"""Team ratings and a per-game breakdown of the predicted margin, for the web page.

The margin model predicts home minus away points as a weighted sum of rating differences. Because
that sum has no constant term, it splits exactly into one number per team plus the game's own
circumstances:

    predicted margin = power(home) - power(away) + home field + rest + backup quarterback

`power` is in points against an average team on a neutral field and has four parts: results (the
Elo rating, from scores), offence and defence (opponent-adjusted passing and rushing EPA, each also
given separately for passing and rushing) and the starting quarterback. This module only reads the model's state and weights; it changes no prediction.
"""
from __future__ import annotations

import pandas as pd

from . import margin_model, qb_depth, qb_form, totals_model

PARTS = ("results", "offense", "defense", "qb")
SPLITS = ("pass_off", "rush_off", "pass_def", "rush_def")  # offense and defense, each split by play type


def _qb_names(games: pd.DataFrame) -> dict:
    names = {}
    for side in ("home", "away"):
        if f"{side}_qb_name" in games.columns:
            names.update(games[[f"{side}_qb_id", f"{side}_qb_name"]].dropna().itertuples(index=False, name=None))
    return names


def _records(games: pd.DataFrame, season: int) -> dict:
    done = games[(games["season"] == season) & games["home_score"].notna() & games["away_score"].notna()]
    out: dict = {}
    for home, away, hs, as_ in zip(done["home_team"], done["away_team"], done["home_score"], done["away_score"]):
        for team, diff in ((home, hs - as_), (away, as_ - hs)):
            rec = out.setdefault(team, [0, 0, 0])
            rec[0 if diff > 0 else 1 if diff < 0 else 2] += 1
    return out


def _next_games(this: pd.DataFrame) -> dict:
    """team -> its next unplayed game this season, as {opponent, home}."""
    out: dict = {}
    todo = this[this["home_score"].isna()].sort_values(["gameday", "game_id"])
    for home, away in zip(todo["home_team"], todo["away_team"]):
        out.setdefault(home, {"opponent": away, "home": True})
        out.setdefault(away, {"opponent": home, "home": False})
    return out


def _next_starters(this: pd.DataFrame, week: int | None) -> dict:
    """team -> the quarterback listed to start its unplayed game in `week`, where one is named.

    That week's listing has been checked against rosters, injuries and ESPN's depth chart (see
    `cli.expected_starters`), so this is the starter the game prediction uses. Without it a team is
    shown with whoever started its last game, which is wrong the week a new quarterback takes over.
    Later weeks are pre-filled guesses nobody has checked (they can name a player on injured
    reserve), so a team that has already played this week keeps the quarterback who started."""
    if week is None:
        return {}
    todo = this[this["home_score"].isna() & (this["week"] == week)]
    out: dict = {}
    for side in ("home", "away"):
        out.update((team, qb) for team, qb in zip(todo[f"{side}_team"], todo[f"{side}_qb_id"]) if isinstance(qb, str))
    return out


def build(games: pd.DataFrame, team_stats: pd.DataFrame, player_stats: pd.DataFrame, season: int,
          pace: dict | None = None, qb_shares: dict | None = None, week: int | None = None) -> dict:
    """`teams`: one row per team, best first. `games`: game_id -> the parts of that game's predicted margin
    (home minus away points). `pace` is the totals model's plays-per-game rating by team, if wanted.
    `week` is the week whose starters have been checked; teams still to play in it are rated with
    that game's quarterback, every other team with its last starter."""
    model, form = {}, {}
    feats = margin_model.compute_features(games, team_stats, state=model, qb_shares=qb_shares)
    feats = feats.merge(qb_form.qb_form_features(games, player_stats, state=form), on="game_id", how="left")
    feats = feats.merge(qb_depth.depth_features(games), on="game_id", how="left")
    features = margin_model.FEATURES + qb_form.FEATURES + qb_depth.MARGIN_FEATURES
    _, weights = margin_model.blend_walk_forward(feats, features=features)
    if season not in weights:
        return {"teams": [], "games": {}}
    w = weights[season]

    this = games[games["season"] == season]
    current = sorted(set(this["home_team"].replace(margin_model.TEAM_MAP)) | set(this["away_team"].replace(margin_model.TEAM_MAP)))
    this = this.replace({"home_team": margin_model.TEAM_MAP, "away_team": margin_model.TEAM_MAP})
    names, records, upcoming = _qb_names(games), _records(this, season), _next_games(this)
    starters = _next_starters(this, week)
    teams = []
    for team in current:
        if team not in model["elo"]:
            continue
        qb = starters.get(team) or model["last_qb"].get(team)
        value = model["qb_value"].get(qb, model["qb_prior"])
        qb_form_value = form["form"].get(qb, form["new_qb"])
        teams.append({
            "team": team,
            "results": w["elo_diff"] * (model["elo"][team] - 1500.0) / model["elo_per_pt"],
            "pass_off": w["pass_diff"] * model["pass_off"][team], "rush_off": w["rush_diff"] * model["rush_off"][team],
            # The model tracks what a defense allows, so the sign is turned: positive is a good defense.
            "pass_def": -w["pass_diff"] * model["pass_def"][team], "rush_def": -w["rush_diff"] * model["rush_def"][team],
            "qb": w["qb_diff"] * (value - model["pass_off"][team]) + w["qb_form_diff"] * qb_form_value,
            "qb_name": names.get(qb), "qb_form": qb_form_value, "elo": model["elo"][team],
            "record": records.get(team, [0, 0, 0]), "next": upcoming.get(team),
            "pace": (pace or {}).get(team, 0.0),
        })
    for part in SPLITS + ("results", "qb", "pace"):  # measure every part against the average team
        mean = sum(t[part] for t in teams) / max(len(teams), 1)
        for t in teams:
            t[part] -= mean
    for t in teams:
        t["offense"], t["defense"] = t["pass_off"] + t["rush_off"], t["pass_def"] + t["rush_def"]
        t["power"] = sum(t[part] for part in PARTS)
    teams.sort(key=lambda t: -t["power"])
    for rank, t in enumerate(teams, 1):
        t["rank"] = rank
    for part in ("offense", "defense", "qb", "qb_form", "pace") + SPLITS:
        for rank, t in enumerate(sorted(teams, key=lambda t: -t[part]), 1):
            t[f"{part}_rank"] = rank

    rows = feats[feats["season"] == season]
    breakdown = {}
    for r in rows.itertuples(index=False):
        breakdown[r.game_id] = {
            "team": w["elo_diff"] * r.elo_diff + w["pass_diff"] * r.pass_diff + w["rush_diff"] * r.rush_diff,
            "qb": (w["qb_diff"] * r.qb_diff + w["qb_form_diff"] * r.qb_form_diff
                   + w["qb_backup_diff"] * r.qb_backup_diff + w["qb_new_diff"] * r.qb_new_diff),
            "home": w["hfa"] * r.hfa,
            "rest": w["rest_diff"] * r.rest_diff,
        }
    return {"teams": teams, "games": breakdown, "home_field": w["hfa"] * model["hfa"]}


def load(games: pd.DataFrame, data_dir: str, season: int, week: int | None = None) -> dict:
    totals: dict = {}
    players = qb_form.load_player_stats(data_dir)
    games, shares = qb_depth.prepare(games, players)
    totals_model.build_features(games, totals_model.load_team_stats(data_dir), state=totals, qb_shares=shares)
    return build(games, margin_model.load_team_stats(data_dir), players, season,
                 pace=totals["pace"], qb_shares=shares, week=week)
