from datetime import datetime, timedelta, timezone

import pytest

from sharpbet import ledger
from sharpbet.cli import main
from sharpbet.odds_math import (american_to_decimal, decimal_to_american, devig_multiplicative,
                                devig_power, expected_value, fair_probs, kelly_fraction)
from sharpbet.scan import find_plays
from sharpbet.sizing import select_and_size

NOW = datetime(2026, 11, 15, 15, 0, tzinfo=timezone.utc)


def iso(dt):
    return dt.isoformat().replace("+00:00", "Z")


def book(key, h2h, age_min=5, spreads=None):
    stamp = iso(NOW - timedelta(minutes=age_min))
    markets = [{"key": "h2h", "last_update": stamp,
                "outcomes": [{"name": n, "price": p} for n, p in h2h]}]
    if spreads:
        markets.append({"key": "spreads", "last_update": stamp,
                        "outcomes": [{"name": n, "price": p, "point": pt} for n, p, pt in spreads]})
    return {"key": key, "title": key, "last_update": stamp, "markets": markets}


def event(eid, books, hours=3):
    return {"id": eid, "sport_title": "NFL", "commence_time": iso(NOW + timedelta(hours=hours)),
            "home_team": "A", "away_team": "B", "bookmakers": books}


def test_odds_conversion():
    assert american_to_decimal(150) == 2.5
    assert american_to_decimal(-200) == 1.5
    assert decimal_to_american(2.5) == 150
    assert decimal_to_american(1.5) == -200


def test_devig_sums_to_one_and_fair_is_conservative():
    prices = [1.25, 4.30]
    mult, power = devig_multiplicative(prices), devig_power(prices)
    assert sum(mult) == pytest.approx(1)
    assert sum(power) == pytest.approx(1, abs=1e-9)
    assert power[1] < mult[1]  # power takes more vig out of the longshot
    assert fair_probs(prices) == [min(a, b) for a, b in zip(mult, power)]
    assert devig_multiplicative([1.91, 1.91]) == pytest.approx([0.5, 0.5])


def test_ev_and_kelly():
    assert expected_value(0.5, 2.1) == pytest.approx(0.05)
    assert kelly_fraction(0.5, 2.1) == pytest.approx(0.05 / 1.1)
    assert kelly_fraction(0.5, 1.9) == 0


def test_finds_edge_only_at_matching_fresh_lines():
    pin = book("pinnacle", [("A", 1.95), ("B", 1.95)], spreads=[("A", 1.95, -3.0), ("B", 1.95, 3.0)])
    events = [event("e1", [
        pin,
        book("good", [("A", 2.06), ("B", 1.80)]),                      # +3% on A
        book("fair", [("A", 1.91), ("B", 1.91)]),                      # no edge
        book("stale", [("A", 2.10), ("B", 1.75)], age_min=90),         # too old
        book("error", [("A", 2.60), ("B", 1.50)]),                     # +30%: treated as bad data
        book("offline", [("A", 1.9), ("B", 1.9)], spreads=[("A", 2.2, -3.5), ("B", 1.7, 3.5)]),  # other point
    ])]
    plays = find_plays(events, NOW)
    assert [(p.book, p.selection) for p in plays] == [("good", "A")]
    assert plays[0].ev == pytest.approx(0.03)
    assert find_plays(events, NOW, books={"fair"}) == []


def test_skips_started_games_and_missing_sharp_book():
    soft = book("good", [("A", 2.2), ("B", 1.7)])
    pin = book("pinnacle", [("A", 1.95), ("B", 1.95)])
    assert find_plays([event("live", [pin, soft], hours=-1)], NOW) == []
    assert find_plays([event("nosharp", [soft])], NOW) == []


def make_plays():
    pin = book("pinnacle", [("A", 1.95), ("B", 1.95)])
    events = [event(f"e{i}", [pin, book("x", [("A", price), ("B", 1.8)]),
                              book("y", [("A", price - 0.01), ("B", 1.8)])])
              for i, price in enumerate([2.06, 2.10, 2.20, 2.08])]
    return find_plays(events, NOW)


def test_top_three_one_per_game_with_caps():
    picks = select_and_size(make_plays(), 100)
    assert [p.play.event_id for p in picks] == ["e2", "e1", "e3"]
    assert all(p.play.book == "x" for p in picks)  # best price per game
    for p in picks:
        assert p.stake <= 100 * 0.25 * p.play.kelly + 1e-9
        assert p.stake <= 5
    big = select_and_size(make_plays(), 100, kelly_multiplier=5)
    assert [p.stake for p in big] == [5, 5, 5]


def test_daily_cap_and_open_games_respected():
    picks = select_and_size(make_plays(), 100, kelly_multiplier=5, staked_today=8)
    assert [p.stake for p in picks] == [5, 2]
    assert select_and_size(make_plays(), 100, staked_today=15) == []
    picks = select_and_size(make_plays(), 100, skip_events={"e2"})
    assert "e2" not in [p.play.event_id for p in picks]


def test_ledger_round_trip(tmp_path):
    path = tmp_path / "picks.csv"
    picks = select_and_size(make_plays(), 100, kelly_multiplier=5)
    rows = ledger.record(path, picks, NOW)
    assert [r["id"] for r in rows] == [1, 2, 3]
    assert ledger.settle(path, 1, "win")["pnl"] == "6.00"  # $5 at 2.20
    ledger.settle(path, 2, "loss")
    ledger.settle(path, 3, "push")
    rows = ledger.load(path)
    assert ledger.settled_pnl(rows) == pytest.approx(1.0)
    assert ledger.open_rows(rows) == []
    assert ledger.staked_on(rows, NOW.astimezone().date()) == 15
    with pytest.raises(KeyError):
        ledger.settle(path, 99, "win")


def test_cli_sample_scan(tmp_path, capsys):
    path = tmp_path / "picks.csv"
    assert main(["--ledger", str(path), "scan", "--sample", "--log"]) == 0
    out = capsys.readouterr().out
    assert "SAMPLE DATA" in out and "#3" in out and "#4" not in out
    assert "BetMGM  +550" not in out  # stale and implausible line is excluded
    assert not path.exists()  # sample picks are never logged
    assert main(["--ledger", str(path), "report"]) == 0


def test_cli_needs_key(monkeypatch, capsys):
    monkeypatch.delenv("ODDS_API_KEY", raising=False)
    assert main(["scan"]) == 2
    assert "ODDS_API_KEY" in capsys.readouterr().err
