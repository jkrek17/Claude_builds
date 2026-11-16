"""Tests for the page's top plays file (no downloads)."""
import datetime as dt

from nflmodel import plays as P
from test_betting import PROBS, event

NOW = dt.datetime(2026, 11, 20, tzinfo=dt.timezone.utc)
Q = [20, 40, 60, 80, 100]


def row(pid, player, team, opp, gid, q=Q):
    return {"player_id": pid, "player": player, "team": team, "opponent": opp, "position": "WR", "game_id": gid,
            "prop": "rec_yds", "q": q, "pmf": None}


def props():
    kc, buf = "2026_11_DEN_KC", "2026_11_MIA_BUF"
    return {"season": 2026, "week": 11, "probs": PROBS, "rows": [
        row("1", "Al One", "KC", "DEN", kc), row("2", "Bo Two", "KC", "DEN", kc), row("3", "Cy Three", "DEN", "KC", kc),
        row("4", "Di Four", "BUF", "MIA", buf)]}


def events():
    return [
        event("e1", "Kansas City Chiefs", "Denver Broncos", "2026-11-22T18:00:00Z", [
            ("Al One", "player_reception_yds", 80.5, -110, -110),    # model under about 70%: a play
            ("Bo Two", "player_reception_yds", 90.5, -110, -110),    # stronger still
            ("Cy Three", "player_reception_yds", 85.5, -110, -110),  # third in the game: dropped by rule 4
        ]),
        event("e2", "Buffalo Bills", "Miami Dolphins", "2026-11-19T18:00:00Z", [   # already kicked off
            ("Di Four", "player_reception_yds", 90.5, -110, -110)]),
        event("e3", "Dallas Cowboys", "New York Giants", "2026-11-29T18:00:00Z", [  # not this week
            ("Al One", "player_reception_yds", 90.5, -110, -110)]),
    ]


def test_build_ranks_caps_per_game_and_skips_started_games():
    out = P.build(props(), events(), NOW)
    assert [p["player"] for p in out["plays"]] == ["Bo Two", "Cy Three"]
    assert out["games_open"] == 1 and out["games_priced"] == 1
    top = out["plays"][0]
    assert top["player_id"] == "2" and top["game_id"] == "2026_11_DEN_KC" and top["side"] == "under"
    assert top["price"] == "-110" and top["line"] == 90.5
    assert top["edge"] >= out["plays"][1]["edge"] >= 0.031 and top["model_edge"] >= 0.10


def test_build_with_no_lines_is_empty():
    out = P.build(props(), [], NOW)
    assert out["plays"] == [] and out["games_priced"] == 0


def test_fetch_prices_only_this_weeks_open_games(monkeypatch):
    asked = []

    def fake(path, **params):
        asked.append(path)
        return events() if path == "/events" else {"id": path}

    monkeypatch.setattr(P.B, "_get", fake)
    P.fetch(props(), NOW)
    assert asked == ["/events", "/events/e1/odds"]
