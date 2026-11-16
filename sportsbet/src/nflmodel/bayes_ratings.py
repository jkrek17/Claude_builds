"""Bayesian team ratings: every rating comes with an uncertainty band.

Each team has an offence and a defence rating for three per-game measures ("channels"): points,
passing EPA and rushing EPA. A team's value in a game is modelled as

    value = league level +/- home edge + offence(team) + defence(opponent) + noise

and the ratings are followed through time with a Kalman filter, which is the exact Bayesian update
for this model. Ratings drift a little every week, are pulled toward average between seasons, and
become less certain when a team changes its starting quarterback. The filter therefore carries a
mean and a variance for every rating: bands are wide in September and after a quarterback change and
narrow as games accumulate.

Everything is walk-forward. Games are processed one calendar day at a time, a game's numbers are read
before that day's results are used, and unplayed games never update anything.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

TEAM_MAP = {"OAK": "LV", "SD": "LAC", "STL": "LA", "JAC": "JAX", "SL": "LA", "LAR": "LA", "WSH": "WAS"}
CHANNELS = ("pts", "pass", "rush")

# Tuned by one-step-ahead likelihood on seasons 2003-2017 only (research/stochastic/tune.py).
#   obs_sd   noise of one team-game around its expected value
#   prior_sd spread of true team ratings at the start of a season
#   keep     fraction of a rating carried over an off-season
#   drift_sd weekly random-walk step of a rating
#   qb_sd    extra offence uncertainty when the starting quarterback changes
#   depth_sd further offence uncertainty for each step down the depth chart (3rd starter and beyond)
PARAMS = {
    "pts": dict(obs_sd=9.096, prior_sd=2.621, keep=0.635, drift_sd=0.388, qb_sd=0.596, depth_sd=0.309),
    "pass": dict(obs_sd=9.779, prior_sd=2.699, keep=0.673, drift_sd=0.320, qb_sd=1.223, depth_sd=1.418),
    "rush": dict(obs_sd=5.043, prior_sd=1.011, keep=0.500, drift_sd=0.143, qb_sd=0.0, depth_sd=0.0),
}
INIT_LEVEL = {"pts": 20.8, "pass": 0.0, "rush": -2.0}
LEVEL_DRIFT = {"pts": 0.05, "pass": 0.05, "rush": 0.03}  # weekly drift of the league level
HOME_PRIOR = {"pts": (1.3, 1.0), "pass": (0.8, 1.0), "rush": (0.3, 0.5)}  # mean, sd of the home edge
HOME_DRIFT = 0.01


def qb_depth(games: pd.DataFrame) -> pd.DataFrame:
    """Where each game's starting quarterback stands in his team's season, known before kickoff.

    Per game and side: `changed` (1 when the starter differs from the team's previous game) and
    `string` (1 for the team's first starter of the season, 2 for the second different quarterback
    to start, 3 for the third, and so on). A quarterback who returns keeps his original number.
    A missing starter is taken to be the team's most recent one.
    """
    g = games.copy()
    g["gameday"] = g["gameday"].astype(str)
    g = g.sort_values(["gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    played = (pd.to_numeric(g["home_score"], errors="coerce").notna()
              & pd.to_numeric(g["away_score"], errors="coerce").notna()).to_numpy()
    seasons = g["season"].to_numpy()
    out = {f"{s}_{k}": np.zeros(len(g)) for s in ("home", "away") for k in ("qb_changed", "qb_string")}
    order: dict = {}   # (team, season) -> [quarterbacks in order of first start]
    last: dict = {}    # team -> most recent starter
    for side in ("home", "away"):
        g[side] = g[f"{side}_team"].replace(TEAM_MAP)
    cols = {s: (g[s].to_numpy(), g[f"{s}_qb_id"].to_numpy(dtype=object)) for s in ("home", "away")}
    for i in range(len(g)):
        for side in ("home", "away"):
            team, qb = cols[side][0][i], cols[side][1][i]
            if not isinstance(qb, str):
                qb = last.get(team)
            seen = order.get((team, seasons[i]), [])
            if qb is None:
                string = 1
            elif qb in seen:
                string = seen.index(qb) + 1
            else:
                string = len(seen) + 1
            out[f"{side}_qb_string"][i] = string
            out[f"{side}_qb_changed"][i] = float(qb is not None and team in last and last[team] != qb)
        if played[i]:  # same-day games involve different teams, so updating in row order is safe
            for side in ("home", "away"):
                team, qb = cols[side][0][i], cols[side][1][i]
                if not isinstance(qb, str):
                    qb = last.get(team)
                if qb is None:
                    continue
                seen = order.setdefault((team, seasons[i]), [])
                if qb not in seen:
                    seen.append(qb)
                last[team] = qb
    f = pd.DataFrame({"game_id": g["game_id"].to_numpy()})
    for k, v in out.items():
        f[k] = v
    return f


def channel_values(games: pd.DataFrame, team_stats: pd.DataFrame) -> pd.DataFrame:
    """games plus home_/away_ value columns for each channel (NaN for unplayed games)."""
    g = games.copy()
    g["home"] = g["home_team"].replace(TEAM_MAP)
    g["away"] = g["away_team"].replace(TEAM_MAP)
    played = pd.to_numeric(g["home_score"], errors="coerce").notna() & pd.to_numeric(g["away_score"], errors="coerce").notna()
    s = team_stats[["game_id", "team", "passing_epa", "rushing_epa"]].dropna(subset=["team"]).copy()
    s["team"] = s["team"].replace(TEAM_MAP)
    s = s.drop_duplicates(["game_id", "team"])
    for side in ("home", "away"):
        m = g[["game_id", side]].merge(s, left_on=["game_id", side], right_on=["game_id", "team"], how="left")
        g[f"{side}_pts"] = pd.to_numeric(g[f"{side}_score"], errors="coerce").where(played)
        g[f"{side}_pass"] = m["passing_epa"].where(played.to_numpy()).to_numpy()
        g[f"{side}_rush"] = m["rushing_epa"].where(played.to_numpy()).to_numpy()
    return g


def run_filter(games: pd.DataFrame, team_stats: pd.DataFrame, params: dict | None = None,
               channels=CHANNELS, state: dict | None = None) -> pd.DataFrame:
    """Pre-game expected value and uncertainty for both offences in every game, per channel.

    Columns per channel c and side s (home, away), all as they stood before the game's date:
      {c}_{s}        expected value of that side's offence against the other side's defence
      {c}_{s}_var    variance of that expectation that comes from not knowing the ratings exactly
      {c}_{s}_sd_obs standard deviation of one game around the expectation (rating uncertainty + noise)
    plus `loglik_{c}`, the log-likelihood of the played game under the pre-game prediction.

    If `state` is a dict it receives, per channel, the teams, the rating means and their covariance
    after the last played game.
    """
    params = params or PARAMS
    g = channel_values(games, team_stats)
    g["gameday"] = pd.to_datetime(g["gameday"].astype(str))
    g = g.sort_values(["gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    depth = qb_depth(games)  # same row order: sorted by (gameday, game_id)
    teams = sorted(set(g["home"]) | set(g["away"]))
    ix = {t: k for k, t in enumerate(teams)}
    nt = len(teams)
    n = len(g)
    home_i = g["home"].map(ix).to_numpy()
    away_i = g["away"].map(ix).to_numpy()
    neutral = (g["location"].astype(str).str.lower() == "neutral").to_numpy()
    seasons = g["season"].to_numpy()
    days = g["gameday"].to_numpy()
    changed = {s: depth[f"{s}_qb_changed"].to_numpy() for s in ("home", "away")}
    string = {s: depth[f"{s}_qb_string"].to_numpy() for s in ("home", "away")}
    out = pd.DataFrame({"game_id": g["game_id"].to_numpy(), "season": seasons})
    for side in ("home", "away"):
        out[f"{side}_qb_changed"] = changed[side]
        out[f"{side}_qb_string"] = string[side]

    # day boundaries
    starts = [0] + [k for k in range(1, n) if days[k] != days[k - 1]] + [n]

    for c in channels:
        p = params[c]
        dim = 2 * nt + 2          # offences, defences, league level, home edge
        LV, HF = 2 * nt, 2 * nt + 1
        x = np.zeros(dim)
        x[LV] = INIT_LEVEL[c]
        x[HF] = HOME_PRIOR[c][0]
        P = np.zeros((dim, dim))
        P[np.arange(2 * nt), np.arange(2 * nt)] = p["prior_sd"] ** 2
        P[LV, LV] = 2.0 ** 2
        P[HF, HF] = HOME_PRIOR[c][1] ** 2
        r = p["obs_sd"] ** 2
        vh, va = g[f"home_{c}"].to_numpy(dtype=float), g[f"away_{c}"].to_numpy(dtype=float)
        mean = {s: np.full(n, np.nan) for s in ("home", "away")}
        var = {s: np.full(n, np.nan) for s in ("home", "away")}
        ll = np.full(n, np.nan)
        cur_season, last_day = None, None
        team_block = slice(0, 2 * nt)
        for b in range(len(starts) - 1):
            i, j = starts[b], starts[b + 1]
            if seasons[i] != cur_season:
                if cur_season is not None:
                    k = p["keep"]
                    x[team_block] *= k
                    P[team_block, :] *= k
                    P[:, team_block] *= k
                    P[np.arange(2 * nt), np.arange(2 * nt)] += (1 - k * k) * p["prior_sd"] ** 2
                cur_season = seasons[i]
            elif last_day is not None:
                weeks = min(float((days[i] - last_day) / np.timedelta64(1, "D")) / 7.0, 2.0)
                P[np.arange(2 * nt), np.arange(2 * nt)] += weeks * p["drift_sd"] ** 2
                P[LV, LV] += weeks * LEVEL_DRIFT[c] ** 2
                P[HF, HF] += weeks * HOME_DRIFT ** 2
            last_day = days[i]
            # a quarterback change makes that offence less certain before the game is predicted
            for k in range(i, j):
                for side, ti in (("home", home_i[k]), ("away", away_i[k])):
                    if changed[side][k]:
                        extra = p["qb_sd"] ** 2 + max(string[side][k] - 2.0, 0.0) * p["depth_sd"] ** 2
                        P[ti, ti] += extra
            m = j - i
            H = np.zeros((2 * m, dim))
            y = np.empty(2 * m)
            for q, k in enumerate(range(i, j)):
                h, a = home_i[k], away_i[k]
                edge = 0.0 if neutral[k] else 1.0
                H[2 * q, [h, nt + a, LV]] = 1.0
                H[2 * q, HF] = edge
                H[2 * q + 1, [a, nt + h, LV]] = 1.0
                H[2 * q + 1, HF] = -edge
                y[2 * q], y[2 * q + 1] = vh[k], va[k]
            pred = H @ x
            PHt = P @ H.T
            S = H @ PHt
            sv = np.diag(S)
            mean["home"][i:j], mean["away"][i:j] = pred[0::2], pred[1::2]
            var["home"][i:j], var["away"][i:j] = sv[0::2], sv[1::2]
            ok = np.isfinite(y)
            e = y - pred
            tot = sv + r
            for q, k in enumerate(range(i, j)):
                if ok[2 * q] and ok[2 * q + 1]:
                    ll[k] = -0.5 * sum(np.log(2 * np.pi * tot[z]) + e[z] ** 2 / tot[z] for z in (2 * q, 2 * q + 1))
            if ok.any():
                Hk, ek = H[ok], e[ok]
                Sk = S[np.ix_(ok, ok)] + r * np.eye(int(ok.sum()))
                K = np.linalg.solve(Sk, PHt[:, ok].T).T
                x = x + K @ ek
                P = P - K @ Hk @ P
                P = (P + P.T) / 2
        for s in ("home", "away"):
            out[f"{c}_{s}"] = mean[s]
            out[f"{c}_{s}_var"] = var[s]
            out[f"{c}_{s}_sd_obs"] = np.sqrt(var[s] + r)
        out[f"loglik_{c}"] = ll
        if state is not None:
            state[c] = {"teams": teams, "offense": x[:nt].copy(), "defense": x[nt:2 * nt].copy(),
                        "offense_sd": np.sqrt(np.diag(P)[:nt]), "defense_sd": np.sqrt(np.diag(P)[nt:2 * nt]),
                        "level": float(x[LV]), "home_edge": float(x[HF])}
    return out
