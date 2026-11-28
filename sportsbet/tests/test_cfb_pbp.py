import io
import os
import time
import urllib.request

import pandas as pd
import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from cfbmodel import pbp


def plays(**cols):
    n = len(next(iter(cols.values())))
    base = dict(period=[1] * n, pos_score_diff_start=[0.0] * n, rush=[1.0] * n, pass_=[0.0] * n,
                EPA=[0.1] * n)
    base.update(cols)
    base["pass"] = base.pop("pass_")
    return pd.DataFrame(base)


def full_frame(season, n=3):
    """A stand-in for the sportsdataverse file: every KEEP_COLS column plus two extras."""
    data = {c: [0.0] * n for c in pbp.KEEP_COLS}
    data["season"] = [season] * n
    data["game_id"] = [401520145] * n
    data["start_date"] = ["2023-08-26T21:30:00.000Z"] * n
    data["pos_team"] = ["Jacksonville State"] * n
    data["id_play"] = ["x"] * n
    data["desc"] = ["a long play description"] * n
    return pd.DataFrame(data)


def parquet_bytes(df):
    buf = io.BytesIO()
    pq.write_table(pa.Table.from_pandas(df, preserve_index=False), buf)
    return buf.getvalue()


class FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def no_network(*a, **k):
    raise AssertionError("network called")


def test_is_garbage_thresholds():
    df = plays(period=[2, 2, 3, 3, 4, 4, 1, 5],
               pos_score_diff_start=[39.0, -38.0, -29.0, 28.0, 23.0, -22.0, 50.0, 30.0])
    assert pbp.is_garbage(df).tolist() == [True, False, True, False, True, False, False, False]


def test_scrimmage_drops_kicks_and_null_epa():
    df = plays(rush=[1.0, 0.0, 0.0, 1.0, 1.0],
               pass_=[0.0, 1.0, 0.0, 0.0, 0.0],
               EPA=[0.2, -0.3, 1.0, None, 0.5],
               period=[1, 1, 1, 1, 4],
               pos_score_diff_start=[0.0, 0.0, 0.0, 0.0, 30.0])
    out = pbp.scrimmage(df)
    assert out["EPA"].tolist() == [0.2, -0.3]


def test_load_reads_slim_cache_without_network(tmp_path, monkeypatch):
    monkeypatch.setattr(urllib.request, "urlopen", no_network)
    slim = tmp_path / "pbp" / "slim_2019.parquet"
    slim.parent.mkdir(parents=True)
    full_frame(2019)[pbp.KEEP_COLS].to_parquet(slim, index=False)
    out = pbp.load(2019, tmp_path)
    assert list(out.columns) == pbp.KEEP_COLS + pbp.LINE_COLS
    assert len(out) == 3
    assert out["spread"].isna().all() and out["over_under"].isna().all()


def test_load_downloads_keeps_only_keep_cols_and_deletes_full_file(tmp_path, monkeypatch):
    urls = []

    def fake_urlopen(req, *a, **k):
        urls.append(getattr(req, "full_url", req))
        return FakeResponse(parquet_bytes(full_frame(2021)))

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    out = pbp.load(2021, tmp_path)
    assert urls == [pbp.PBP_URL.format(season=2021)]
    assert list(out.columns) == pbp.KEEP_COLS + pbp.LINE_COLS
    assert sorted(p.name for p in (tmp_path / "pbp").iterdir()) == ["slim_2021.parquet"]
    assert pq.ParquetFile(tmp_path / "pbp" / "slim_2021.parquet").schema_arrow.names == pbp.KEEP_COLS


def test_current_season_refetched_after_six_hours_past_season_never(tmp_path, monkeypatch):
    calls = []

    def fake_urlopen(req, *a, **k):
        calls.append(1)
        return FakeResponse(parquet_bytes(full_frame(2026)))

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(pbp, "current_season", lambda: 2026)
    for season in (2026, 2025):
        slim = tmp_path / "pbp" / f"slim_{season}.parquet"
        slim.parent.mkdir(parents=True, exist_ok=True)
        full_frame(season)[pbp.KEEP_COLS].to_parquet(slim, index=False)
        old = time.time() - 7 * 3600
        os.utime(slim, (old, old))

    pbp.load(2025, tmp_path)
    assert calls == []
    pbp.load(2026, tmp_path)
    assert calls == [1]
    pbp.load(2026, tmp_path)  # fresh now
    assert calls == [1]


def test_current_season_follows_calendar():
    import datetime as dt
    assert pbp.current_season(dt.date(2026, 11, 28)) == 2026
    assert pbp.current_season(dt.date(2027, 1, 10)) == 2026
    assert pbp.current_season(dt.date(2027, 8, 1)) == 2027


def test_failed_download_leaves_no_partial_file(tmp_path, monkeypatch):
    class Broken(FakeResponse):
        def read(self, *a):
            raise OSError("connection reset")

    monkeypatch.setattr(urllib.request, "urlopen", lambda *a, **k: Broken(b""))
    with pytest.raises(OSError):
        pbp.load(2022, tmp_path)
    assert list((tmp_path / "pbp").iterdir()) == []


def test_line_columns_kept_when_source_has_them(tmp_path, monkeypatch):
    full = full_frame(2020)
    full["spread"] = [-7.5] * 3
    full["over_under"] = [55.0] * 3
    monkeypatch.setattr(urllib.request, "urlopen", lambda *a, **k: FakeResponse(parquet_bytes(full)))
    out = pbp.load(2020, tmp_path)
    assert list(out.columns) == pbp.KEEP_COLS + pbp.LINE_COLS
    assert out["spread"].tolist() == [-7.5] * 3 and out["over_under"].tolist() == [55.0] * 3
