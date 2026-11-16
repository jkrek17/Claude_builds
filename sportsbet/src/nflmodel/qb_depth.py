"""Where a starting quarterback stands on his team, and whether he finished the game.

Two things the ratings cannot see from the starter's name alone:

* ``side_table``: for every game and side, facts known before kickoff about the starter: whether he
  is an in-season backup, whether this is his first start of a stint, how many starts he has made,
  and how many different quarterbacks his team has already started this season.
* ``starter_share``: for played games, the share of his team's quarterback dropbacks the starter
  took. A low share means he left early (injury or benching), so the result says little about the
  team with him on the field.

Everything is computed in date order; a game's own result never feeds its pre-kickoff facts.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np
import pandas as pd

MARGIN_FEATURES = ["qb_new_diff"]                # added to the margin blend (home minus away)
TOTAL_FEATURES = ["qb_form_sum", "qb_greenbk_sum"]  # added to the totals layer (home plus away)
GREEN_STARTS = 8    # a backup counts as inexperienced, on a sliding scale, below this many career starts
EARLY_CUT = 0.5     # a starter with under this share of his team's dropbacks left the game early
TEAM_MAP = {"OAK": "LV", "SD": "LAC", "STL": "LA", "JAC": "JAX", "SL": "LA", "LAR": "LA", "WSH": "WAS"}


def starter_share(games: pd.DataFrame, player_stats: pd.DataFrame) -> dict:
    """{(game_id, team): starter's share of the team's quarterback dropbacks}, played games only."""
    ps = player_stats[player_stats["position"] == "QB"].copy()
    for c in ("attempts", "sacks_suffered"):
        ps[c] = pd.to_numeric(ps[c], errors="coerce").fillna(0.0)
    ps["db"] = ps["attempts"] + ps["sacks_suffered"]
    ps["team"] = ps["team"].replace(TEAM_MAP)
    total = ps.groupby(["game_id", "team"])["db"].sum().to_dict()
    own = ps.groupby(["game_id", "player_id"])["db"].sum().to_dict()
    out = {}
    for side in ("home", "away"):
        teams = games[f"{side}_team"].replace(TEAM_MAP)
        for gid, team, qb in zip(games["game_id"], teams, games[f"{side}_qb_id"]):
            t = total.get((gid, team), 0.0)
            if t > 0 and isinstance(qb, str):
                out[(gid, team)] = own.get((gid, qb), 0.0) / t
    return out


def played_starters(games: pd.DataFrame, player_stats: pd.DataFrame) -> pd.DataFrame:
    """`games` with the starter corrected where the listed one never took a dropback.

    nflverse sometimes lists a quarterback who did not play (about 50 team-games in 2024 to 2026).
    When the listed starter has no dropbacks in a played game and another quarterback of that team
    does, the one with the most dropbacks is taken as the starter. A starter who left early keeps
    the start."""
    ps = player_stats[player_stats["position"] == "QB"].copy()
    for c in ("attempts", "sacks_suffered"):
        ps[c] = pd.to_numeric(ps[c], errors="coerce").fillna(0.0)
    ps["db"] = ps["attempts"] + ps["sacks_suffered"]
    ps["team"] = ps["team"].replace(TEAM_MAP)
    ps = ps[ps["db"] > 0]
    top = (ps.sort_values("db", ascending=False).drop_duplicates(["game_id", "team"])
           .set_index(["game_id", "team"])["player_id"].to_dict())
    took = set(zip(ps["game_id"], ps["player_id"]))
    names = dict(zip(ps["player_id"], ps["player_display_name"])) if "player_display_name" in ps.columns else {}
    out = games.copy()
    for side in ("home", "away"):
        teams = out[f"{side}_team"].replace(TEAM_MAP)
        fixed = [top[(gid, team)] if (gid, team) in top and (gid, qb) not in took else qb
                 for gid, team, qb in zip(out["game_id"], teams, out[f"{side}_qb_id"])]
        changed = pd.Series(fixed, index=out.index) != out[f"{side}_qb_id"]
        changed &= pd.Series(fixed, index=out.index).notna()
        out[f"{side}_qb_id"] = fixed
        if f"{side}_qb_name" in out.columns:
            out.loc[changed, f"{side}_qb_name"] = out.loc[changed, f"{side}_qb_id"].map(names)
    return out


def dropback_shares(games: pd.DataFrame, player_stats: pd.DataFrame, cut: float = EARLY_CUT) -> dict:
    """{(game_id, team): [(quarterback, share), ...]} for games the starter took under `cut` of the dropbacks.

    The model updates then credit the game's passing result to the quarterbacks who played it."""
    ps = player_stats[player_stats["position"] == "QB"].copy()
    for c in ("attempts", "sacks_suffered"):
        ps[c] = pd.to_numeric(ps[c], errors="coerce").fillna(0.0)
    ps["db"] = ps["attempts"] + ps["sacks_suffered"]
    ps["team"] = ps["team"].replace(TEAM_MAP)
    by: dict = defaultdict(list)
    for gid, team, pid, db in zip(ps["game_id"], ps["team"], ps["player_id"], ps["db"]):
        if db > 0:
            by[(gid, team)].append((pid, db))
    out = {}
    for key, share in starter_share(games, player_stats).items():
        if share < cut:
            total = sum(db for _, db in by[key])
            out[key] = [(pid, db / total) for pid, db in by[key]]
    return out


def side_table(games: pd.DataFrame) -> pd.DataFrame:
    """One row per game with, for each side, pre-kickoff facts about the starting quarterback.

    Columns (prefix home_ / away_): backup (not the quarterback with most of the team's starts this
    season, once the team has played two games), new (a backup making the first start of a stint),
    nth (how many different quarterbacks the team has started this season, counting him), starts
    (his career starts so far). A missing starter is taken to be the team's last one."""
    g = games.copy()
    g["gameday"] = g["gameday"].astype(str)
    g = g.sort_values(["gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    played = (pd.to_numeric(g["home_score"], errors="coerce").notna()
              & pd.to_numeric(g["away_score"], errors="coerce").notna()).to_numpy()
    teams = {s: g[f"{s}_team"].replace(TEAM_MAP).to_numpy() for s in ("home", "away")}
    qbs = {s: g[f"{s}_qb_id"].to_numpy(dtype=object) for s in ("home", "away")}
    days, seasons = g["gameday"].to_numpy(), g["season"].to_numpy()
    n = len(g)
    out = {f"{s}_{c}": np.zeros(n) for s in ("home", "away") for c in ("backup", "new", "nth", "starts")}
    season_starts: dict = {}    # team -> (season, {qb: starts})
    last_qb: dict = {}
    career = defaultdict(int)
    i = 0
    while i < n:
        j = i
        while j < n and days[j] == days[i]:
            j += 1
        todays = []
        for k in range(i, j):
            for s in ("home", "away"):
                team, qb = teams[s][k], qbs[s][k]
                if not isinstance(qb, str):
                    qb = last_qb.get(team)
                todays.append((k, team, qb))
                if qb is None:
                    continue
                ss = season_starts.get(team)
                ss = ss[1] if ss and ss[0] == seasons[k] else {}
                backup = float(sum(ss.values()) >= 2 and max(ss.items(), key=lambda kv: kv[1])[0] != qb)
                out[f"{s}_backup"][k] = backup
                out[f"{s}_new"][k] = backup * float(last_qb.get(team) != qb)
                out[f"{s}_nth"][k] = len(ss) + (qb not in ss)
                out[f"{s}_starts"][k] = career[qb]
        for k, team, qb in todays:
            if not played[k] or qb is None:
                continue
            ss = season_starts.get(team)
            if not ss or ss[0] != seasons[k]:
                ss = (seasons[k], {})
                season_starts[team] = ss
            ss[1][qb] = ss[1].get(qb, 0) + 1
            last_qb[team] = qb
            career[qb] += 1
        i = j
    f = pd.DataFrame({"game_id": g["game_id"].to_numpy()})
    for k, v in out.items():
        f[k] = v
    return f


def expected_starters(games: pd.DataFrame, avail_week: pd.DataFrame, player_stats: pd.DataFrame,
                      season: int, week: int) -> tuple[pd.DataFrame, list[dict]]:
    """`games` with the listed starter replaced, for unplayed games of one week, when he cannot play.

    `avail_week` is `availability.for_week(...)` for that week. A listed starter (or, when none is
    listed, the team's last starter) who is off the active roster or listed Out or Doubtful is
    replaced by the available quarterback on the team who most recently took dropbacks for it this
    season; failing that, the one who has been on its roster longest this season. Returns the new
    games and a list of the changes made ({game_id, team, was, now})."""
    qb = avail_week[avail_week["position"] == "QB"]
    out_ids = set(qb.loc[qb["out"], "gsis_id"])
    ok = qb[~qb["out"]]
    on_team = ok.groupby("team")["gsis_id"].apply(list).to_dict()
    since = dict(zip(ok["gsis_id"], ok.get("first_week", pd.Series(0, index=ok.index))))
    ps = player_stats[player_stats["position"] == "QB"].copy()
    ps["db"] = (pd.to_numeric(ps["attempts"], errors="coerce").fillna(0)
                + pd.to_numeric(ps["sacks_suffered"], errors="coerce").fillna(0))
    ps = ps[(ps["db"] > 0) & ps["game_id"].astype(str).str.startswith(f"{season}_")]
    ps["team"] = ps["team"].replace(TEAM_MAP)
    played_wk = ps["game_id"].str.slice(5, 7).astype(int)
    last_played = ps.assign(wk=played_wk)[played_wk < week].groupby(["team", "player_id"])["wk"].max().to_dict()
    done = games[games["home_score"].notna() & (games["season"] == season) & (games["week"] < week)]
    done = done.sort_values(["gameday", "game_id"])
    last_starter = {}
    for s in ("home", "away"):
        for team, q in zip(done[f"{s}_team"].replace(TEAM_MAP), done[f"{s}_qb_id"]):
            if isinstance(q, str):
                last_starter[team] = q
    g = games.copy()
    rows = g.index[(g["season"] == season) & (g["week"] == week) & g["home_score"].isna()]
    changes = []
    for i in rows:
        for s in ("home", "away"):
            team = TEAM_MAP.get(g.at[i, f"{s}_team"], g.at[i, f"{s}_team"])
            listed = g.at[i, f"{s}_qb_id"]
            listed = listed if isinstance(listed, str) else last_starter.get(team)
            if listed is None or listed not in out_ids:
                continue
            pool = on_team.get(team, [])
            if not pool:
                continue
            best = max(pool, key=lambda q: (last_played.get((team, q), 0), -since.get(q, 99)))
            changes.append({"game_id": g.at[i, "game_id"], "team": team, "was": listed, "now": best})
            g.at[i, f"{s}_qb_id"] = best
            if f"{s}_qb_name" in g.columns:
                name = ok.loc[ok["gsis_id"] == best, "full_name"]
                g.at[i, f"{s}_qb_name"] = name.iloc[0] if len(name) else None
    return g, changes


def depth_features(games: pd.DataFrame) -> pd.DataFrame:
    """One row per game_id with the pre-kickoff features the models add (see research/backup_qb).

    * ``qb_new_diff``: a backup making the first start of a stint, home minus away.
    * ``qb_greenbk_sum``: how inexperienced each backup starter is (1 with no career starts, 0 from
      `GREEN_STARTS` on), summed over the two teams."""
    s = side_table(games)
    green = {side: np.clip((GREEN_STARTS - s[f"{side}_starts"]) / GREEN_STARTS, 0, 1) * s[f"{side}_backup"]
             for side in ("home", "away")}
    return pd.DataFrame({"game_id": s["game_id"], "qb_new_diff": s["home_new"] - s["away_new"],
                         "qb_greenbk_sum": green["home"] + green["away"]})


def prepare(games: pd.DataFrame, player_stats: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """(games with played starters corrected, dropback shares for games the starter left early)."""
    fixed = played_starters(games, player_stats)
    return fixed, dropback_shares(fixed, player_stats)


def check_week(games: pd.DataFrame, data_dir: str, player_stats: pd.DataFrame, season: int,
               week: int) -> tuple[pd.DataFrame, list[dict]]:
    """`expected_starters` for one week from the cached roster and injury files; no files, no change."""
    from . import availability
    avail = availability.load(data_dir, [season])
    if avail.empty:
        return games, []
    this = availability.for_week(avail, season, week)
    active = avail[~avail["roster_status"].isin(availability.OUT_ROSTER)]
    this["first_week"] = this["gsis_id"].map(active.groupby("gsis_id")["week"].min()).fillna(99)
    new, changes = expected_starters(games, this, player_stats, season, week)
    names = dict(zip(avail["gsis_id"], avail["full_name"]))
    for c in changes:
        c["was_name"], c["now_name"] = names.get(c["was"], c["was"]), names.get(c["now"], c["now"])
    return new, changes
