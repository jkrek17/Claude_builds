import datetime as dt
import json

import numpy as np
import pandas as pd
import pytest

from nflmodel import challenge, loop, params, site

UTC = dt.timezone.utc
TUE, THU, SUN = (dt.datetime(2026, 11, d, 12, 17, tzinfo=UTC) for d in (10, 12, 15))


def week(number, gameday, n=12, played=False, seed=0):
    rng = np.random.default_rng(seed + number)
    rows = []
    for i in range(n):
        line = float(rng.choice([-6.5, -3.0, 2.5, 3.0, 7.0]))
        hs, as_ = int(rng.integers(10, 35)), int(rng.integers(10, 35))
        rows.append(dict(
            game_id=f"2026_{number:02d}_A{i}_H{i}", season=2026, week=number, game_type="REG", gameday=gameday,
            gametime="13:00", away_team=f"A{i}", home_team=f"H{i}", spread_line=line, total_line=44.5,
            home_moneyline=-150.0, away_moneyline=130.0, pred_margin=line + rng.normal(0, 2),
            pred_total=44.5 + rng.normal(0, 2), qb_assumed=0, wind_mph=np.nan, wind_source="climatology",
            home_score=hs if played else np.nan, away_score=as_ if played else np.nan,
            result=hs - as_ if played else np.nan, total=hs + as_ if played else np.nan))
    return pd.DataFrame(rows)


def schedule(played_through=9):
    days = {9: "2026-11-08", 10: "2026-11-15", 11: "2026-11-22"}
    return pd.concat([week(w, d, played=w <= played_through) for w, d in days.items()], ignore_index=True)


def finish(preds, number):
    """The same schedule with one more week played and its closing spread half a point higher."""
    done = week(number, preds.loc[preds["week"] == number, "gameday"].iloc[0], played=True)
    out = preds.copy()
    mask = out["week"] == number
    for col in ("home_score", "away_score", "result", "total"):
        out.loc[mask, col] = done[col].to_numpy()
    out.loc[mask, "spread_line"] += 0.5
    out.loc[out["week"] > number, "pred_margin"] += 0.3      # new results move the later predictions
    return out


def test_snapshot_type_follows_the_clock():
    assert loop.snapshot_type(TUE) == "early" and loop.snapshot_type(THU) == "official"
    assert loop.snapshot_type(SUN) == "official"
    assert loop.kickoff_utc("2026-11-15", "13:00") == dt.datetime(2026, 11, 15, 18, 0, tzinfo=UTC)


def test_freeze_is_idempotent_and_never_writes_after_kickoff(tmp_path):
    preds = schedule()
    early = loop.freeze(tmp_path, preds, TUE, "v1")
    assert {r["snapshot_type"] for r in early} == {"early"} and len(early) == 12
    assert loop.freeze(tmp_path, preds, TUE, "v1") == []           # same run again writes nothing
    thu = loop.freeze(tmp_path, preds, THU, "v1")
    kinds = [r["snapshot_type"] for r in thu]
    assert kinds.count("official") == 12 and kinds.count("lookahead") == 12
    assert all(r["late_freeze"] == "0" for r in thu)
    assert loop.freeze(tmp_path, preds, SUN, "v1") == []           # already frozen on Thursday
    after_kickoff = dt.datetime(2026, 11, 15, 19, 0, tzinfo=UTC)
    week11 = loop.freeze(tmp_path, preds, after_kickoff, "v1")     # week 10 has started: only week 11 is open
    assert {r["week"] for r in week11} == {"11"} and all(r["late_freeze"] == "1" for r in week11
                                                           if r["snapshot_type"] == "official")
    assert len(loop.read_picks(tmp_path)) == 12 + 24 + 12


def test_a_changed_ledger_is_detected(tmp_path):
    loop.freeze(tmp_path, schedule(), THU, "v1")
    path = tmp_path / "picks.csv"
    lines = path.read_text().splitlines()
    cells = lines[3].split(",")
    cells[loop.PICK_COLS.index("ats_side")] = "home" if cells[loop.PICK_COLS.index("ats_side")] == "away" else "away"
    path.write_text("\n".join(lines[:3] + [",".join(cells)] + lines[4:]) + "\n")
    with pytest.raises(loop.LedgerError):
        loop.read_picks(tmp_path)


def test_grading_uses_the_frozen_line_and_measures_line_movement(tmp_path):
    pick = {c: "" for c in loop.PICK_COLS}
    pick.update(season="2026", week="10", game_id="g", snapshot_type="official", away="AWY", home="HOM",
                pred_margin="6.0", pred_total="40.0", home_win_prob="0.67", ml_pick="HOM", spread_line="3.0",
                ats_side="home", ats_pick="HOM -3", total_line="44.5", ou_side="under", ou_pick="Under 44.5")
    game = pd.Series(dict(result=3.0, total=41.0, home_score=22, away_score=19, spread_line=4.5, total_line=43.5,
                          home_moneyline=-200.0, away_moneyline=170.0))
    g = loop.grade_pick(pick, game)
    assert g["ml_result"] == "W" and g["fav_result"] == "W"
    assert g["ats_result"] == "P"                    # home by 3 against a frozen line of 3, not the closing 4.5
    assert float(g["clv_spread"]) == 1.5             # the line moved 1.5 points toward the home pick
    assert g["ou_result"] == "W" and float(g["clv_total"]) == 1.0
    assert float(g["units"]) == pytest.approx(100 / 110, abs=1e-3)
    away = loop.grade_pick({**pick, "ats_side": "away", "ats_pick": "AWY +3"}, game)
    assert float(away["clv_spread"]) == -1.5


def test_run_grades_only_complete_weeks_and_reports_honestly(tmp_path):
    preds = schedule()
    first = loop.run(tmp_path, preds, THU, "v1")
    assert first["ok"] and first["froze"] == 24 and first["graded_weeks"] == 0
    assert "No week has been graded yet" in (tmp_path / "REPORT.md").read_text()
    partial = finish(preds, 10)
    partial.loc[partial.index[partial["week"] == 10][0], ["result", "total"]] = np.nan
    monday = dt.datetime(2026, 11, 16, 5, 0, tzinfo=UTC)
    assert loop.run(tmp_path, partial, monday, "v1")["graded_weeks"] == 0      # one game still to play
    later = dt.datetime(2026, 11, 17, 10, 17, tzinfo=UTC)
    status = loop.run(tmp_path, finish(preds, 10), later, "v1")
    assert status["graded_weeks"] == 1 and status["snapshot"] == "early"
    report = json.loads((tmp_path / "report.json").read_text())
    official = report["season_to_date"]["official"]
    assert official["spread"]["w"] + official["spread"]["l"] + official["spread"]["p"] == 12
    assert official["clv"]["n"] == 24 and official["paper"]["bets"] == 24
    text = (tmp_path / "REPORT.md").read_text()
    for sentence in ("This record cannot yet tell skill from luck", loop.WEEK_NOTE, loop.PAPER_NOTE, loop.CLV_NOTE):
        assert sentence in text
    assert (tmp_path / "reports" / "2026_w10.md").exists()
    page = site.loop_state(tmp_path)
    assert page["status"]["red"] == [], page["status"]["red"]
    assert page["report"]["week"] == 10
    grades_before = (tmp_path / "grades.csv").read_text()
    loop.run(tmp_path, finish(preds, 10), later, "v1")
    assert (tmp_path / "grades.csv").read_text() == grades_before                # safe to repeat
    frozen = loop.run(tmp_path, finish(preds, 10).assign(pred_margin=preds["pred_margin"]), later, "v1")
    assert any("unchanged from a week ago" in r for r in frozen["red"])          # a model that stopped updating


def test_stale_results_raise_an_alarm_and_block_the_early_look(tmp_path):
    preds = schedule(played_through=8)                    # week 9 finished days ago but has no scores
    status = loop.run(tmp_path, preds, TUE, "v1")
    assert not status["ok"] and status["froze"] == 0
    assert "no score" in status["red"][0]
    assert "A check failed" in (tmp_path / "REPORT.md").read_text()


def test_wild_predictions_raise_an_alarm(tmp_path):
    preds = schedule()
    preds.loc[preds["week"] == 10, "pred_total"] += 9.0
    red, _ = loop.check_before(preds, THU, [])
    assert any("tilted" in r or "unusually far" in r for r in red)


# ---------------------------------------------------------------- alternative settings

def seasons(n_seasons=16, per=270):
    return np.repeat(np.arange(2010, 2010 + n_seasons), per)


def test_t_crit_matches_the_table():
    assert challenge.t_crit(16) == pytest.approx(3.25, abs=0.01)
    assert challenge.t_crit(14) == pytest.approx(3.33, abs=0.01)


def test_promotion_test_passes_a_real_effect_and_rejects_noise():
    rng = np.random.default_rng(1)
    season = seasons()
    champ = np.full(len(season), 175.0)
    noise = rng.normal(0, 30, len(season))
    assert not challenge.look(noise, season, champ)["pass"]
    assert not challenge.look(noise + 0.5, season, champ)["pass"]        # ahead, but nowhere near enough
    real = challenge.look(noise + 3.0, season, champ)
    assert real["pass"] and real["t"] > real["crit"] and real["season_share"] >= 0.65
    tiny = challenge.look(rng.normal(0.05, 0.3, len(season)), season, champ)
    assert tiny["t"] > tiny["crit"] and not tiny["pass"]                  # certain but too small to matter
    late = noise + np.where(season >= 2018, 8.0, -1.0)
    assert not challenge.look(late, season, champ)["pass"]                # all of it from one half


def test_candidates_are_one_rung_from_the_current_settings():
    ladders = challenge.load_ladders()
    cands = [c for ladder in ladders.values() for c in challenge.neighbours(params.DEFAULT, ladder)]
    assert len(cands) == 36 and ("margin.elo_k", 34.0) in cands and ("totals.pts.r", 1.0) in cands
    for ladder in ladders.values():
        for name, rungs in ladder.items():
            assert challenge._get(params.DEFAULT, name) in rungs and rungs == sorted(rungs)
            assert not name.startswith("card.")       # the loop never tunes the weights given to the model


def test_weights_given_to_the_model_stay_within_their_caps():
    for name, cap in challenge.WEIGHT_CAPS.items():
        assert 0.0 <= params.DEFAULT["card"][name] <= cap


def candidate(**kw):
    base = dict(model="totals", setting="totals.epa.k", to=0.04, t=3.5, crit=3.25, rel=0.0015, rmse_gain=0.01,
                season_share=0.8, eligible=True, mean=0.2)
    base["from"] = 0.03
    base["pass"] = True
    return {**base, **kw}


def test_decide_proposes_once_per_model_per_season(tmp_path):
    ev = dict(season=2026, week=10, games=4500, candidates=[
        candidate(), candidate(setting="totals.pts.k", to=0.03, t=4.0), candidate(setting="totals.ridge", eligible=False, t=9.0)])
    ev["candidates"][1]["from"] = 0.02
    first = challenge.decide(tmp_path, ev, params.DEFAULT)
    assert first["kind"] == "promotion" and first["setting"] == "totals.pts.k"       # strongest eligible one
    again = challenge.decide(tmp_path, ev, params.DEFAULT)
    assert again["setting"] == "totals.pts.k"                                        # same proposal stays open
    assert len(json.loads((tmp_path / "promotions.json").read_text())["history"]) == 1
    merged = params.override(params.DEFAULT, {"totals.pts.k": 0.03})
    ev2 = dict(ev, candidates=[candidate()])
    assert challenge.decide(tmp_path, ev2, merged) is None                           # one change a season
    book = json.loads((tmp_path / "promotions.json").read_text())
    assert book["history"][0]["status"] == "merged"
    assert challenge.decide(tmp_path, dict(ev2, season=2027), merged)["setting"] == "totals.epa.k"
    body = challenge.pr_body(first, ev)
    assert "not evidence of a betting edge" in body and "nothing changes until you merge" in body
