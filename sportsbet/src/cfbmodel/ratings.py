"""Opponent-adjusted team ratings rebuilt as of a date.

For one metric, each (game, offense) row is modeled as

    value = mean + off[team] + def[opp] + hfa * home

and solved by weighted ridge: game rows are weighted ``decay ** weeks_ago`` and every team's off and def
is shrunk toward its preseason prior (``prior.off_<metric>`` / ``prior.def_<metric>``) with the strength
of ``prior_games`` pseudo-games, divided by ``sd_mult ** 2`` when the prior carries an ``sd_mult``
column. Ratings are deviations from the season mean, so ``def`` is positive for a defense that allows
more of the metric. Every FCS team is pooled into one team, ``"FCS"``.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

try:
    from .efficiency import METRICS
except ImportError:  # efficiency.py not built yet
    METRICS = ["epa_rush", "epa_pass", "success", "explosive", "finish", "sec_per_play"]

FCS = "FCS"
DEFAULTS = {"prior_games": 4.0, "decay": 0.97}


def _utc(ts) -> pd.Timestamp:
    ts = pd.Timestamp(ts)
    return ts.tz_localize("UTC") if ts.tzinfo is None else ts.tz_convert("UTC")


def _keys(tg: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Team and opponent keys with FCS pooled; mask of rows kept (FCS vs FCS rows dropped)."""
    team_fbs = tg["team_fbs"].to_numpy(bool) if "team_fbs" in tg else np.ones(len(tg), bool)
    opp_fbs = tg["opp_fbs"].to_numpy(bool) if "opp_fbs" in tg else np.ones(len(tg), bool)
    team = np.where(team_fbs, tg["team"].to_numpy(object), FCS)
    opp = np.where(opp_fbs, tg["opp"].to_numpy(object), FCS)
    keep = team_fbs | opp_fbs
    return team[keep], opp[keep], keep


def _solve(tg: pd.DataFrame, metrics: list[str], prior: pd.DataFrame, prior_games: float, decay: float,
           ref_date=None) -> tuple[pd.Index, np.ndarray, np.ndarray, np.ndarray, dict]:
    """Ridge fit for several metrics sharing one design matrix.

    Returns (teams, off[n, k], def[n, k], games[n], extras) with extras holding mean and hfa per metric.
    """
    team, opp, keep = _keys(tg)
    rows = tg[keep]
    teams = pd.Index(prior.index.astype(object)).append(pd.Index(np.concatenate([team, opp]))).unique()
    n, k = len(teams), len(metrics)

    prior_r = prior.reindex(teams)
    if FCS in prior.index:
        prior_r.loc[FCS] = prior.loc[FCS]
    p_off = prior_r[[f"off_{m}" for m in metrics]].to_numpy(float)
    p_def = prior_r[[f"def_{m}" for m in metrics]].to_numpy(float)
    p_off = np.nan_to_num(p_off)  # a team missing from the prior starts at the mean
    p_def = np.nan_to_num(p_def)
    sd = prior_r["sd_mult"].to_numpy(float) if "sd_mult" in prior_r else np.ones(n)
    sd = np.where(np.isfinite(sd) & (sd > 0), sd, 1.0)
    pw = np.sqrt(max(prior_games, 0.0) / sd ** 2)

    ti = teams.get_indexer(team)
    oi = teams.get_indexer(opp)
    games = np.bincount(ti, minlength=n)

    if len(rows) == 0:
        return teams, p_off, p_def, games, {"mean": np.zeros(k), "hfa": np.zeros(k)}

    dates = pd.to_datetime(rows["start_date"], utc=True)
    ref = _utc(ref_date) if ref_date is not None else dates.max()
    weeks_ago = np.floor(((ref - dates).dt.total_seconds() / (7 * 86400)).to_numpy())
    gw = np.sqrt(np.power(float(decay), np.maximum(weeks_ago, 0.0)))

    g = len(rows)
    p = 2 + 2 * n
    a = np.zeros((g + 2 * n, p))
    r = np.arange(g)
    a[r, 0] = 1.0
    a[r, 1] = rows["home"].to_numpy(float)
    a[r, 2 + ti] = 1.0
    a[r, 2 + n + oi] = 1.0
    a[:g] *= gw[:, None]
    d = np.arange(2 * n)
    a[g + d, 2 + d] = np.concatenate([pw, pw])

    vals = rows[metrics].to_numpy(float)
    b_prior = np.vstack([p_off * pw[:, None], p_def * pw[:, None]])
    off = np.empty((n, k))
    dfn = np.empty((n, k))
    mean = np.empty(k)
    hfa = np.empty(k)

    # Metrics with the same missing-value pattern share one solve (normally all of them).
    masks = ~np.isnan(vals)
    groups: dict[bytes, list[int]] = {}
    for j in range(k):
        groups.setdefault(masks[:, j].tobytes(), []).append(j)
    for cols in groups.values():
        ok = masks[:, cols[0]]
        aa = a if ok.all() else np.vstack([a[:g][ok], a[g:]])
        bb = np.vstack([vals[ok][:, cols] * gw[ok, None], b_prior[:, cols]])
        # Normal equations of the augmented system: (p x p) instead of a tall SVD, ~10x faster.
        # lstsq on the square matrix stays safe when it is singular (prior_games = 0).
        x = np.linalg.lstsq(aa.T @ aa, aa.T @ bb, rcond=None)[0]
        mean[cols], hfa[cols] = x[0], x[1]
        off[:, cols], dfn[:, cols] = x[2:2 + n], x[2 + n:]

    # A team with no games is defined only by its prior: return it exactly.
    idle = games == 0
    if idle.any():
        idle_def = np.bincount(oi, minlength=n) == 0
        off[idle] = p_off[idle]
        dfn[idle_def] = p_def[idle_def]
    return teams, off, dfn, games, {"mean": mean, "hfa": hfa}


def adjust(tg: pd.DataFrame, metric: str, prior: pd.DataFrame, prior_games: float, decay: float,
           ref_date=None) -> pd.DataFrame:
    """Opponent-adjusted off and def for one metric from the rows of ``tg``.

    ``ref_date`` is the date ``weeks_ago`` counts back from (default: the latest game in ``tg``).
    Returns columns team, off, def; ``attrs`` carries the fitted ``mean`` and ``hfa``.
    """
    teams, off, dfn, _, extra = _solve(tg, [metric], prior, prior_games, decay, ref_date)
    out = pd.DataFrame({"team": teams.to_numpy(), "off": off[:, 0], "def": dfn[:, 0]})
    out.attrs.update(mean=float(extra["mean"][0]), hfa=float(extra["hfa"][0]))
    return out


def season_of(cutoff) -> int:
    """College season a date belongs to: August through February is the season that began in August."""
    ts = _utc(cutoff)
    return ts.year if ts.month >= 3 else ts.year - 1


def as_of(tg_all: pd.DataFrame, cutoff: pd.Timestamp, prior: pd.DataFrame, params: dict) -> pd.DataFrame:
    """Ratings for every team using only same-season games that started before ``cutoff``.

    ``params``: ``prior_games`` (default 4.0), ``decay`` (default 0.97), optional ``season`` (else
    inferred from ``cutoff``). Returns a frame indexed by team with off_<m>, def_<m> for every metric
    in METRICS and ``games`` (games played as offense). With no games, the ratings equal the prior.
    """
    cfg = {**DEFAULTS, **(params or {})}
    cut = _utc(cutoff)
    season = int(cfg.get("season") or season_of(cut))
    dates = pd.to_datetime(tg_all["start_date"], utc=True)
    rows = tg_all[(tg_all["season"].to_numpy() == season) & (dates < cut).to_numpy()]
    teams, off, dfn, games, _ = _solve(rows, list(METRICS), prior, float(cfg["prior_games"]),
                                       float(cfg["decay"]), ref_date=cut)
    data = {}
    for j, m in enumerate(METRICS):
        data[f"off_{m}"] = off[:, j]
        data[f"def_{m}"] = dfn[:, j]
    out = pd.DataFrame(data, index=pd.Index(teams.to_numpy(), name="team"))
    out["games"] = games.astype(int)
    if len(rows) == 0:  # week 0: hand back the prior itself, bit for bit
        cols = [c for c in prior.columns if c in out.columns]
        out.loc[prior.index, cols] = prior[cols].to_numpy(float)
    return out
