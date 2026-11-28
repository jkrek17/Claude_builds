"""Tests for the CollegeFootballData client, its cache and the cfbmodel command. No network."""
import io
import json
import os
import time
import urllib.error
import urllib.request

import pytest

from cfbmodel import cfbd, cli


class FakeResponse:
    def __init__(self, payload):
        self._body = json.dumps(payload).encode()

    def read(self):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeUrlopen:
    """Stands in for urllib.request.urlopen: replays a script of status codes, records requests."""

    def __init__(self, statuses=(200,), payload=None):
        self.statuses = list(statuses)
        self.payload = payload if payload is not None else [{"id": 1, "home_team": "Miami"}]
        self.requests = []

    def __call__(self, req, timeout=None):
        self.requests.append(req)
        status = self.statuses.pop(0) if self.statuses else 200
        if status != 200:
            raise urllib.error.HTTPError(req.full_url, status, "error", {}, io.BytesIO(b"{}"))
        return FakeResponse(self.payload)


@pytest.fixture
def key(monkeypatch):
    monkeypatch.setenv("CFBD_API_KEY", "secret123")


@pytest.fixture
def no_sleep(monkeypatch):
    waits = []
    monkeypatch.setattr(time, "sleep", waits.append)
    return waits


def test_get_caches_and_second_call_does_not_hit_network(tmp_path, monkeypatch, key):
    fake = FakeUrlopen()
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    before = cfbd.calls_made()
    first = cfbd.get("games", {"year": 2023}, tmp_path)
    second = cfbd.get("games", {"year": 2023}, tmp_path)
    assert len(fake.requests) == 1
    assert first == second == fake.payload
    assert cfbd.calls_made() == before + 1
    req = fake.requests[0]
    assert req.full_url == "https://api.collegefootballdata.com/games?year=2023"
    assert req.get_header("Authorization") == "Bearer secret123"
    assert (tmp_path / "cfbd" / "games" / "year=2023.json").exists()


def test_cache_path_uses_sorted_params_and_nested_endpoint(tmp_path, monkeypatch, key):
    monkeypatch.setattr(urllib.request, "urlopen", FakeUrlopen())
    cfbd.get("teams/fbs", {"year": 2023, "seasonType": "both"}, tmp_path)
    assert (tmp_path / "cfbd" / "teams" / "fbs" / "seasonType=both_year=2023.json").exists()
    # Same params in another order hit the same cache file.
    fake = FakeUrlopen()
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    cfbd.get("teams/fbs", {"seasonType": "both", "year": 2023}, tmp_path)
    assert fake.requests == []


def test_live_refetches_only_when_cache_older_than_six_hours(tmp_path, monkeypatch, key):
    fake = FakeUrlopen()
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    cfbd.get("games", {"year": 2026}, tmp_path, live=True)
    cfbd.get("games", {"year": 2026}, tmp_path, live=True)
    assert len(fake.requests) == 1
    path = tmp_path / "cfbd" / "games" / "year=2026.json"
    old = time.time() - 7 * 3600
    os.utime(path, (old, old))
    cfbd.get("games", {"year": 2026}, tmp_path)  # not live: old cache is fine
    assert len(fake.requests) == 1
    cfbd.get("games", {"year": 2026}, tmp_path, live=True)
    assert len(fake.requests) == 2


def test_missing_key_raises_clear_error(tmp_path, monkeypatch):
    monkeypatch.delenv("CFBD_API_KEY", raising=False)
    fake = FakeUrlopen()
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    with pytest.raises(cfbd.MissingKey) as err:
        cfbd.get("games", {"year": 2023}, tmp_path)
    assert "CFBD_API_KEY" in str(err.value)
    assert fake.requests == []


def test_rejected_key_names_variable_without_echoing_key(tmp_path, monkeypatch, key, no_sleep):
    monkeypatch.setattr(urllib.request, "urlopen", FakeUrlopen(statuses=[401]))
    with pytest.raises(cfbd.MissingKey) as err:
        cfbd.get("games", {"year": 2023}, tmp_path)
    msg = str(err.value)
    assert "CFBD_API_KEY" in msg and "secret123" not in msg and "\n" not in msg


def test_key_never_written_to_cache(tmp_path, monkeypatch, key):
    monkeypatch.setattr(urllib.request, "urlopen", FakeUrlopen())
    cfbd.get("games", {"year": 2023}, tmp_path)
    cfbd.get("lines", {"year": 2023}, tmp_path)
    files = [p for p in tmp_path.rglob("*") if p.is_file()]
    assert files
    for p in files:
        assert b"secret123" not in p.read_bytes()
        assert "secret123" not in str(p)


def test_429_retries_then_succeeds(tmp_path, monkeypatch, key, no_sleep):
    fake = FakeUrlopen(statuses=[429, 429, 200])
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    assert cfbd.get("games", {"year": 2023}, tmp_path) == fake.payload
    assert len(fake.requests) == 3
    assert no_sleep == [2, 4]


def test_429_gives_up_after_four_retries(tmp_path, monkeypatch, key, no_sleep):
    fake = FakeUrlopen(statuses=[429] * 10)
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    with pytest.raises(cfbd.CFBDError) as err:
        cfbd.get("games", {"year": 2023}, tmp_path)
    assert len(fake.requests) == 5
    assert no_sleep == [2, 4, 8, 16]
    assert "secret123" not in str(err.value)
    assert not (tmp_path / "cfbd" / "games" / "year=2023.json").exists()


def test_server_error_is_retried(tmp_path, monkeypatch, key, no_sleep):
    fake = FakeUrlopen(statuses=[503, 200])
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    assert cfbd.get("games", {"year": 2023}, tmp_path) == fake.payload
    assert no_sleep == [2]


def test_parse_seasons():
    assert cli.parse_seasons("2013-2015") == [2013, 2014, 2015]
    assert cli.parse_seasons("2023") == [2023]
    with pytest.raises(ValueError):
        cli.parse_seasons("2015-2013")


def test_fetch_caches_every_season_and_is_free_second_time(tmp_path, monkeypatch, key, capsys):
    fake = FakeUrlopen()
    monkeypatch.setattr(urllib.request, "urlopen", fake)
    assert cli.main(["fetch", "--seasons", "2013-2014", "--data-dir", str(tmp_path)]) == 0
    first = len(fake.requests)
    assert first == 2 * len(cli.FETCH_PLAN)
    assert cli.main(["fetch", "--seasons", "2013-2014", "--data-dir", str(tmp_path)]) == 0
    assert len(fake.requests) == first
    assert "secret123" not in capsys.readouterr().out


def test_full_backfill_stays_under_call_budget():
    assert len(cli.parse_seasons("2013-2026")) * len(cli.FETCH_PLAN) < 400


def test_fetch_without_key_prints_one_line_error(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("CFBD_API_KEY", raising=False)
    monkeypatch.setattr(urllib.request, "urlopen", FakeUrlopen())
    assert cli.main(["fetch", "--seasons", "2023", "--data-dir", str(tmp_path)]) == 1
    err = capsys.readouterr().err.strip()
    assert "CFBD_API_KEY" in err and "\n" not in err


def test_help_exits_zero(capsys):
    with pytest.raises(SystemExit) as exit_:
        cli.main(["--help"])
    assert exit_.value.code == 0
    assert "fetch" in capsys.readouterr().out
