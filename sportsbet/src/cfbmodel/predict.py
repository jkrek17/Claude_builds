"""Points, margin and total for every game, walking a season forward a week at a time.

Pipeline:

* ``team_games_all`` turns each season's play-by-play into ``efficiency.team_games`` rows plus what
  the ratings and the predictions need: FBS flags, the neutral flag, which side was the home team,
  each team's final score and the game's line (``spread``, ``over_under``) when the source has one.
  Cached at ``data_dir/team_games.parquet``.
* ``walk`` rebuilds ratings with ``ratings.as_of`` before each week (cutoff = that week's first
  kickoff) and gives one row per (game, team) with the team's and the opponent's pregame ratings.
* ``fit_points`` maps those ratings to points by least squares, on earlier seasons only. Two forms:
  ``"direct"`` regresses points on the ratings; ``"drives"`` predicts drives from both teams' pace
  ratings and points per drive from the efficiency ratings, and multiplies. Both carry a
  ``season >= 2023`` term for the clock rule that cut drives from 2023 on. ``DEFAULT_KIND`` is the
  one that did better on 2019 (fit 2015-2018), see below.
* ``season`` predicts every game of a season.

Final scores: the slim play-by-play has no score columns, so ``final_scores`` rebuilds them from
``pos_score_diff_start``: the home margin before each snap, whose rises are home points and falls
away points. The source sometimes mislabels the offense for a row or two, which flips the sign of
the margin and back; those short excursions (at most ``GLITCH_ROWS`` rows that leave and return to
the same margin) are dropped first. The final margin is exact; totals are close but not perfect
(2019: mean actual total minus closing total -1.1 against about +1 in published data, 11 of 880
games more than 40 points off the line). Task 3's CFBD scores should replace this when built.

Accuracy, FBS vs FBS regular season, default params (regress 0.5, prior_games 4, decay 0.97), MAE
in points against the rebuilt final score, closing line = pbp ``spread`` on the same games:

    season (fit on)        form     margin  line   total  line
    2019 (2015-2018)       direct   13.70  12.16   13.36  12.86
    2019 (2015-2018)       drives   13.69  12.16   13.48  12.86
    2022 (2015-19, 2021)   direct   13.11  11.93   15.98  15.23
    2022 (2015-19, 2021)   drives   13.15  11.93   16.13  15.23

The two forms tie on margin; ``direct`` is simpler and better on totals, so it is the default.
The carryover regress matters most in weeks 0-4: 2019 margin MAE 13.70 at 0.5, 13.47 at 0.2,
13.41 at 0 (the previous season's ratings are already shrunk by its own ridge); Task 6's prior
replaces it.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from . import efficiency, pbp, ratings
from .efficiency import METRICS

FIRST_SEASON = 2014  # the first season with play-by-play; it gets an all-zero prior
CLOCK_RULE_SEASON = 2023  # the clock keeps running after first downs from 2023 on
GLITCH_ROWS = 2
DEFAULT_KIND = "direct"
DEFAULT_REGRESS = 0.5

CACHE_NAME = "team_games.parquet"
EXTRA = ["is_home", "neutral", "team_fbs", "opp_fbs", "team_score", "opp_score", "spread", "over_under"]
PACE = "sec_per_play"


# ---------------------------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------------------------

def _drop_glitches(hm: np.ndarray, max_rows: int = GLITCH_ROWS) -> np.ndarray:
    """Remove runs of up to ``max_rows`` rows (in 1 to 3 steps) that leave a margin and come back."""
    if len(hm) < 3:
        return hm
    vals, lens = [], []
    for x in hm:
        if vals and vals[-1] == x:
            lens[-1] += 1
        else:
            vals.append(x)
            lens.append(1)
    changed = True
    while changed:
        changed = False
        for w in (1, 2, 3):
            for j in range(1, len(vals) - w):
                if vals[j - 1] == vals[j + w] and sum(lens[j:j + w]) <= max_rows:
                    lens[j - 1] += lens[j + w]
                    del vals[j:j + w + 1]
                    del lens[j:j + w + 1]
                    changed = True
                    break
            if changed:
                break
    return np.repeat(np.asarray(vals, float), lens)


def final_scores(plays: pd.DataFrame) -> pd.DataFrame:
    """game_id, home_points, away_points rebuilt from the score diff before each snap."""
    side = np.where(plays["pos_team"].eq(plays["home"]), 1.0,
                    np.where(plays["pos_team"].eq(plays["away"]), -1.0, np.nan))
    hm = plays["pos_score_diff_start"].to_numpy(float) * side
    ok = np.isfinite(hm)
    frame = pd.DataFrame({"game_id": plays["game_id"].to_numpy()[ok], "hm": hm[ok]})
    out = []
    for gid, g in frame.groupby("game_id", sort=False):
        path = _drop_glitches(g["hm"].to_numpy())
        steps = np.diff(np.concatenate([[0.0], path]))
        out.append((gid, steps[steps > 0].sum(), -steps[steps < 0].sum()))
    return pd.DataFrame(out, columns=["game_id", "home_points", "away_points"])


def _season_team_games(plays: pd.DataFrame) -> pd.DataFrame:
    plays = plays.copy()
    plays["garbage"] = pbp.is_garbage(plays)
    tg = efficiency.team_games(plays)
    for c in pbp.LINE_COLS:
        if c not in plays:
            plays[c] = np.nan
    games = plays.groupby("game_id", sort=False).agg(
        home_name=("home", "first"), home_div=("home_team_division", "first"),
        away_div=("away_team_division", "first"), neutral=("neutral_site", "first"),
        spread=("spread", "first"), over_under=("over_under", "first")).reset_index()
    games = games.merge(final_scores(plays), on="game_id", how="left")
    games["game_id"] = games["game_id"].astype("int64")
    tg = tg.merge(games, on="game_id", how="left")
    is_home = tg["team"].eq(tg["home_name"])
    home_fbs = tg["home_div"].eq("fbs")
    away_fbs = tg["away_div"].eq("fbs")
    tg["is_home"] = is_home.astype(int)
    tg["neutral"] = tg["neutral"].fillna(False).astype(bool)
    tg["team_fbs"] = np.where(is_home, home_fbs, away_fbs)
    tg["opp_fbs"] = np.where(is_home, away_fbs, home_fbs)
    tg["team_score"] = np.where(is_home, tg["home_points"], tg["away_points"])
    tg["opp_score"] = np.where(is_home, tg["away_points"], tg["home_points"])
    tg["start_date"] = pd.to_datetime(tg["start_date"], utc=True)
    return tg[efficiency.COLUMNS + EXTRA]


def team_games_all(seasons, data_dir: Path) -> pd.DataFrame:
    """``efficiency.team_games`` rows for ``seasons`` plus the ``EXTRA`` columns, start_date in UTC.

    Cached in ``data_dir/team_games.parquet``: a past season is built once; the current season is
    rebuilt when its play-by-play file is newer than the cache."""
    data_dir = Path(data_dir)
    path = data_dir / CACHE_NAME
    cache = pd.read_parquet(path) if path.exists() else None
    cache_time = path.stat().st_mtime if path.exists() else 0.0
    have = set(cache["season"].unique()) if cache is not None else set()
    current = pbp.current_season()

    fresh, parts = [], []
    for s in sorted(set(int(s) for s in seasons)):
        if s in have and s != current:
            continue
        plays = pbp.load(s, data_dir)
        slim = data_dir / "pbp" / f"slim_{s}.parquet"
        if s in have and (not slim.exists() or slim.stat().st_mtime <= cache_time):
            continue
        fresh.append(s)
        parts.append(_season_team_games(plays))

    if fresh:
        keep = cache[~cache["season"].isin(fresh)] if cache is not None else None
        cache = pd.concat([p for p in [keep, *parts] if p is not None and len(p)], ignore_index=True)
        data_dir.mkdir(parents=True, exist_ok=True)
        part = path.with_name(path.name + ".part")
        cache.to_parquet(part, index=False)
        part.replace(path)
    if cache is None:
        return pd.DataFrame(columns=efficiency.COLUMNS + EXTRA)
    out = cache[cache["season"].isin(list(seasons))].reset_index(drop=True)
    out["start_date"] = pd.to_datetime(out["start_date"], utc=True)
    return out


# ---------------------------------------------------------------------------------------------
# Prior and walk-forward ratings
# ---------------------------------------------------------------------------------------------

def _rating_cols() -> list[str]:
    return [f"{s}_{m}" for m in METRICS for s in ("off", "def")]


def zero_prior() -> pd.DataFrame:
    """No teams: every team starts at the season mean."""
    return pd.DataFrame(columns=_rating_cols(), index=pd.Index([], name="team"), dtype=float)


def carryover_prior(season: int, tg_all: pd.DataFrame, regress: float = DEFAULT_REGRESS) -> pd.DataFrame:
    """Last season's end-of-regular-season ratings times (1 - regress).

    The previous season's ratings are rebuilt from a zero prior. A team with no rating last season
    (new to FBS) starts from the pooled FCS row. With no previous season in ``tg_all`` (the first
    season), the prior is all zero."""
    prev = tg_all[tg_all["season"] == season - 1]
    if prev.empty:
        return zero_prior()
    reg = prev[prev["season_type"] == "regular"] if "season_type" in prev else prev
    reg = reg if len(reg) else prev
    cutoff = pd.to_datetime(reg["start_date"], utc=True).max() + pd.Timedelta(seconds=1)
    end = ratings.as_of(tg_all, cutoff, zero_prior(), {"season": season - 1})
    cols = _rating_cols()
    out = end[cols] * (1.0 - regress)
    cur = tg_all[tg_all["season"] == season]
    if len(cur) and ratings.FCS in out.index:
        fbs = set(cur.loc[cur["team_fbs"].astype(bool), "team"]) | set(cur.loc[cur["opp_fbs"].astype(bool), "opp"])
        new = sorted(t for t in fbs if t not in out.index)
        if new:
            seed = pd.DataFrame([out.loc[ratings.FCS].to_numpy()] * len(new), columns=cols,
                                index=pd.Index(new, name="team"))
            out = pd.concat([out, seed])
    out.index.name = "team"
    return out


def _week_keys(tg: pd.DataFrame) -> pd.Series:
    stype = tg["season_type"] if "season_type" in tg else pd.Series("regular", index=tg.index)
    return stype.astype(str) + ":" + tg["week"].astype(str)


def walk(season: int, tg_all: pd.DataFrame, prior: pd.DataFrame, params: dict) -> pd.DataFrame:
    """One row per (game, team) of ``season`` with pregame ratings.

    Each week's ratings come from ``ratings.as_of`` with cutoff = the earliest start_date of that
    week's games (regular season and postseason weeks kept apart). Columns: game identifiers,
    ``cutoff``, home (1 for a home team not at a neutral site), neutral, ``era`` (season >= 2023),
    own_off_<m>, own_def_<m>, opp_off_<m>, opp_def_<m>, ``points`` (the team's final score) and
    ``drives``."""
    cfg = {k: v for k, v in (params or {}).items() if k in ("prior_games", "decay")}
    rows = tg_all[tg_all["season"] == season].copy()
    if rows.empty:
        return pd.DataFrame()
    rows["start_date"] = pd.to_datetime(rows["start_date"], utc=True)
    rows["wk"] = _week_keys(rows)
    first = rows.groupby("wk")["start_date"].min().sort_values()
    out = []
    cols = _rating_cols()
    for wk, cutoff in first.items():
        r = ratings.as_of(tg_all, cutoff, prior, {**cfg, "season": season})
        week = rows[rows["wk"] == wk]
        team = np.where(week["team_fbs"].astype(bool), week["team"], ratings.FCS)
        opp = np.where(week["opp_fbs"].astype(bool), week["opp"], ratings.FCS)
        own = r.reindex(team)[cols].fillna(0.0).to_numpy()
        oth = r.reindex(opp)[cols].fillna(0.0).to_numpy()
        frame = week.drop(columns=["wk"]).reset_index(drop=True)
        frame["cutoff"] = cutoff
        frame = pd.concat([frame,
                           pd.DataFrame(own, columns=[f"own_{c}" for c in cols]),
                           pd.DataFrame(oth, columns=[f"opp_{c}" for c in cols])], axis=1)
        out.append(frame)
    hist = pd.concat(out, ignore_index=True)
    hist["era"] = (hist["season"] >= CLOCK_RULE_SEASON).astype(float)
    hist["neutral"] = hist["neutral"].astype(bool)
    hist["off_points"] = hist["points"]  # offensive drive points from efficiency
    hist["points"] = hist["team_score"].astype(float)
    return hist


# ---------------------------------------------------------------------------------------------
# Points model
# ---------------------------------------------------------------------------------------------

def _pace(frame: pd.DataFrame) -> np.ndarray:
    """Both offenses' pace: own offense against opponent defense, and the reverse."""
    return np.column_stack([frame[f"own_off_{PACE}"], frame[f"opp_def_{PACE}"],
                            frame[f"opp_off_{PACE}"], frame[f"own_def_{PACE}"]])


def _strength(frame: pd.DataFrame) -> np.ndarray:
    return np.column_stack([frame[f"own_off_{m}"] for m in METRICS] + [frame[f"opp_def_{m}"] for m in METRICS])


def _design(frame: pd.DataFrame, part: str) -> np.ndarray:
    one = np.ones(len(frame))
    home = frame["home"].to_numpy(float)
    neutral = frame["neutral"].to_numpy(float)
    era = frame["era"].to_numpy(float)
    if part == "drives":
        return np.column_stack([one, era, neutral, _pace(frame)])
    if part == "ppd":
        return np.column_stack([one, home, neutral, era, _strength(frame)])
    return np.column_stack([one, home, neutral, era, _strength(frame), _pace(frame)])  # direct


def _lstsq(x: np.ndarray, y: np.ndarray, w: np.ndarray | None = None) -> list[float]:
    if w is not None:
        sw = np.sqrt(w)
        x, y = x * sw[:, None], y * sw
    return [float(v) for v in np.linalg.lstsq(x, y, rcond=None)[0]]


def fit_points(history: pd.DataFrame, kind: str = DEFAULT_KIND) -> dict:
    """Least-squares map from pregame ratings to a team's points, on rows from earlier seasons.

    ``history`` is ``walk`` output (rows with at least one FBS team are used). Returns a JSON-ready
    dict: ``kind`` plus ``direct`` coefficients, or ``drives`` and ``ppd`` coefficients."""
    if kind not in ("direct", "drives"):
        raise ValueError(f"kind must be 'direct' or 'drives', not {kind!r}")
    h = history[history["team_fbs"].astype(bool) | history["opp_fbs"].astype(bool)]
    h = h[np.isfinite(h["points"].to_numpy(float))]
    coef = {"kind": kind, "metrics": list(METRICS), "seasons": sorted(int(s) for s in h["season"].unique()),
            "n": int(len(h))}
    if kind == "direct":
        coef["direct"] = _lstsq(_design(h, "direct"), h["points"].to_numpy(float))
        return coef
    d = h[h["drives"] > 0]
    drives = d["drives"].to_numpy(float)
    coef["drives"] = _lstsq(_design(d, "drives"), drives)
    coef["ppd"] = _lstsq(_design(d, "ppd"), d["points"].to_numpy(float) / drives, drives)
    return coef


def points(frame: pd.DataFrame, coef: dict) -> np.ndarray:
    """Predicted points for each (game, team) row of a ``walk``-shaped frame."""
    if coef["kind"] == "direct":
        return _design(frame, "direct") @ np.asarray(coef["direct"])
    drives = _design(frame, "drives") @ np.asarray(coef["drives"])
    return drives * (_design(frame, "ppd") @ np.asarray(coef["ppd"]))


def _pair(home: pd.Series, away: pd.Series, neutral: bool, season: int | None) -> pd.DataFrame:
    cols = _rating_cols()
    h = np.array([float(home.get(c, 0.0)) for c in cols])
    a = np.array([float(away.get(c, 0.0)) for c in cols])
    h, a = np.nan_to_num(h), np.nan_to_num(a)
    data = {f"own_{c}": [h[i], a[i]] for i, c in enumerate(cols)}
    data.update({f"opp_{c}": [a[i], h[i]] for i, c in enumerate(cols)})
    era = 1.0 if season is None or season >= CLOCK_RULE_SEASON else 0.0
    data.update(home=[0.0 if neutral else 1.0, 0.0], neutral=[float(neutral)] * 2, era=[era, era])
    return pd.DataFrame(data)


def game(home: pd.Series, away: pd.Series, neutral: bool, coef: dict, season: int | None = None) -> dict:
    """margin (home minus away), total, home_pts, away_pts from two teams' ratings.

    ``home`` / ``away`` hold off_<m> and def_<m>. ``season`` sets the clock-rule term (None: the
    current rules, 2023 on)."""
    hp, ap = points(_pair(home, away, bool(neutral), season), coef)
    return {"margin": float(hp - ap), "total": float(hp + ap), "home_pts": float(hp), "away_pts": float(ap)}


# ---------------------------------------------------------------------------------------------
# Season
# ---------------------------------------------------------------------------------------------

def default_train_seasons(season: int) -> list[int]:
    """Every earlier season from 2015 on (2014 has a zero prior), 2020 left out."""
    return [s for s in range(FIRST_SEASON + 1, season) if s != 2020]


_SWAP = {"team": "opp", "team_id": "opp_id", "team_fbs": "opp_fbs", "team_score": "opp_score"}


def _complete_pairs(rows: pd.DataFrame) -> pd.DataFrame:
    """Add the missing side of a game whose other team ran no plays, mirrored from the side present."""
    n = rows.groupby("game_id")["is_home"].transform("size")
    lone = rows[n == 1]
    if lone.empty:
        return rows
    m = lone.copy()
    for a, b in _SWAP.items():
        m[a], m[b] = lone[b].to_numpy(), lone[a].to_numpy()
    for c in _rating_cols():
        m[f"own_{c}"], m[f"opp_{c}"] = lone[f"opp_{c}"].to_numpy(), lone[f"own_{c}"].to_numpy()
    m["is_home"] = 1 - lone["is_home"]
    m["home"] = ((m["is_home"] == 1) & ~m["neutral"].astype(bool)).astype(int)
    return pd.concat([rows, m], ignore_index=True)


def season(season: int, data_dir: Path, params: dict | None = None, prior_fn=None) -> pd.DataFrame:
    """Walk-forward predictions for every game of ``season`` in the play-by-play.

    ``params``: ``prior_games`` and ``decay`` (for ``ratings.as_of``), ``train_seasons`` (default
    ``default_train_seasons``), ``kind`` (``fit_points``), ``regress`` (carryover prior).
    ``prior_fn(season) -> DataFrame`` gives a season's preseason prior (index team, off_<m>,
    def_<m>, optional sd_mult); the default is ``carryover_prior``.

    Only games in the play-by-play are predicted, so a game not yet played is not listed; actual
    points are NaN where the source has no score."""
    params = dict(params or {})
    train = [int(s) for s in params.get("train_seasons", default_train_seasons(season))]
    if any(s >= season for s in train):
        raise ValueError("train_seasons must be earlier than the season predicted")
    need = {season, season - 1, *train, *(s - 1 for s in train)}
    tg_all = team_games_all(sorted(s for s in need if s >= FIRST_SEASON), data_dir)
    regress = float(params.get("regress", DEFAULT_REGRESS))
    if prior_fn is None:
        def prior_fn(s):
            return carryover_prior(s, tg_all, regress)

    hist = [walk(s, tg_all, prior_fn(s), params) for s in train]
    hist = [h for h in hist if len(h)]
    if not hist:
        raise ValueError(f"no training seasons with data for {season}")
    coef = fit_points(pd.concat(hist, ignore_index=True), params.get("kind", DEFAULT_KIND))

    rows = walk(season, tg_all, prior_fn(season), params)
    if rows.empty:
        return pd.DataFrame()
    rows = _complete_pairs(rows)
    rows["pred"] = points(rows, coef)
    h = rows[rows["is_home"] == 1].set_index("game_id")
    a = rows[rows["is_home"] == 0].set_index("game_id")
    gids = h.index.intersection(a.index)
    h, a = h.loc[gids], a.loc[gids]

    out = pd.DataFrame({
        "game_id": gids.astype("int64"),
        "season": h["season"].astype(int).to_numpy(),
        "week": h["week"].astype(int).to_numpy(),
        "season_type": h["season_type"].to_numpy() if "season_type" in h else "regular",
        "start_date": pd.to_datetime(h["start_date"], utc=True).to_numpy(),
        "cutoff": pd.to_datetime(h["cutoff"], utc=True).to_numpy(),
        "home": h["team"].to_numpy(), "away": h["opp"].to_numpy(),
        "home_id": h["team_id"].to_numpy(), "away_id": h["opp_id"].to_numpy(),
        "neutral": h["neutral"].astype(bool).to_numpy(),
        "home_fbs": h["team_fbs"].astype(bool).to_numpy(), "away_fbs": h["opp_fbs"].astype(bool).to_numpy(),
        "home_pts": h["pred"].to_numpy(float), "away_pts": a["pred"].to_numpy(float),
        "home_points": h["team_score"].to_numpy(float), "away_points": h["opp_score"].to_numpy(float),
        "spread": h["spread"].to_numpy(float), "over_under": h["over_under"].to_numpy(float),
    })
    out["start_date"] = pd.to_datetime(out["start_date"], utc=True)
    out["cutoff"] = pd.to_datetime(out["cutoff"], utc=True)
    out["margin"] = out["home_pts"] - out["away_pts"]
    out["total"] = out["home_pts"] + out["away_pts"]
    out.attrs["coef"] = coef
    return out.sort_values(["start_date", "game_id"], kind="stable").reset_index(drop=True)


