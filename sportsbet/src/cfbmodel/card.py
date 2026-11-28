"""The weekly card: every FBS game of a week with the model's margin, total, chances and picks.

``context`` fits what a season's card needs once (team games, prior, points coefficients and the
key-number distribution, all from earlier seasons, as ``predict.season`` does) and caches the
fitted coefficients in ``data_dir/fits/`` keyed by a hash of the training data and params, so a
second run skips the training walks. ``week_card`` predicts one week's ESPN schedule from ratings as
of that week's first kickoff (``predict.upcoming``). ``rankings`` rates every FBS team now.

Edges are priced at -110 (``backtest.DEFAULT_PRICE``) as ``prob x decimal price - 1``, where prob
is the chance the side wins outright (a push counts as not winning, as in ``backtest.bets``). The
cover and over chances in the output are conditional on no push, so the other side's chance is one
minus it. A game is flagged as a bet only when ``rulebook["passed"]`` is true and the game meets
that market's frozen configuration (``edge_min``, ``segment``), and only for FBS vs FBS regular
season games with a line.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd

from . import backtest, dist, predict, ratings, schedule

FIT_VERSION = 1
GAME_KEYS = ["game_id", "week", "start", "home", "away", "home_id", "away_id", "home_conf", "away_conf",
             "neutral", "spread_line", "total_line", "line_provider", "model_margin", "model_total",
             "home_cover_prob", "over_prob", "pick_spread", "pick_total", "edge_spread", "edge_total",
             "bet_spread", "bet_total", "final", "home_score", "away_score", "ats_hit", "ou_hit"]
RANK_KEYS = ["rank", "team", "team_id", "conf", "off", "def", "net"]
FBS_CONFS = frozenset(schedule.CONFERENCES.values())
PBP_LINE = "closing line (play-by-play)"  # provider label for a final's line taken from the pbp file
# A training season whose rebuilt final scores miss the closing total by more than BAD_TOTAL_GAP
# points in more than MAX_BAD_SHARE of FBS games has broken play-by-play scores (2025: 37% of games,
# against 0-5% in every other season, from mislabeled offenses in the source file). Training the
# points model on it inflates every predicted total by about 14 points, so the card leaves it out.
BAD_TOTAL_GAP = 40.0
MAX_BAD_SHARE = 0.10
RANK_METRIC = "success"  # off/def in the rankings: opponent-adjusted success rate, percentage points


# ---------------------------------------------------------------------------------------------
# Fitting, cached
# ---------------------------------------------------------------------------------------------

def dist_history(tg_all: pd.DataFrame, seasons) -> pd.DataFrame:
    """FBS vs FBS regular-season games of ``seasons`` with the play-by-play line and final score."""
    h = tg_all[tg_all["season"].isin(list(seasons)) & (tg_all["is_home"] == 1)
               & tg_all["team_fbs"].astype(bool) & tg_all["opp_fbs"].astype(bool)]
    if "season_type" in h:
        h = h[h["season_type"] == "regular"]
    return pd.DataFrame({"spread_close": h["spread"].to_numpy(float),
                         "total_close": h["over_under"].to_numpy(float),
                         "home_points": h["team_score"].to_numpy(float),
                         "away_points": h["opp_score"].to_numpy(float)})


def bad_score_share(tg_all: pd.DataFrame) -> dict:
    """season -> share of FBS vs FBS games whose rebuilt total misses the line total by > BAD_TOTAL_GAP."""
    h = tg_all[(tg_all["is_home"] == 1) & tg_all["team_fbs"].astype(bool) & tg_all["opp_fbs"].astype(bool)]
    gap = ((h["team_score"] + h["opp_score"]) - h["over_under"]).abs()
    ok = gap.notna()
    return {int(s): float(v) for s, v in (gap[ok] > BAD_TOTAL_GAP).groupby(h.loc[ok, "season"]).mean().items()}


def _fit_key(season: int, params: dict, train: list[int], tg: pd.DataFrame) -> str:
    cfg = {k: params[k] for k in sorted(params) if k != "train_seasons"}
    h = hashlib.sha256(json.dumps({"v": FIT_VERSION, "season": season, "train": train, "params": cfg},
                                  sort_keys=True, default=str).encode())
    h.update(str(int(pd.util.hash_pandas_object(tg, index=False).sum())).encode())
    return h.hexdigest()[:16]


def context(season: int, data_dir: Path, params: dict | None = None, prior_fn=None) -> dict:
    """tg_all, prior (this season's), coef, dist_fit, params, train and dropped_seasons for a card.

    Training seasons default to ``predict.default_train_seasons`` minus any with broken rebuilt
    scores (``bad_score_share`` above ``MAX_BAD_SHARE``); an explicit ``params["train_seasons"]`` is
    used as given. With the default prior the fit is cached in ``data_dir/fits/``."""
    params = dict(params or {})
    train = [int(s) for s in params.get("train_seasons", predict.default_train_seasons(season))]
    need = predict.needed_seasons(season, train)
    tg_all = predict.team_games_all(need, data_dir)
    share = bad_score_share(tg_all)
    dropped = [s for s in train if share.get(s, 0.0) > MAX_BAD_SHARE]
    if dropped and "train_seasons" not in params:
        train = [s for s in train if s not in dropped]
        params["train_seasons"] = train
    else:
        dropped = []
    path = None
    if prior_fn is None:
        past = tg_all[tg_all["season"] < season].drop(columns=["start_date"])
        path = Path(data_dir) / "fits" / f"card_{season}_{_fit_key(season, params, train, past)}.json"
    if path is not None and path.exists():
        fit = json.loads(path.read_text())
        coef, dist_fit = fit["coef"], fit["dist"]
        regress = float(params.get("regress", predict.DEFAULT_REGRESS))
        prior = predict.carryover_prior(season, tg_all, regress)
    else:
        m = predict.model(season, data_dir, params, prior_fn)
        tg_all, coef = m["tg_all"], m["coef"]
        prior = m["prior_fn"](season)
        dist_fit = dist.fit(dist_history(tg_all, train))
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"coef": coef, "dist": dist_fit}))
    return {"season": season, "params": params, "tg_all": tg_all, "prior": prior, "coef": coef,
            "dist_fit": dist_fit, "train": train, "dropped_seasons": dropped}


# ---------------------------------------------------------------------------------------------
# Card
# ---------------------------------------------------------------------------------------------

def _num(x, digits: int | None = None):
    """A JSON-ready float (None for NaN/None), optionally rounded."""
    if x is None:
        return None
    try:
        v = float(x)
    except (TypeError, ValueError):
        return None
    if not np.isfinite(v):
        return None
    return round(v, digits) if digits is not None else v


def _int(x):
    try:
        return None if x is None or pd.isna(x) else int(x)
    except (TypeError, ValueError):
        return None


def grade(pick: str | None, score: float) -> bool | None:
    """Hit (True), miss (False) or push/no pick (None). ``score`` > 0 means home covered / over hit."""
    if pick is None or score is None or not np.isfinite(score) or score == 0:
        return None
    return (score > 0) == (pick in ("home", "over"))


def _pbp_lines(tg_all: pd.DataFrame, season: int) -> pd.DataFrame:
    rows = tg_all[(tg_all["season"] == season) & (tg_all["is_home"] == 1)]
    return rows.drop_duplicates("game_id").set_index("game_id")[["spread", "over_under"]]


def _market(pred: float, line: float, kind: str, fit: dict) -> dict:
    """prob (conditional on no push, home/over side), pick, edge at -110, or Nones without a line."""
    if pred is None or line is None or not (np.isfinite(pred) and np.isfinite(line)):
        return {"prob": None, "pick": None, "edge": None}
    a, _, b = dist.cover_prob(pred, line, fit) if kind == "spread" else dist.over_prob(pred, line, fit)
    dec = backtest.decimal(backtest.DEFAULT_PRICE)
    ea, eb = a * dec - 1, b * dec - 1
    sides = ("home", "away") if kind == "spread" else ("over", "under")
    pick, edge = (sides[0], ea) if ea >= eb else (sides[1], eb)
    prob = a / (a + b) if a + b > 0 else None
    return {"prob": prob, "pick": pick, "edge": edge}


def _bets(frame: pd.DataFrame, rulebook: dict | None, market: str) -> np.ndarray:
    """True where the frozen ``rulebook`` configuration for ``market`` would bet."""
    out = np.zeros(len(frame), dtype=bool)
    if not rulebook or rulebook.get("passed") is not True or not rulebook.get(market):
        return out
    cfg = rulebook[market]
    edge = frame[f"edge_{market}"].astype(float).to_numpy()
    ok = (np.isfinite(edge) & (edge >= float(cfg.get("edge_min", 0.0)))
          & frame["home_conf"].isin(FBS_CONFS).to_numpy() & frame["away_conf"].isin(FBS_CONFS).to_numpy()
          & (frame["season_type"] == schedule.REGULAR).to_numpy())
    seg = backtest._segment_mask(frame, cfg.get("segment", "all")).to_numpy(bool)
    return ok & seg


def week_card(season: int, week: int, data_dir: Path, params: dict | None = None, prior_fn=None,
              rulebook: dict | None = None, ctx: dict | None = None,
              games: pd.DataFrame | None = None) -> list[dict]:
    """One dict per game of ``week`` (keys ``GAME_KEYS``, the cfb.json game contract).

    ``games`` (default ``schedule.espn_week``) and ``ctx`` (default ``context``) can be passed in to
    reuse them. A finished game with no ESPN line takes the play-by-play closing line."""
    ctx = ctx or context(season, data_dir, params, prior_fn)
    g = schedule.espn_week(season, week, data_dir) if games is None else games.copy()
    if g.empty:
        return []
    g = g.reset_index(drop=True)
    preds = predict.upcoming(g, ctx["tg_all"], ctx["prior"], ctx["coef"], ctx["params"], season)
    g = g.merge(preds[["game_id", "margin", "total"]], on="game_id", how="left")

    pl = _pbp_lines(ctx["tg_all"], season)
    provider = g["line_provider"].astype(object).where(g["line_provider"].notna(), None)
    for col, src in (("spread_line", "spread"), ("total_line", "over_under")):
        fill = g["game_id"].map(pl[src]) if len(pl) else pd.Series(np.nan, index=g.index)
        use = g[col].isna() & g["completed"].astype(bool) & fill.notna()
        g.loc[use, col] = fill[use]
        provider = provider.where(~use | provider.notna(), PBP_LINE)
    g["line_provider"] = provider

    fit = ctx["dist_fit"]
    sp = [_market(m, line, "spread", fit) for m, line in zip(g["margin"], g["spread_line"])]
    to = [_market(t, line, "total", fit) for t, line in zip(g["total"], g["total_line"])]
    g["edge_spread"] = [s["edge"] if s["edge"] is not None else np.nan for s in sp]
    g["edge_total"] = [t["edge"] if t["edge"] is not None else np.nan for t in to]
    g["season"] = season
    bet_s, bet_t = _bets(g, rulebook, "spread"), _bets(g, rulebook, "total")

    out = []
    for i, r in g.iterrows():
        final = bool(r["completed"]) and np.isfinite(r["home_points"]) and np.isfinite(r["away_points"])
        hs, as_ = (r["home_points"], r["away_points"]) if final else (np.nan, np.nan)
        ats = grade(sp[i]["pick"], (hs - as_) + r["spread_line"]) if final else None
        ou = grade(to[i]["pick"], (hs + as_) - r["total_line"]) if final else None
        out.append({
            "game_id": int(r["game_id"]), "week": int(r["week"]),
            "start": r["start_date"].strftime("%Y-%m-%dT%H:%M:%SZ") if pd.notna(r["start_date"]) else None,
            "home": r["home"], "away": r["away"], "home_id": _int(r["home_id"]), "away_id": _int(r["away_id"]),
            "home_conf": r["home_conf"] if isinstance(r["home_conf"], str) else None,
            "away_conf": r["away_conf"] if isinstance(r["away_conf"], str) else None,
            "neutral": bool(r["neutral"]),
            "spread_line": _num(r["spread_line"], 1), "total_line": _num(r["total_line"], 1),
            "line_provider": r["line_provider"] if isinstance(r["line_provider"], str) else None,
            "model_margin": _num(r["margin"], 1), "model_total": _num(r["total"], 1),
            "home_cover_prob": _num(sp[i]["prob"], 3), "over_prob": _num(to[i]["prob"], 3),
            "pick_spread": sp[i]["pick"], "pick_total": to[i]["pick"],
            "edge_spread": _num(sp[i]["edge"], 3), "edge_total": _num(to[i]["edge"], 3),
            "bet_spread": bool(bet_s[i]), "bet_total": bool(bet_t[i]),
            "final": final, "home_score": _int(hs) if final else None, "away_score": _int(as_) if final else None,
            "ats_hit": ats, "ou_hit": ou,
        })
    return out


# ---------------------------------------------------------------------------------------------
# Rankings
# ---------------------------------------------------------------------------------------------

def _conferences(tg_all: pd.DataFrame, season: int, data_dir: Path | None) -> dict:
    """team id -> conference name from the season's play-by-play (last game wins)."""
    if data_dir is None:
        return {}
    try:
        from . import pbp
        plays = pbp.load(season, data_dir)
    except Exception:  # noqa: BLE001 - conferences are a nicety; no file means no names
        return {}
    games = plays.drop_duplicates("game_id").sort_values("start_date")
    out: dict = {}
    for side in ("home", "away"):
        ids = pd.to_numeric(games[f"{side}_team_id"], errors="coerce")
        for tid, conf in zip(ids, games[f"{side}_team_conference"]):
            if pd.notna(tid) and isinstance(conf, str):
                out[int(tid)] = conf
    return out


def rankings(season: int, data_dir: Path | None, params: dict | None = None, prior_fn=None,
             ctx: dict | None = None, cutoff=None, conferences: dict | None = None) -> list[dict]:
    """Every FBS team that has played this season, best first (keys ``RANK_KEYS``).

    ``net``: predicted margin in points against an average FBS team (the mean of the FBS teams'
    ratings) on a neutral field. ``off`` / ``def``: opponent-adjusted success rate above or below
    average in percentage points (``def`` above zero allows more success: worse). Ratings use every
    game before ``cutoff`` (default now)."""
    ctx = ctx or context(season, data_dir, params, prior_fn)
    tg_all = ctx["tg_all"]
    cut = pd.Timestamp.now(tz="UTC") if cutoff is None else pd.Timestamp(cutoff)
    cfg = {k: v for k, v in ctx["params"].items() if k in ("prior_games", "decay")}
    r = ratings.as_of(tg_all, cut, ctx["prior"], {**cfg, "season": season})
    cur = tg_all[(tg_all["season"] == season) & tg_all["team_fbs"].astype(bool)]
    if cur.empty:
        return []
    names = cur.dropna(subset=["team_id"]).drop_duplicates("team", keep="last").set_index("team")["team_id"]
    teams = [t for t in names.index if t in r.index and t != ratings.FCS]
    if not teams:
        return []
    cols = predict._rating_cols()
    avg = r.loc[teams, cols].mean()
    confs = conferences if conferences is not None else _conferences(tg_all, season, data_dir)
    rows = []
    for t in teams:
        net = predict.game(r.loc[t, cols], avg, True, ctx["coef"], season)["margin"]
        tid = int(names[t])
        rows.append({"team": t, "team_id": tid, "conf": confs.get(tid),
                     "off": _num(100 * (r.loc[t, f"off_{RANK_METRIC}"] - avg[f"off_{RANK_METRIC}"]), 2),
                     "def": _num(100 * (r.loc[t, f"def_{RANK_METRIC}"] - avg[f"def_{RANK_METRIC}"]), 2),
                     "net": _num(net, 1), "_net": net})
    rows.sort(key=lambda x: -x["_net"])
    out = []
    for i, x in enumerate(rows, 1):
        x.pop("_net")
        out.append({"rank": i, **x})
    return out
