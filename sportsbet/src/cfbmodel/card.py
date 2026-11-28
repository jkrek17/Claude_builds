"""The weekly card: every FBS game of a week with the model's margin, total, chances and picks.

``context`` fits what a season's card needs once (team games, prior, points coefficients and the
key-number distribution, all from earlier seasons, as ``predict.season`` does) and caches the
fitted coefficients in ``data_dir/fits/`` keyed by a hash of the training data and params, so a
second run skips the training walks. ``week_card`` predicts one week's ESPN schedule from ratings as
of that week's first kickoff (``predict.upcoming``). ``rankings`` rates every FBS team now.

The chances are centred on the model number shrunk toward the line (``dist.fit_shrink``: line +
beta x (model - line)), with the betas fitted on out-of-sample predictions of the training seasons
(``shrink_history``), since the model alone is less accurate than the closing line. A game gets no
chance, pick or edge (and no grade) unless both teams are in an FBS conference and rated: at least
one game this season or a carried-over prior row, not the pooled FCS row (``predict.upcoming``).

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
import logging
from pathlib import Path

import numpy as np
import pandas as pd

from . import backtest, dist, predict, ratings, schedule

log = logging.getLogger(__name__)
FIT_VERSION = 3  # 2: points fitted on ESPN's real final scores; 3: shrinkage betas, prior with run params
GAME_KEYS = ["game_id", "week", "start", "home", "away", "home_id", "away_id", "home_conf", "away_conf",
             "neutral", "spread_line", "total_line", "line_provider", "model_margin", "model_total",
             "home_cover_prob", "over_prob", "pick_spread", "pick_total", "edge_spread", "edge_total",
             "bet_spread", "bet_total", "final", "home_score", "away_score", "ats_hit", "ou_hit"]
RANK_KEYS = ["rank", "team", "team_id", "conf", "off", "def", "net"]
FBS_CONFS = frozenset(schedule.CONFERENCES.values())
PBP_LINE = "closing line (play-by-play)"  # provider label for a final's line taken from the pbp file
# Data check: share of FBS games whose final total misses the closing total by more than this. With
# the play-by-play rebuild it was 37% in 2025 (mislabeled offenses in the source file); with ESPN's
# real scores (``predict.apply_scores``) every season 2014-2026 is at 0-4.4% (2025: 0.5%), so no
# training season is dropped any more.
BAD_TOTAL_GAP = 40.0
RANK_METRIC = "success"  # off/def in the rankings: opponent-adjusted success rate, percentage points


# ---------------------------------------------------------------------------------------------
# Fitting, cached
# ---------------------------------------------------------------------------------------------

def dist_history(tg_all: pd.DataFrame, seasons) -> pd.DataFrame:
    """FBS vs FBS regular-season games of ``seasons`` with the play-by-play line and the final score
    (ESPN's, else the play-by-play rebuild: ``predict.team_games_all``)."""
    h = tg_all[tg_all["season"].isin(list(seasons)) & (tg_all["is_home"] == 1)
               & tg_all["team_fbs"].astype(bool) & tg_all["opp_fbs"].astype(bool)]
    if "season_type" in h:
        h = h[h["season_type"] == "regular"]
    return pd.DataFrame({"spread_close": h["spread"].to_numpy(float),
                         "total_close": h["over_under"].to_numpy(float),
                         "home_points": h["team_score"].to_numpy(float),
                         "away_points": h["opp_score"].to_numpy(float)})


def paired_predictions(walk_rows: pd.DataFrame, coef: dict) -> pd.DataFrame:
    """One row per FBS vs FBS regular-season game of ``walk_rows`` (``predict.walk`` output) predicted
    with ``coef``: season, spread_close, total_close, home_points, away_points, margin and total."""
    rows = predict._complete_pairs(walk_rows.reset_index(drop=True))
    rows = rows.assign(pred=predict.points(rows, coef))
    h = rows[rows["is_home"] == 1].drop_duplicates("game_id").set_index("game_id")
    a = rows[rows["is_home"] == 0].drop_duplicates("game_id").set_index("game_id")
    gids = h.index.intersection(a.index)
    h, a = h.loc[gids], a.loc[gids]
    keep = (h["team_fbs"].astype(bool) & h["opp_fbs"].astype(bool)).to_numpy()
    if "season_type" in h:
        keep = keep & (h["season_type"] == "regular").to_numpy()
    h, a = h[keep], a[keep]
    return pd.DataFrame({
        "season": h["season"].astype(int).to_numpy(),
        "spread_close": h["spread"].to_numpy(float), "total_close": h["over_under"].to_numpy(float),
        "home_points": h["team_score"].to_numpy(float), "away_points": h["opp_score"].to_numpy(float),
        "margin": h["pred"].to_numpy(float) - a["pred"].to_numpy(float),
        "total": h["pred"].to_numpy(float) + a["pred"].to_numpy(float)})


def shrink_history(history: pd.DataFrame, kind: str = predict.DEFAULT_KIND) -> pd.DataFrame:
    """Out-of-sample model margins and totals for the training walks, for ``dist.fit_shrink``.

    ``history`` is the training seasons' walks (``predict.model``'s ``history``). Each season is
    predicted with points coefficients fitted on the earlier seasons in ``history`` only, so the
    first season (nothing earlier) is left out. Returns ``paired_predictions`` rows."""
    cols = ["season", "spread_close", "total_close", "home_points", "away_points", "margin", "total"]
    out = []
    seasons = sorted(int(s) for s in history["season"].unique())
    for s in seasons[1:]:
        coef = predict.fit_points(history[history["season"] < s], kind)
        out.append(paired_predictions(history[history["season"] == s], coef))
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame(columns=cols)


def bad_score_share(tg_all: pd.DataFrame) -> dict:
    """season -> share of FBS vs FBS games whose final total misses the line total by > BAD_TOTAL_GAP."""
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
    """tg_all, prior (this season's), coef, dist_fit, params and train for a card.

    Training seasons default to ``predict.default_train_seasons``; an explicit
    ``params["train_seasons"]`` is used as given. ``dist_fit`` holds the widths (``dist.fit`` on the
    training seasons' lines) and the shrinkage betas (``dist.fit_shrink`` on ``shrink_history``).
    With the default prior the fit is cached in ``data_dir/fits/``."""
    params = dict(params or {})
    train = [int(s) for s in params.get("train_seasons", predict.default_train_seasons(season))]
    need = predict.needed_seasons(season, train)
    tg_all = predict.team_games_all(need, data_dir)
    path = None
    if prior_fn is None:
        past = tg_all[tg_all["season"] < season].drop(columns=["start_date"])
        path = Path(data_dir) / "fits" / f"card_{season}_{_fit_key(season, params, train, past)}.json"
    if path is not None and path.exists():
        fit = json.loads(path.read_text())
        coef, dist_fit = fit["coef"], fit["dist"]
        regress = float(params.get("regress", predict.DEFAULT_REGRESS))
        prior = predict.carryover_prior(season, tg_all, regress, params)
    else:
        m = predict.model(season, data_dir, params, prior_fn)
        tg_all, coef = m["tg_all"], m["coef"]
        prior = m["prior_fn"](season)
        dist_fit = {**dist.fit(dist_history(tg_all, train)),
                    **dist.fit_shrink(shrink_history(m["history"], params.get("kind", predict.DEFAULT_KIND)))}
        log.info("card %d: shrinkage beta_margin %.3f, beta_total %.3f", season,
                 dist_fit["beta_margin"], dist_fit["beta_total"])
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"coef": coef, "dist": dist_fit}))
    return {"season": season, "params": params, "tg_all": tg_all, "prior": prior, "coef": coef,
            "dist_fit": dist_fit, "train": train}


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


def _pbp_lines(tg_all: pd.DataFrame, season: int, games: pd.DataFrame | None = None) -> pd.DataFrame:
    """game_id -> play-by-play spread (home perspective) and over_under.

    With ``games`` (ESPN rows with game_id, home_id, away_id), a spread whose play-by-play home team
    is ESPN's away team is flipped to ESPN's home side, as ``predict.espn_lines`` does."""
    rows = tg_all[(tg_all["season"] == season) & (tg_all["is_home"] == 1)]
    out = rows.drop_duplicates("game_id").set_index("game_id")[["spread", "over_under", "team_id"]].copy()
    if games is not None and len(out):
        e = games.drop_duplicates("game_id").set_index("game_id")
        pbp_home = pd.to_numeric(out["team_id"], errors="coerce").astype("float64")
        espn_home = pd.to_numeric(out.index.map(e["home_id"]), errors="coerce").astype("float64")
        espn_away = pd.to_numeric(out.index.map(e["away_id"]), errors="coerce").astype("float64")
        flip = (pbp_home.to_numpy() != np.asarray(espn_home)) & (pbp_home.to_numpy() == np.asarray(espn_away))
        out.loc[flip, "spread"] = -out.loc[flip, "spread"]
    return out[["spread", "over_under"]]


def _fill_scores(g: pd.DataFrame, tg_all: pd.DataFrame, season: int) -> pd.DataFrame:
    """A final ESPN lists without a score takes the team-games score (the play-by-play rebuild)."""
    miss = g["completed"].astype(bool) & (g["home_points"].isna() | g["away_points"].isna())
    if not miss.any():
        return g
    rows = tg_all[(tg_all["season"] == season)].drop_duplicates(["game_id", "team"])
    g = g.copy()
    n = 0
    for i in g.index[miss]:
        gr = rows[rows["game_id"] == g.at[i, "game_id"]]
        if gr.empty:
            continue
        side = gr[pd.to_numeric(gr["team_id"], errors="coerce").astype("float64") == float(g.at[i, "home_id"])]
        side = side if len(side) else gr[gr["is_home"] == 1]
        if side.empty or pd.isna(side["team_score"].iloc[0]):
            continue
        g.at[i, "home_points"] = float(side["team_score"].iloc[0])
        g.at[i, "away_points"] = float(side["opp_score"].iloc[0])
        n += 1
    if n:
        log.info("week card: %d final(s) without an ESPN score take the play-by-play score", n)
    return g


def _market(pred: float, line: float, kind: str, fit: dict) -> dict:
    """prob (conditional on no push, home/over side), pick, edge at -110, or Nones without a line."""
    if pred is None or line is None or not (np.isfinite(pred) and np.isfinite(line)):
        return {"prob": None, "pick": None, "edge": None}
    a, _, b = dist.cover_prob(pred, line, fit) if kind == "spread" else dist.over_prob(pred, line, fit)
    dec = backtest.decimal(backtest.DEFAULT_PRICE)
    ea, eb = a * dec - 1, b * dec - 1
    sides = ("home", "away") if kind == "spread" else ("over", "under")
    if abs(ea - eb) < 1e-9:  # beta 0 makes it a coin flip: the pick is the side the model leans to
        lean = pred + line if kind == "spread" else pred - line
        first = lean >= 0
    else:
        first = ea > eb
    pick, edge = (sides[0], ea) if first else (sides[1], eb)
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
    reuse them. A finished game with no ESPN line takes the play-by-play closing line, and one with
    no ESPN score takes the team-games score (``_fill_scores``)."""
    ctx = ctx or context(season, data_dir, params, prior_fn)
    g = schedule.espn_week(season, week, data_dir) if games is None else games.copy()
    if g.empty:
        return []
    g = _fill_scores(g.reset_index(drop=True), ctx["tg_all"], season)
    preds = predict.upcoming(g, ctx["tg_all"], ctx["prior"], ctx["coef"], ctx["params"], season)
    g = g.merge(preds[["game_id", "margin", "total", "home_rated", "away_rated"]], on="game_id", how="left")
    rated = (g["home_rated"].eq(True) & g["away_rated"].eq(True) & g["home_conf"].isin(FBS_CONFS)
             & g["away_conf"].isin(FBS_CONFS)).to_numpy()

    pl = _pbp_lines(ctx["tg_all"], season, g)
    provider = g["line_provider"].astype(object).where(g["line_provider"].notna(), None)
    for col, src in (("spread_line", "spread"), ("total_line", "over_under")):
        fill = g["game_id"].map(pl[src]) if len(pl) else pd.Series(np.nan, index=g.index)
        use = g[col].isna() & g["completed"].astype(bool) & fill.notna()
        g.loc[use, col] = fill[use]
        provider = provider.where(~use | provider.notna(), PBP_LINE)
    g["line_provider"] = provider

    fit = ctx["dist_fit"]
    sp = [_market(m if ok else None, line, "spread", fit) for m, line, ok in zip(g["margin"], g["spread_line"], rated)]
    to = [_market(t if ok else None, line, "total", fit) for t, line, ok in zip(g["total"], g["total_line"], rated)]
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
