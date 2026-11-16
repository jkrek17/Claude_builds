"""Tests for the betting rules (no downloads)."""
import pytest

from nflmodel import betting as B

PROBS = [0.1, 0.3, 0.5, 0.7, 0.9]


def event(eid, home, away, kickoff, players):
    """players: (name, market, line, over price, under price)."""
    markets = {}
    for name, market, line, over, under in players:
        markets.setdefault(market, []).extend([
            {"name": "Over", "description": name, "point": line, "price": over},
            {"name": "Under", "description": name, "point": line, "price": under}])
    return {"id": eid, "home_team": home, "away_team": away, "commence_time": kickoff,
            "bookmakers": [{"key": "fanduel", "markets": [{"key": k, "outcomes": v} for k, v in markets.items()]},
                           {"key": "draftkings", "markets": [{"key": "player_receptions", "outcomes": [
                               {"name": "Over", "description": "Other Book", "point": 9.5, "price": -110},
                               {"name": "Under", "description": "Other Book", "point": 9.5, "price": -110}]}]}]}


def yards(player, team, q):
    return {"player": player, "team": team, "prop": "rec_yds", "q": q, "pmf": None}


def test_prices_and_blend():
    assert B.decimal(-110) == pytest.approx(1.9091, abs=1e-4)
    assert B.decimal(150) == 2.5
    assert B.american(2.5) == "+150" and B.american(1.5) == "-200"
    assert B.blend(0.7, 0.5, 0.0) == pytest.approx(0.5)
    assert B.blend(0.7, 0.5, 1.0) == pytest.approx(0.7)
    assert 0.5 < B.blend(0.7, 0.5) < 0.7


def test_prob_under_for_yards_and_counts():
    row = yards("A", "KC", [20, 40, 60, 80, 100])
    assert B.prob_under(row, 60, PROBS) == pytest.approx(0.5)
    assert B.prob_under(row, 50, PROBS) == pytest.approx(0.4)
    assert B.prob_under(row, 5, PROBS) == 0.005 and B.prob_under(row, 500, PROBS) == 0.995
    count = {"pmf": [0.1, 0.2, 0.3, 0.4], "q": None}
    assert B.prob_under(count, 1.5, PROBS) == pytest.approx(0.3)


def test_main_line_is_the_most_even_one_and_fanduel_only():
    ev = event("e1", "Kansas City Chiefs", "Buffalo Bills", "2026-11-22T18:00:00Z",
               [("Travis Kelce", "player_reception_yds", 39.5, -250, 190),
                ("Travis Kelce", "player_reception_yds", 55.5, -112, -112)])
    lines = B.main_lines(ev)
    assert len(lines) == 1 and lines[0]["line"] == 55.5
    assert lines[0]["p_book_under"] == pytest.approx(0.5)


def test_only_unders_with_enough_edge_qualify():
    props = {"probs": PROBS, "rows": [yards("Travis Kelce", "KC", [20, 30, 40, 50, 60]),      # 55.5 is high: under likely
                                      yards("Josh Palmer Jr.", "BUF", [40, 50, 60, 70, 80])]}  # 55.5 is about fair
    ev = event("e1", "Kansas City Chiefs", "Buffalo Bills", "2026-11-22T18:00:00Z",
               [("Travis Kelce", "player_reception_yds", 55.5, -112, -112),
                ("Josh Palmer", "player_reception_yds", 55.5, -112, -112),
                ("Nobody Known", "player_reception_yds", 55.5, -112, -112)])
    c = B.candidates(props, [ev])
    assert [x["player"] for x in c] == ["Travis Kelce"]
    k = c[0]
    assert k["p_model"] == pytest.approx(0.81) and k["edge"] >= B.MIN_EDGE and k["score"] >= B.MIN_BLEND_EDGE
    # at the worst acceptable price both rules still hold, and it is worse than the posted price
    assert k["worst_price"] < k["price"]
    assert k["p_model"] * k["worst_price"] - 1 >= B.MIN_EDGE - 1e-9


def cand(name, game, kickoff, score):
    return {"name_key": name, "game_key": game, "kickoff": kickoff, "score": score}


def test_select_caps_per_player_per_game_and_per_week():
    c = [cand("a", "g1", "2026-11-22T18:00:00Z", 0.20), cand("a", "g1", "2026-11-22T18:00:00Z", 0.10),
         cand("b", "g1", "2026-11-22T18:00:00Z", 0.15), cand("c", "g1", "2026-11-22T18:00:00Z", 0.12)]
    c += [cand(f"p{i}", f"h{i}", "2026-11-23T01:20:00Z", 0.5) for i in range(12)]
    picks = B.select(c)
    assert len(picks) == B.WEEK_BETS
    assert [p["name_key"] for p in picks[:2]] == ["a", "b"]          # earliest kickoff first, two from g1
    assert picks[0]["score"] == 0.20                                    # the player's better line
    assert len(B.select(c, placed=8)) == 2 and B.select(c, placed=10) == []
    assert "cap reached" in B.render([], 10)
