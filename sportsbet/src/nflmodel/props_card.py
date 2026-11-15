"""The weekly props card: every likely player in a week's games, with a distribution for each prop."""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from . import props as P

RECENT_TEAM_GAMES = 2   # a player is a candidate if he appeared in one of his team's last N games
BAND = (0.10, 0.90)


def upcoming_rows(offense: pd.DataFrame, games: pd.DataFrame, season: int, week: int,
                  avail: pd.DataFrame | None = None) -> pd.DataFrame:
    """Blank stat rows for the players expected in the unplayed games of a week.

    A player is expected when his latest appearance was for this team in one of its last
    ``RECENT_TEAM_GAMES`` games this season (or, before a team has played, last season). The announced
    starting quarterback is always included; with none announced, the team's last starter is assumed.

    With ``avail`` (availability.for_week), the roster decides instead: every skill player on the team's
    roster who is not out (injured reserve, practice squad, cut, or listed Out or Doubtful) and has
    played since last season is a candidate, on the team he belongs to now, so traded players move
    and players back from injury return. Without it nothing here knows about injuries.
    """
    g = games[(games["season"] == season) & (games["week"] == week) & games["result"].isna()]
    if g.empty:
        return offense.iloc[0:0]
    done = set(offense["game_id"])
    g = g[~g["game_id"].isin(done)]
    order = games[["game_id", "gameday"]].assign(gameday=games["gameday"].astype(str))
    hist = offense.merge(order, on="game_id").sort_values(["gameday", "game_id"], kind="mergesort")
    hist = hist[hist["season"] >= season - 1]
    last = hist.groupby("player_id").tail(1)
    now_team, is_out, report = {}, {}, {}
    if avail is not None and not avail.empty:
        now_team = dict(zip(avail["gsis_id"], avail["team"]))
        is_out = dict(zip(avail["gsis_id"], avail["out"].astype(bool)))
        report = dict(zip(avail["gsis_id"], avail["report_status"]))
        on_roster = set(avail["team"])
    names = offense.drop_duplicates("player_id", keep="last").set_index("player_id")
    gm = games.assign(home_team=games["home_team"].replace(P.TEAM_MAP), away_team=games["away_team"].replace(P.TEAM_MAP))
    rows = []
    for _, r in g.iterrows():
        for side, opp in (("home", "away"), ("away", "home")):
            team, other = P.TEAM_MAP.get(r[f"{side}_team"], r[f"{side}_team"]), P.TEAM_MAP.get(r[f"{opp}_team"], r[f"{opp}_team"])
            th = hist[hist["team"] == team]
            cur = th[th["season"] == season]
            recent = list(dict.fromkeys((cur if not cur.empty else th)["game_id"]))[-RECENT_TEAM_GAMES:]
            ids = list(last[(last["team"] == team) & last["game_id"].isin(recent)]["player_id"])
            if now_team and team in on_roster:
                ids = [p for p in last["player_id"] if now_team.get(p) == team and not is_out.get(p, False)]
            qb = r.get(f"{side}_qb_id")
            if not isinstance(qb, str) and recent:
                prev = gm[gm["game_id"] == recent[-1]].iloc[0]
                qb = prev["home_qb_id"] if prev["home_team"] == team else prev["away_qb_id"]
            if isinstance(qb, str) and not isinstance(r.get(f"{side}_qb_id"), str) and is_out.get(qb, False):
                qb = None   # the assumed starter is out and no replacement is announced
            if isinstance(qb, str) and qb not in ids:
                ids.append(qb)
            for pid in ids:
                known = pid in names.index
                rows.append({
                    "player_id": pid,
                    "player_display_name": names.at[pid, "player_display_name"] if known else r.get(f"{side}_qb_name"),
                    "position": names.at[pid, "position"] if known else "QB",
                    "season": season, "week": week, "game_id": r["game_id"], "team": team,
                    "opponent_team": other, "_starter_id": qb,
                    "status": report.get(pid) if isinstance(report.get(pid), str) else None,
                })
    up = pd.DataFrame(rows, columns=["player_id", "player_display_name", "position", "season", "week", "game_id",
                                     "team", "opponent_team", "_starter_id", "status"])
    for c in P._RAW:
        up[c] = 0.0
    return up


def _fill_lines(games: pd.DataFrame, preds: pd.DataFrame | None) -> pd.DataFrame:
    """Where a game has no posted spread or total, use the nflmodel prediction."""
    if preds is None:
        return games
    g = games.merge(preds[["game_id", "pred_margin", "pred_total"]], on="game_id", how="left")
    g["spread_line"] = pd.to_numeric(g["spread_line"], errors="coerce").fillna(g["pred_margin"])
    g["total_line"] = pd.to_numeric(g["total_line"], errors="coerce").fillna(g["pred_total"])
    return g.drop(columns=["pred_margin", "pred_total"])


def _summary(model: P.PropModel, pos: str, mu: float, scale: float = 1.0) -> dict:
    prop = model.prop
    if prop.kind == "count":
        pmf = model.pmf(pos, mu)
        cdf = np.cumsum(pmf)
        q = lambda p: float(np.searchsorted(cdf, p))
        out = {"median": q(0.5), "low": q(BAND[0]), "high": q(BAND[1])}
        out["ladder"] = {f"{k}+": float(pmf[k:].sum()) for k in range(1, 11) if 0.02 <= pmf[k:].sum() <= 0.98}
    else:
        qs = model.quantiles(pos, mu, scale)
        q = lambda p: float(np.interp(p, P.QUANTILES, qs))
        out = {"median": q(0.5), "low": q(BAND[0]), "high": q(BAND[1])}
        step = 25.0 if prop.key == "pass_yds" else (5.0 if prop.key in ("pass_att", "pass_cmp") else 10.0)
        lo, hi = math.ceil(q(0.1) / step) * step, math.floor(q(0.95) / step) * step
        out["ladder"] = {f"{int(x)}+": model.prob_over(pos, mu, x - 0.5, scale) for x in np.arange(lo, hi + step, step) if x > 0}
    # The half-point line closest to an even chance.
    best, gap = None, 2.0
    for line in np.arange(0.5, max(out["high"] * 1.5, 3.0) + 1.0, 1.0):
        p = model.prob_over(pos, mu, float(line), scale)
        if abs(p - 0.5) < gap:
            best, gap = (float(line), p), abs(p - 0.5)
    out["fair_line"], out["fair_over"] = best
    return out


STEADY, VOLATILE = 0.95, 1.05   # range width against a typical player, for the consistency label


def consistency_label(scale: float | None) -> str | None:
    if scale is None:
        return None
    return "steady" if scale <= STEADY else ("boom or bust" if scale >= VOLATILE else "typical")


JSON_PROBS = np.round(np.concatenate([[0.005], np.arange(0.01, 0.995, 0.01), [0.995]]), 3)


def _dist(model: P.PropModel, pos: str, mu: float, scale: float = 1.0) -> dict:
    """The whole distribution, for the web page: quantiles at JSON_PROBS for yardage props, or the
    probability of each count 0, 1, 2, ... for count props."""
    if model.prop.kind == "count":
        pmf = model.pmf(pos, mu)
        keep = int(np.searchsorted(np.cumsum(pmf), 0.9999)) + 1
        return {"q": None, "pmf": [round(float(x), 4) for x in pmf[:keep]]}
    qs = np.interp(JSON_PROBS, P.QUANTILES, model.quantiles(pos, mu, scale))
    return {"q": [round(float(x), 1) for x in qs], "pmf": None}


def week_props(offense: pd.DataFrame, games: pd.DataFrame, season: int, week: int,
               preds: pd.DataFrame | None = None, avail: pd.DataFrame | None = None,
               snaps: pd.DataFrame | None = None, rookies: pd.DataFrame | None = None) -> tuple[pd.DataFrame, dict[str, P.PropModel]]:
    """Predictions for a week. For an unplayed week the players are guessed from recent games; for a
    finished week the players who appeared are scored, with the model fitted only on earlier dates."""
    games = _fill_lines(games, preds)
    off = offense[~((offense["season"] == season) & (offense["week"] >= week)) & (offense["season"] <= season)].copy()
    off["played"] = True
    target = offense[(offense["season"] == season) & (offense["week"] == week)].copy()
    target["played"] = False
    up = upcoming_rows(off, games, season, week, avail)
    if not up.empty:
        qb_over = dict(zip(zip(up["game_id"], up["team"]), up["_starter_id"]))
        up = up.drop(columns="_starter_id")
        up["played"] = False
        games = games.copy()
        for (gid, team), qb in qb_over.items():
            if isinstance(qb, str):
                row = games["game_id"] == gid
                col = "home_qb_id" if games.loc[row, "home_team"].replace(P.TEAM_MAP).iloc[0] == team else "away_qb_id"
                games.loc[row, col] = qb
    both = pd.concat([off, target, up], ignore_index=True)
    panel = P.build_panel(both, games, snaps, rookies)
    models = P.fit_all(panel)
    cur = panel[~panel["played"].astype(bool)]
    out = []
    for prop in P.PROPS:
        d = cur[P.eligible(cur, prop)]
        mu = models[prop.key].expected(d)
        scales = models[prop.key].spread_scale(d)
        has_scale = prop.key in P.SPREAD_MODS
        for (_, r), m, sc in zip(d.iterrows(), mu, scales):
            if not np.isfinite(m):
                continue
            sc = float(sc)
            s = _summary(models[prop.key], r["position"], float(m), sc)
            row = {"game_id": r["game_id"], "gameday": r["gameday"], "team": r["team"], "opponent": r["opponent_team"],
                   "player_id": r["player_id"], "player": r["player_display_name"], "position": r["position"],
                   "status": r.get("status") if isinstance(r.get("status"), str) else None,
                   "prop": prop.key, "expected": float(m), **{k: v for k, v in s.items() if k != "ladder"},
                   "chance": float(1 - models[prop.key].pmf(r["position"], float(m))[0]) if prop.key == "any_td" else None,
                   "ladder": s["ladder"], **_dist(models[prop.key], r["position"], float(m), sc),
                   "range_width": round(sc, 3) if has_scale else None,
                   "consistency": consistency_label(sc if has_scale else None),
                   "actual": float(r[prop.target]) if r["game_id"] in set(target["game_id"]) and r["player_id"] in set(target["player_id"]) else None}
            out.append(row)
    return pd.DataFrame(out), models
