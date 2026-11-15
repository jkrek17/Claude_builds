"""Candidate role / volume features added to the baseline panel. Every column is pre-game.

`build()` returns the baseline panel (frozen eligibility) plus all candidate columns; cached in
data/panel.pkl (delete to rebuild). Columns marked [LIVE] use "who has a row in this game", which
live needs the inactives list; everything else uses only earlier dates + announced starter.
"""
from __future__ import annotations

import os
import warnings
import numpy as np
import pandas as pd

import lib, hist
from nflmodel import props as P

warnings.simplefilter("ignore", pd.errors.PerformanceWarning)
EXTRA = ["receiving_air_yards", "receiving_yards_after_catch", "receiving_first_downs", "rushing_first_downs"]


def _sorted_apply(d, x, decay, mult=None):
    o = hist.order(d)
    s = hist.decayed(d["player_id"].to_numpy()[o], x[o], decay, mult[o] if mult is not None else None)
    inv = np.empty(len(o), dtype=int); inv[o] = np.arange(len(o))
    return s[inv]


def dec_avg(d, cols, decay, shrink=0.0, valid=None):
    """Pre-game decayed average of columns over the player's earlier games (only rows with valid)."""
    v = np.ones(len(d)) if valid is None else valid.astype(float)
    x = np.column_stack([np.nan_to_num(d[cols].to_numpy(float)) * v[:, None], v])
    s = _sorted_apply(d, x, decay)
    return s[:, :-1] / (s[:, -1:] + shrink + 1e-12), s[:, -1]


def prev_value(d, col):
    """Value of `col` in the player's previous game (NaN if none)."""
    o = hist.order(d)
    pid = d["player_id"].to_numpy()[o]
    v = d[col].to_numpy(float)[o]
    out = np.r_[np.nan, v[:-1]]
    out[np.r_[True, pid[1:] != pid[:-1]]] = np.nan
    inv = np.empty(len(o), dtype=int); inv[o] = np.arange(len(o))
    return out[inv]


# ------------------------------------------------------------------ snaps (idea 3)
def add_snaps(d):
    pl = pd.read_csv(lib.DATA / "players.csv", usecols=["gsis_id", "pfr_id"], low_memory=False).dropna()
    pl = pl.drop_duplicates("pfr_id")
    parts = [pd.read_csv(lib.DATA / f"snap_counts_{s}.csv") for s in range(2013, int(d["season"].max()) + 1)
             if os.path.exists(lib.DATA / f"snap_counts_{s}.csv")]
    sc = pd.concat(parts, ignore_index=True)
    sc = sc.merge(pl, left_on="pfr_player_id", right_on="pfr_id", how="inner")
    sc = sc.groupby(["game_id", "gsis_id"], as_index=False)[["offense_snaps", "offense_pct"]].max()
    d = d.merge(sc.rename(columns={"gsis_id": "player_id", "offense_pct": "snap_pct", "offense_snaps": "snaps"}),
                on=["game_id", "player_id"], how="left")
    has = d["snap_pct"].notna().to_numpy()
    d["has_snap"] = has
    for nm, dec in (("fast", P.FAST), ("slow", P.SLOW)):
        a, n = dec_avg(d, ["snap_pct"], dec, shrink=0.0, valid=has)
        d[f"snap_{nm}"] = a[:, 0]
        d[f"snapn_{nm}"] = n
        a, _ = dec_avg(d, ["snap_pct"], dec, shrink=P.SHRINK, valid=has)
        d[f"snapsh_{nm}"] = a[:, 0]                       # shrunk to zero like the other rates
    last = prev_value(d, "snap_pct")
    d["snap_last"] = np.where(np.isnan(last), d["snap_fast"], last)
    d["snap_trend"] = d["snap_last"] - d["snap_slow"]
    # route proxy: team pass attempts x snap share; targets per route proxy, carries per snap
    tatt = d.groupby(["game_id", "team"])["attempts"].transform("sum").to_numpy()
    tcar = d.groupby(["game_id", "team"])["carries"].transform("sum").to_numpy()
    d["_routes"] = np.nan_to_num(d["snap_pct"].to_numpy()) * tatt
    d["_rsnaps"] = np.nan_to_num(d["snap_pct"].to_numpy()) * tcar
    x = np.column_stack([d["targets"], d["_routes"], d["carries"], d["_rsnaps"]]) * has[:, None]
    s = _sorted_apply(d, x.astype(float), P.SLOW)
    d["tprr_slow"] = s[:, 0] / (s[:, 1] + 30.0)          # 30 routes of "no targets" prior
    d["cps_slow"] = s[:, 2] / (s[:, 3] + 25.0)
    d["exp_tgt_snap"] = d["tprr_slow"] * d["snap_fast"] * d["tm_attempts"]
    d["exp_tgt_snaplast"] = d["tprr_slow"] * d["snap_last"] * d["tm_attempts"]
    d["exp_car_snap"] = d["cps_slow"] * d["snap_fast"] * d["tm_carries"]
    d["exp_car_snaplast"] = d["cps_slow"] * d["snap_last"] * d["tm_carries"]
    return d.drop(columns=["_routes", "_rsnaps"])


# ------------------------------------------------------------------ vacated volume (idea 2)
def add_competition(d):
    """Teammates' share of volume present in this game versus in the player's own past games.

    comp_{tgt,car}_now  [LIVE]: sum of pre-game slow shares of the OTHER teammates with a row in this game.
    comp_*_prev: same sum over teammates who played the team's previous game this season (post-game
        shares as of that game) -- earlier weeks only.
    comp_*_h{fast,slow}: decayed average, over the player's own earlier games, of comp_now in those games.
    """
    key = ["game_id", "team"]
    # post-game shares (state after this game), for the "previous game lineup" version
    tt = d.groupby(key)["targets"].transform("sum").to_numpy(float)
    tc = d.groupby(key)["carries"].transform("sum").to_numpy(float)
    x = np.column_stack([d["targets"].to_numpy(float), tt, d["carries"].to_numpy(float), tc])
    pre = _sorted_apply(d, x, P.SLOW)
    post = pre * P.SLOW + x
    d["_tpost"] = post[:, 0] / (post[:, 1] + P.SHRINK * 34.0)
    d["_cpost"] = post[:, 2] / (post[:, 3] + P.SHRINK * 27.0)
    # team game sequence within season
    tg = d[key + ["season", "gameday"]].drop_duplicates(key).sort_values(["team", "gameday"]).reset_index(drop=True)
    same = (tg["team"] == tg["team"].shift()) & (tg["season"] == tg["season"].shift())
    tg["prev_game"] = np.where(same, tg["game_id"].shift(), None)
    tg["tgame_no"] = tg.groupby(["team", "season"]).cumcount()
    d = d.merge(tg[key + ["prev_game", "tgame_no"]], on=key, how="left")
    for kind, pre_c, post_c in (("tgt", "tgt_share_slow", "_tpost"), ("car", "car_share_slow", "_cpost")):
        tot = d.groupby(key)[pre_c].transform("sum")
        d[f"comp_{kind}_now"] = tot - d[pre_c]
        ptot = d.groupby(key)[post_c].sum().rename("_ptot").reset_index().rename(columns={"game_id": "prev_game"})
        d = d.merge(ptot, on=["prev_game", "team"], how="left")
        own = d[["game_id", "team", "player_id", post_c]].rename(columns={"game_id": "prev_game", post_c: "_pown"})
        d = d.merge(own, on=["prev_game", "team", "player_id"], how="left")
        d[f"comp_{kind}_prev"] = d["_ptot"] - d["_pown"].fillna(0.0)
        d = d.drop(columns=["_ptot", "_pown"])
        for nm, dec in (("fast", P.FAST), ("slow", P.SLOW)):
            a, n = dec_avg(d, [f"comp_{kind}_now"], dec)
            h = np.where(n > 0, a[:, 0], d[f"comp_{kind}_now"])
            d[f"comp_{kind}_h{nm}"] = h
        d[f"comp_{kind}_prev"] = d[f"comp_{kind}_prev"].fillna(d[f"comp_{kind}_hfast"])
        s = d[pre_c].to_numpy()
        for ver in ("now", "prev"):
            c = d[f"comp_{kind}_{ver}"].to_numpy()
            for nm in ("fast", "slow"):
                h = d[f"comp_{kind}_h{nm}"].to_numpy()
                d[f"vac_{kind}_{ver}_{nm}"] = h - c                       # >0: less competition than usual
                r = np.clip((s + h + 0.05) / (s + c + 0.05), 0.5, 2.0)
                d[f"ratio_{kind}_{ver}_{nm}"] = r
            base = "exp_targets" if kind == "tgt" else "exp_carries"
            d[f"{base}_{ver}"] = d[base] * d[f"ratio_{kind}_{ver}_slow"]
            fs = d["tgt_share_fast" if kind == "tgt" else "car_share_fast"]
            d[f"{'tgt' if kind == 'tgt' else 'car'}_share_fast_{ver}"] = fs * d[f"ratio_{kind}_{ver}_fast"]
    # QB weapons: receiving form of the players in this game vs the team's recent games [LIVE]
    d["_w"] = np.where(d["position"] != "QB", d["receiving_yards_slow"], 0.0)
    w = d.groupby(key, as_index=False).agg(wpn_now=("_w", "sum"), gameday=("gameday", "first"))
    w = w.sort_values(["team", "gameday"], kind="mergesort").reset_index(drop=True)
    s = P._decayed(w["team"].to_numpy(), np.column_stack([w["wpn_now"], np.ones(len(w))]), 0.75)
    w["wpn_hist"] = np.where(s[:, 1] > 0, s[:, 0] / (s[:, 1] + 1e-12), w["wpn_now"])
    d = d.merge(w[key + ["wpn_now", "wpn_hist"]], on=key, how="left")
    d["wpn_delta"] = d["wpn_now"] - d["wpn_hist"]
    return d.drop(columns=["_tpost", "_cpost", "_w"])


# ------------------------------------------------------------------ layoff / own absence
def add_layoff(d):
    o = hist.order(d)
    pid = d["player_id"].to_numpy()[o]; tm = d["team"].to_numpy()[o]; sea = d["season"].to_numpy()[o]
    no = d["tgame_no"].to_numpy()[o]
    day = pd.to_datetime(d["gameday"]).to_numpy()[o]
    same = np.r_[False, pid[1:] == pid[:-1]]
    st = same & np.r_[False, (tm[1:] == tm[:-1]) & (sea[1:] == sea[:-1])]
    missed = np.where(st, np.r_[0, no[1:] - no[:-1] - 1], 0).clip(0, 8)
    newteam = (same & np.r_[False, tm[1:] != tm[:-1]]).astype(float)
    newseason = (same & np.r_[False, sea[1:] != sea[:-1]]).astype(float)
    late = np.where(~st, no, 0).clip(0, 8)      # team games already played this season before his first one
    days = np.where(same, np.r_[0, (day[1:] - day[:-1]) / np.timedelta64(1, "D")], 0)
    inv = np.empty(len(o), dtype=int); inv[o] = np.arange(len(o))
    d["missed_games"] = missed[inv].astype(float)
    d["missed_any"] = (missed[inv] > 0).astype(float)
    d["new_team"] = newteam[inv]
    d["first_of_season"] = newseason[inv]
    d["late_start"] = late[inv].astype(float)
    d["week1"] = (d["tgame_no"] == 0).astype(float)
    return d


# ------------------------------------------------------------------ quarterback (idea 4)
def add_qb(d):
    key = ["game_id", "team"]
    qcols = ["passing_yards_slow", "passing_yards_fast", "attempts_slow", "attempts_fast", "completions_slow",
             "passing_tds_slow", "qbnew_slow", "qbnew_fast"]
    q = d.loc[d["starter"] == 1, key + ["player_id"] + qcols].drop_duplicates(key)
    q = q.rename(columns={c: f"qb_{c}" for c in qcols} | {"player_id": "qb_row_id"})
    d = d.merge(q, on=key, how="left")
    d["qb_missing"] = d["qb_row_id"].isna().astype(float)
    for c in qcols:
        fill = 1.0 if "new" in c else 0.0
        d[f"qb_{c}"] = d[f"qb_{c}"].fillna(fill)
    # an unbiased-ish single number: the starter's shrunk yards plus a newcomer default of 200 yds / 31 att
    d["qb_pyds"] = d["qb_passing_yards_slow"] + 200.0 * d["qb_qbnew_slow"]
    d["qb_patt"] = d["qb_attempts_slow"] + 31.0 * d["qb_qbnew_slow"]
    d["qb_pcmp"] = d["qb_completions_slow"] + 19.0 * d["qb_qbnew_slow"]
    d["qb_ypa"] = d["qb_pyds"] / d["qb_patt"]
    d["qb_ptd"] = d["qb_passing_tds_slow"] + 1.2 * d["qb_qbnew_slow"]
    for c in ("qb_pyds", "qb_patt", "qb_ypa", "qb_pcmp"):
        for nm, dec in (("fast", P.FAST), ("slow", P.SLOW)):
            a, n = dec_avg(d, [c], dec)
            d[f"{c}_h{nm}"] = np.where(n > 0, a[:, 0], d[c])
            d[f"{c}_d{nm}"] = d[c] - d[f"{c}_h{nm}"]         # today's starter vs the QBs he has played with
    # starter changed from the team's previous game (any season)
    tg = d[key + ["gameday", "starter_id"]].drop_duplicates(key).sort_values(["team", "gameday"])
    tg["qb_changed"] = ((tg["starter_id"] != tg.groupby("team")["starter_id"].shift())
                        & tg.groupby("team")["starter_id"].shift().notna()).astype(float)
    d = d.merge(tg[key + ["qb_changed"]], on=key, how="left")
    # share of the player's (fast-decayed) history that was with today's starter
    o = hist.order(d)
    pid = d["player_id"].to_numpy()[o]; sid = d["starter_id"].astype(str).to_numpy()[o]
    fam = np.zeros(len(o)); mem = {}; prev = None
    for i in range(len(o)):
        if pid[i] != prev:
            mem = {}; prev = pid[i]
        tot = sum(mem.values())
        fam[i] = mem.get(sid[i], 0.0) / tot if tot > 0 else 1.0
        for k in mem:
            mem[k] *= P.FAST
        mem[sid[i]] = mem.get(sid[i], 0.0) + 1.0
    inv = np.empty(len(o), dtype=int); inv[o] = np.arange(len(o))
    d["qb_familiar"] = fam[inv]
    d["qb_unfam"] = 1.0 - d["qb_familiar"]
    for c in ("qb_pyds", "qb_ypa", "qb_patt"):
        d[f"{c}_dx"] = d[f"{c}_dfast"] * d["qb_unfam"]
    return d


# ------------------------------------------------------------------ draft prior (idea 5)
def add_draft(d):
    pl = pd.read_csv(lib.DATA / "players.csv", usecols=["gsis_id", "draft_pick", "rookie_season", "birth_date"],
                     low_memory=False).drop_duplicates("gsis_id")
    d = d.merge(pl.rename(columns={"gsis_id": "player_id"}), on="player_id", how="left")
    pick = d["draft_pick"].fillna(262.0).clip(1, 262)
    d["draft_val"] = 1.0 - np.log(pick) / np.log(262.0)       # 1 = first pick, 0 = undrafted
    d["undrafted"] = d["draft_pick"].isna().astype(float)
    d["exp_years"] = (d["season"] - d["rookie_season"].fillna(d["season"])).clip(0, 12)
    d["rookie"] = (d["exp_years"] == 0).astype(float)
    d["yr2"] = (d["exp_years"] == 1).astype(float)
    age = (pd.to_datetime(d["gameday"]) - pd.to_datetime(d["birth_date"], errors="coerce")).dt.days / 365.25
    d["age"] = age.fillna(age.median()).clip(20, 40)
    d["age_old"] = (d["age"] - 29).clip(lower=0)
    for nm in ("fast", "slow"):
        d[f"dv_new_{nm}"] = d["draft_val"] * d[f"new_{nm}"]
    d["dv_rookie"] = d["draft_val"] * d["rookie"]
    d["dv_yr2"] = d["draft_val"] * d["yr2"]
    d["dv_young"] = d["draft_val"] * (d["exp_years"] <= 2)
    return d.drop(columns=["birth_date"])


# ------------------------------------------------------------------ air yards (idea 6)
def add_air(d):
    d["air"] = d["receiving_air_yards"].fillna(0.0)
    d["yac"] = d["receiving_yards_after_catch"].fillna(0.0)
    d["rec_fd"] = d["receiving_first_downs"].fillna(0.0)
    d["rush_fd"] = d["rushing_first_downs"].fillna(0.0)
    for nm, dec in (("fast", P.FAST), ("slow", P.SLOW)):
        a, n = dec_avg(d, ["air", "yac", "rec_fd", "rush_fd"], dec, shrink=P.SHRINK)
        for j, c in enumerate(["air", "yac", "rec_fd", "rush_fd"]):
            d[f"{c}_{nm}"] = a[:, j]
    x = d[["air", "targets", "receptions", "receiving_yards"]].to_numpy(float)
    s = _sorted_apply(d, x, P.SLOW)
    d["adot_slow"] = (s[:, 0] + 8.0 * 20) / (s[:, 1] + 20)                # shrunk to 8 yards over 20 targets
    d["catch_slow"] = (s[:, 2] + 0.62 * 20) / (s[:, 1] + 20)
    d["ypt_slow"] = (s[:, 3] + 7.5 * 20) / (s[:, 1] + 20)
    tair = d.groupby(["game_id", "team"])["air"].transform("sum").to_numpy(float)
    s2 = _sorted_apply(d, np.column_stack([d["air"].to_numpy(float), tair]), P.SLOW)
    d["air_share_slow"] = s2[:, 0] / (s2[:, 1] + P.SHRINK * 260.0)
    d["wopr_slow"] = 1.5 * d["tgt_share_slow"] + 0.7 * d["air_share_slow"]
    d["exp_air"] = d["exp_targets"] * d["adot_slow"]
    d["exp_ryds_ypt"] = d["exp_targets"] * d["ypt_slow"]
    return d


def build(cache=True) -> pd.DataFrame:
    f = lib.DATA / "panel.pkl"
    if cache and f.exists():
        return pd.read_pickle(f)
    d = lib.base_panel(EXTRA)
    n = len(d)
    d = add_snaps(d); d = add_competition(d); d = add_layoff(d); d = add_qb(d); d = add_draft(d); d = add_air(d)
    assert len(d) == n
    d = d.sort_values(["gameday", "game_id", "player_id"], kind="mergesort").reset_index(drop=True)
    d.to_pickle(f)
    return d


if __name__ == "__main__":
    d = build(cache=False)
    print(d.shape)
    s = d[d.season >= 2014]
    print(s[["has_snap", "qb_missing", "undrafted", "qb_changed", "missed_any"]].mean())
    cols = ["snap_fast", "snap_last", "tprr_slow", "comp_tgt_now", "comp_tgt_prev", "comp_tgt_hslow", "vac_tgt_now_fast",
            "ratio_tgt_now_slow", "qb_pyds", "qb_ypa", "qb_pyds_dfast", "qb_familiar", "draft_val", "adot_slow", "air_slow",
            "missed_games", "wpn_delta"]
    print(s.loc[s["elig_rec_yds"], cols].describe().T.round(3))
    base = lib.evaluate(d)
    print(lib.summary(base).round(3))
