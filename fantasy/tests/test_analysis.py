import pytest

from fantasy_coach import analysis as a
from fantasy_coach.analysis import Game, Player, SeasonResult

SLOTS = {"QB": 1, "RB": 2, "WR": 2, "TE": 1, "W/R/T": 1, "K": 1, "DEF": 1, "BN": 2, "IR": 1}


def P(pid, name, pos, ppg, slot="BN", status="", bye=None, weeks=6):
    return Player(player_id=pid, name=name, positions=[pos, "W/R/T"] if pos in ("RB", "WR", "TE") else [pos],
                  status=status, selected_position=slot, bye_week=bye,
                  weekly_points={w: ppg for w in range(1, weeks + 1)})


def roster():
    return [
        P(1, "QB1", "QB", 20, "QB"), P(2, "RB1", "RB", 15, "RB"), P(3, "RB2", "RB", 6, "RB"),
        P(4, "WR1", "WR", 14, "WR"), P(5, "WR2", "WR", 12, "WR"), P(6, "TE1", "TE", 8, "TE"),
        P(7, "WR3", "WR", 9, "W/R/T"), P(8, "K1", "K", 8, "K"), P(9, "DEF1", "DEF", 7, "DEF"),
        P(10, "RB3", "RB", 3), P(11, "WR4", "WR", 2),
    ]


def test_health_bucket_collapses_yahoo_codes():
    assert a.health_bucket("IR-R") == "IR"
    assert a.health_bucket("PUP-P") == "PUP"
    assert a.health_bucket("NFI-R") == "PUP"
    assert a.health_bucket("Q") == "Q"
    assert a.health_bucket(None) == ""


def test_projection_blends_recent_form_with_season_average():
    p = P(1, "x", "WR", 0)
    p.weekly_points = {1: 10, 2: 10, 3: 10, 4: 20, 5: 20, 6: 20}
    proj = a.project(p, before_week=7)
    assert proj.games == 6
    assert proj.season_ppg == 15
    assert proj.recent_ppg == 20
    assert proj.points == 0.6 * 20 + 0.4 * 15


def test_projection_ignores_future_weeks_bye_and_zero_weeks():
    p = P(1, "x", "WR", 0, bye=2)
    p.weekly_points = {1: 10, 2: 0, 3: 0, 4: 12, 8: 50}
    proj = a.project(p, before_week=7)
    assert proj.games == 2
    assert proj.season_ppg == 11


def test_projection_falls_back_to_last_season_early():
    p = P(1, "x", "WR", 0)
    p.weekly_points = {1: 6}
    p.prior_ppg = 15
    assert a.project(p, 2).points == pytest.approx((1 / 3) * 6 + (2 / 3) * 15)
    p.weekly_points = {}
    assert a.project(p, 1).points == 15


def test_slot_accepts_flex_and_idp():
    assert a.slot_accepts("W/R/T") >= {"WR", "RB", "TE"}
    assert "QB" in a.slot_accepts("Q/W/R/T")
    assert a.slot_accepts("DB") >= {"CB", "S"}
    assert a.slot_accepts("QB") == {"QB"}


def test_optimal_lineup_fills_dedicated_slots_before_flex():
    lineup = a.optimal_lineup(roster(), SLOTS, lambda p: a.project(p, 7).points)
    by_slot = {}
    for slot, p, _ in lineup.starters:
        by_slot.setdefault(slot, []).append(p.name)
    assert by_slot["RB"] == ["RB1", "RB2"]
    assert by_slot["WR"] == ["WR1", "WR2"]
    assert by_slot["W/R/T"] == ["WR3"]
    assert {p.name for p, _ in lineup.bench} == {"RB3", "WR4"}
    assert round(lineup.total, 2) == 20 + 15 + 6 + 14 + 12 + 8 + 9 + 8 + 7


def test_optimal_lineup_skips_players_parked_on_ir():
    r = roster()
    r[1].selected_position = "IR"
    lineup = a.optimal_lineup(r, SLOTS, lambda p: a.project(p, 7).points)
    assert "RB1" not in {p.name for _, p, _ in lineup.starters if p}


def test_lineup_advice_benches_bye_week_starter_and_flags_it():
    r = roster()
    r[3].bye_week = 7  # WR1 starting on bye
    advice = a.lineup_advice(r, SLOTS, week=7)
    assert [s["player"] for s in advice["sit"]] == ["WR1"]
    assert [s["player"] for s in advice["start"]] == ["RB3"]
    assert advice["projected_gain"] == 3.0
    assert any("WR1" in x and "bye" in x for x in advice["alerts"])


def test_lineup_advice_flags_out_starter_and_open_ir():
    r = roster()
    r[0].status = "O"
    advice = a.lineup_advice(r, SLOTS, week=7)
    assert any("QB1" in x and "listed O" in x for x in advice["alerts"])
    assert any("QB1" in x and "IR slot" in x for x in advice["alerts"])


def test_waiver_recommends_upgrade_and_drops_least_valuable_player():
    fa = [P(50, "FA-WR", "WR", 13, slot=None), P(51, "FA-RB", "RB", 1, slot=None)]
    res = a.waiver_recommendations(roster(), fa, SLOTS, week=7)
    recs = res["recommendations"]
    assert [r["add"] for r in recs] == ["FA-WR"]
    assert recs[0]["drop"] == "WR4"
    assert recs[0]["would_start_at"] in ("WR", "W/R/T")
    assert recs[0]["lineup_gain"] > 0


def test_waiver_uses_open_roster_spot_without_dropping():
    r = roster()[:-2]  # two open bench spots
    res = a.waiver_recommendations(r, [P(50, "FA-RB", "RB", 5, slot=None)], SLOTS, week=7)
    assert res["recommendations"][0]["drop"] is None


def test_waiver_never_drops_ir_stash():
    r = roster()
    r.append(P(12, "Stash", "RB", 1, slot="IR", status="IR"))
    res = a.waiver_recommendations(r, [P(50, "FA-WR", "WR", 13, slot=None)], SLOTS, week=7)
    assert all(rec["drop"] != "Stash" for rec in res["recommendations"])


def test_trade_between_complementary_rosters_is_win_win():
    mine = roster()
    theirs = [P(20, "T-QB", "QB", 18, "QB"), P(21, "T-RB1", "RB", 16, "RB"), P(22, "T-RB2", "RB", 14, "RB"),
              P(23, "T-RB3", "RB", 13, "W/R/T"), P(24, "T-WR1", "WR", 5, "WR"), P(25, "T-WR2", "WR", 4, "WR"),
              P(26, "T-TE", "TE", 7, "TE"), P(27, "T-K", "K", 7, "K"), P(28, "T-DEF", "DEF", 6, "DEF"),
              P(29, "T-RB4", "RB", 12)]
    res = a.evaluate_trade(mine, theirs, [mine[6]], [theirs[3]], SLOTS, week=7)  # WR3 (9) for T-RB3 (13)
    assert res["you"]["strength_change"] > 0
    assert res["them"]["strength_change"] > 0
    assert res["verdict"].startswith("win-win")


def test_trade_forces_drop_when_receiving_more_players():
    mine = roster()  # 11 of 11 active spots used
    theirs = [P(20, "A", "WR", 10, "WR"), P(21, "B", "RB", 10, "RB")]
    res = a.evaluate_trade(mine, theirs, [mine[6]], theirs, SLOTS, week=7)
    assert res["you"]["would_need_to_drop"] == ["WR4"]


def test_find_trades_only_returns_deals_that_help_both_sides():
    mine = roster()
    theirs = [P(20, "T-QB", "QB", 18, "QB"), P(21, "T-RB1", "RB", 16, "RB"), P(22, "T-RB2", "RB", 14, "RB"),
              P(23, "T-RB3", "RB", 13, "W/R/T"), P(24, "T-WR1", "WR", 5, "WR"), P(25, "T-WR2", "WR", 4, "WR"),
              P(26, "T-TE", "TE", 7, "TE"), P(27, "T-K", "K", 7, "K"), P(28, "T-DEF", "DEF", 6, "DEF"),
              P(29, "T-RB4", "RB", 12)]
    trades = a.find_trades(mine, {"t.2": ("Them", theirs)}, SLOTS, week=7)
    assert trades
    assert all(t["your_gain"] > 0 and t["their_gain"] > 0 for t in trades)


def _season(season, rows, finished=True):
    return [SeasonResult(season=season, league_key=f"{season}", team_key=f"{season}.t.{i}", team_name=f"T{i}",
                         manager_id=m, manager_name=m.upper(), rank=rank, wins=w, losses=l, ties=0,
                         points_for=pf, points_against=pa, finished=finished, made_playoffs=rank <= 2)
            for i, (m, rank, w, l, pf, pa) in enumerate(rows)]


def test_summarize_history_counts_titles_and_records():
    results = (_season(2024, [("a", 1, 10, 4, 1500, 1300), ("b", 2, 9, 5, 1450, 1350), ("c", 3, 2, 12, 1100, 1600)])
               + _season(2025, [("b", 1, 11, 3, 1700, 1300), ("a", 2, 8, 6, 1400, 1400), ("c", 3, 2, 12, 1200, 1600)])
               + _season(2026, [("c", 1, 6, 0, 800, 500), ("a", 2, 3, 3, 600, 600), ("b", 3, 0, 6, 400, 800)], finished=False))
    h = a.summarize_history(results)
    by = {m["manager_id"]: m for m in h["managers"]}
    assert by["a"]["championships"] == 1 and by["a"]["championship_years"] == [2024]
    assert by["b"]["championships"] == 1
    assert by["c"]["championships"] == 0  # 2026 is still in progress
    assert by["a"]["wins"] == 21 and by["a"]["seasons"] == 3
    assert h["seasons"][0]["season"] == 2026 and h["seasons"][0]["champion"] is None
    most_pf = next(r for r in h["records"] if r["record"] == "most points for in a season")
    assert (most_pf["season"], most_pf["manager"]) == (2025, "B")


def test_head_to_head_aggregates_across_seasons_and_skips_consolation():
    owners = {(2025, "x1"): ("a", "A"), (2025, "x2"): ("b", "B"), (2026, "y1"): ("b", "B"), (2026, "y2"): ("a", "A")}
    games = [Game(2025, 1, "x1", "x2", 100, 90), Game(2025, 15, "x1", "x2", 80, 120, is_playoffs=True),
             Game(2026, 3, "y1", "y2", 70, 110), Game(2026, 16, "y1", "y2", 200, 10, is_consolation=True)]
    h = a.head_to_head(games, owners, focus_manager_id="a")
    (row,) = h["rivalries"]
    assert (row["opponent"], row["wins"], row["losses"], row["playoff_losses"]) == ("B", 2, 1, 1)
    assert row["points_for"] == 290


def test_lineup_advice_flags_half_empty_multi_spot_slot():
    r = roster()
    r[2].selected_position = "BN"  # RB2 benched, leaving one of two RB spots empty
    advice = a.lineup_advice(r, SLOTS, week=7)
    assert "1 empty starting spot(s) at RB." in advice["alerts"]
