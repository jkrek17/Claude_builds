"""Player props: a predictive distribution for each player's passing, rushing and receiving line.

No sportsbook prices are used. For every player the model gives an expected value and a full
distribution (so the chance of clearing any number can be read off), built in two stages:

1. **Expected value.** A ridge regression per prop and position on the player's own recent and
   longer-run usage and production (decayed sums over earlier games, shrunk for players with little
   history), his share of the team's volume, the opponent's recent defence, and the game's point
   spread and total (the market's when posted, otherwise the nflmodel prediction).
2. **Spread around it.** Yardage props use the historical distribution of actual / expected among
   players with a similar expected value. Count props (receptions, touchdowns) use a negative
   binomial whose dispersion is fitted on history.

Everything a prediction uses comes from games on earlier calendar dates, plus the announced starting
quarterback and the game line.
"""
from __future__ import annotations

import glob
import math
import os
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

TEAM_MAP = {"OAK": "LV", "SD": "LAC", "STL": "LA", "JAC": "JAX", "SL": "LA"}
POSITIONS = ("QB", "RB", "WR", "TE")
FAST, SLOW = 0.75, 0.94      # per-appearance decay of the short and long memory
SHRINK = 3.0                 # appearances of "no history" mixed into every rate
RIDGE = 5.0
N_BINS = 12                  # expected-value bins for the yardage spread
QUANTILES = np.round(np.arange(0.005, 1.0, 0.005), 3)

_RAW = ["completions", "attempts", "passing_yards", "passing_tds", "passing_interceptions",
        "carries", "rushing_yards", "rushing_tds", "targets", "receptions", "receiving_yards",
        "receiving_tds"]
_ID = ["player_id", "player_display_name", "position", "season", "week", "game_id", "team",
       "opponent_team"]


@dataclass(frozen=True)
class Prop:
    key: str                 # name used in files and on the card
    label: str
    target: str              # panel column to predict
    positions: tuple[str, ...]
    kind: str                # "yards" (continuous) or "count"
    gate: str                # pre-game column that decides who gets a line
    gate_min: float
    lines: tuple[float, ...] = ()


PROPS: tuple[Prop, ...] = (
    Prop("pass_yds", "Passing yards", "passing_yards", ("QB",), "yards", "starter", 0.5),
    Prop("pass_tds", "Passing TDs", "passing_tds", ("QB",), "count", "starter", 0.5),
    Prop("pass_att", "Pass attempts", "attempts", ("QB",), "yards", "starter", 0.5),
    Prop("pass_cmp", "Completions", "completions", ("QB",), "yards", "starter", 0.5),
    Prop("rush_yds", "Rushing yards", "rushing_yards", ("QB", "RB"), "yards", "carries_slow", 5.0),
    Prop("rec_yds", "Receiving yards", "receiving_yards", ("RB", "WR", "TE"), "yards", "targets_slow", 2.5),
    Prop("receptions", "Receptions", "receptions", ("RB", "WR", "TE"), "count", "targets_slow", 2.5),
    Prop("any_td", "Anytime TD", "skill_tds", ("QB", "RB", "WR", "TE"), "count", "touches_slow", 4.0),
)
PROP_BY_KEY = {p.key: p for p in PROPS}


# ----------------------------------------------------------------------------- data

def load_offense(data_dir: str, first_season: int = 2006) -> pd.DataFrame:
    """Offensive skill-position rows of nflverse stats_player_week_*.csv."""
    files = sorted(glob.glob(os.path.join(data_dir, "stats_player_week_*.csv")))
    if not files:
        raise FileNotFoundError(f"no stats_player_week_*.csv in {data_dir}")
    parts = []
    for f in files:
        if int(os.path.basename(f)[-8:-4]) < first_season:
            continue
        d = pd.read_csv(f, usecols=_ID + _RAW, low_memory=False)
        parts.append(d[d["position"].isin(POSITIONS)])
    d = pd.concat(parts, ignore_index=True)
    for c in _RAW:
        d[c] = pd.to_numeric(d[c], errors="coerce").fillna(0.0)
    d["team"] = d["team"].replace(TEAM_MAP)
    d["opponent_team"] = d["opponent_team"].replace(TEAM_MAP)
    return d


def _decayed(keys: np.ndarray, x: np.ndarray, decay: float) -> np.ndarray:
    """Pre-game decayed sums of the columns of ``x`` within each run of equal ``keys`` (rows in time order)."""
    out = np.zeros_like(x, dtype=float)
    acc = np.zeros(x.shape[1])
    prev = None
    for i in range(len(keys)):
        if keys[i] != prev:
            acc = np.zeros(x.shape[1])
            prev = keys[i]
        out[i] = acc
        acc = acc * decay + x[i]
    return out


def build_panel(offense: pd.DataFrame, games: pd.DataFrame) -> pd.DataFrame:
    """One row per player per game: the outcomes plus every pre-game feature.

    ``offense`` may contain rows for games not yet played (see :func:`upcoming_rows`); their outcome
    columns are ignored because every feature looks only at earlier rows.
    """
    g = games.copy()
    g["home_team"] = g["home_team"].replace(TEAM_MAP)
    g["away_team"] = g["away_team"].replace(TEAM_MAP)
    g["gameday"] = g["gameday"].astype(str)
    spread = pd.to_numeric(g["spread_line"], errors="coerce")
    total = pd.to_numeric(g["total_line"], errors="coerce")
    sides = []
    for side, opp, sign in (("home", "away", 1.0), ("away", "home", -1.0)):
        sides.append(pd.DataFrame({
            "game_id": g["game_id"], "team": g[f"{side}_team"], "gameday": g["gameday"],
            "is_home": float(side == "home") * (g["location"] != "Neutral"),
            "team_spread": sign * spread,                 # positive = this team is favoured
            "game_total": total,
            "starter_id": g[f"{side}_qb_id"],
            "dome": g["roof"].isin(["dome", "closed"]).astype(float),
        }))
    tg = pd.concat(sides, ignore_index=True)
    tg["implied"] = tg["game_total"] / 2 + tg["team_spread"] / 2

    d = offense.merge(tg, on=["game_id", "team"], how="inner")
    d["skill_tds"] = d["rushing_tds"] + d["receiving_tds"]
    d["touches"] = d["carries"] + d["targets"]
    d["starter"] = ((d["position"] == "QB") & (d["player_id"] == d["starter_id"])).astype(float)
    d = d.sort_values(["gameday", "game_id", "player_id"], kind="mergesort").reset_index(drop=True)

    # Team totals in each game, and their pre-game decayed averages.
    tcols = ["attempts", "carries", "targets", "passing_yards", "rushing_yards", "passing_tds",
             "rushing_tds", "completions"]
    team = d.groupby(["game_id", "team", "opponent_team", "gameday"], as_index=False)[tcols].sum()
    team = team.sort_values(["gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    for who, prefix in (("team", "tm"), ("opponent_team", "opp")):
        t = team.sort_values([who, "gameday"], kind="mergesort")
        x = np.column_stack([t[tcols].to_numpy(float), np.ones(len(t))])
        s = _decayed(t[who].to_numpy(), x, 0.85)
        den = s[:, -1:] + 1e-9
        avg = pd.DataFrame(s[:, :-1] / den, columns=[f"{prefix}_{c}" for c in tcols], index=t.index)
        avg[f"{prefix}_n"] = s[:, -1]
        team = team.join(avg)
    # League averages so far (all earlier dates), to fill teams with no history and to scale defences.
    lg = team.groupby("gameday")[tcols].agg(["sum", "count"])
    for c in tcols:
        cs = lg[(c, "sum")].cumsum().shift(1)
        cn = lg[(c, "count")].cumsum().shift(1)
        team[f"lg_{c}"] = team["gameday"].map(cs / cn)
    for prefix in ("tm", "opp"):
        w = (team[f"{prefix}_n"] / (team[f"{prefix}_n"] + 1.0)).to_numpy()
        for c in tcols:
            lgv = team[f"lg_{c}"].fillna(team[c].mean()).to_numpy()
            team[f"{prefix}_{c}"] = w * team[f"{prefix}_{c}"].to_numpy() + (1 - w) * lgv
    # The defence's allowed numbers belong to the offence facing it: join opp_* by (game, team).
    keep = (["game_id", "team"] + [f"tm_{c}" for c in tcols] + [f"opp_{c}" for c in tcols]
            + [f"lg_{c}" for c in tcols])
    d = d.merge(team[keep], on=["game_id", "team"], how="left")
    d["def_pass_ypa"] = (d["opp_passing_yards"] / d["opp_attempts"].clip(lower=1)) / (
        d["lg_passing_yards"] / d["lg_attempts"]).fillna(1.0)
    d["def_rush_ypc"] = (d["opp_rushing_yards"] / d["opp_carries"].clip(lower=1)) / (
        d["lg_rushing_yards"] / d["lg_carries"]).fillna(1.0)
    d["def_pass_att"] = d["opp_attempts"] / d["lg_attempts"].fillna(d["opp_attempts"])
    d["def_rush_att"] = d["opp_carries"] / d["lg_carries"].fillna(d["opp_carries"])
    d["def_pass_td"] = d["opp_passing_tds"] - d["lg_passing_tds"].fillna(d["opp_passing_tds"])
    d["def_rush_td"] = d["opp_rushing_tds"] - d["lg_rushing_tds"].fillna(d["opp_rushing_tds"])

    # Player history. Quarterback passing memory counts only games he started.
    d = d.sort_values(["player_id", "gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    keys = d["player_id"].to_numpy()
    stats = ["carries", "rushing_yards", "targets", "receptions", "receiving_yards", "skill_tds",
             "touches"]
    x = np.column_stack([d[stats].to_numpy(float), np.ones(len(d)),
                         d["tm_targets"].to_numpy() * 0 + d.groupby(["game_id", "team"])["targets"].transform("sum").to_numpy(),
                         d.groupby(["game_id", "team"])["carries"].transform("sum").to_numpy()])
    for name, decay in (("fast", FAST), ("slow", SLOW)):
        s = _decayed(keys, x, decay)
        n = s[:, len(stats)]
        for j, c in enumerate(stats):
            d[f"{c}_{name}"] = s[:, j] / (n + SHRINK)
        d[f"new_{name}"] = SHRINK / (n + SHRINK)
        d[f"tgt_share_{name}"] = s[:, 2] / (s[:, len(stats) + 1] + SHRINK * 34.0)
        d[f"car_share_{name}"] = s[:, 0] / (s[:, len(stats) + 2] + SHRINK * 27.0)
    st = d["starter"].to_numpy()
    pstats = ["attempts", "completions", "passing_yards", "passing_tds"]
    xp = np.column_stack([d[pstats].to_numpy(float) * st[:, None], st])
    for name, decay in (("fast", FAST), ("slow", SLOW)):
        s = _decayed(keys, xp, decay)
        n = s[:, -1]
        for j, c in enumerate(pstats):
            d[f"{c}_{name}"] = s[:, j] / (n + SHRINK)
        d[f"qbnew_{name}"] = SHRINK / (n + SHRINK)
    d["games_played"] = _decayed(keys, np.ones((len(d), 1)), 1.0)[:, 0]

    d["exp_targets"] = d["tgt_share_slow"] * d["tm_targets"]
    d["exp_carries"] = d["car_share_slow"] * d["tm_carries"]
    d["fav"] = d["team_spread"].fillna(0.0)
    d["implied_pts"] = d["implied"].fillna(22.0)
    d["has_line"] = d["implied"].notna().astype(float)
    return d.sort_values(["gameday", "game_id", "player_id"], kind="mergesort").reset_index(drop=True)


_CTX = ["implied_pts", "fav", "is_home", "dome"]
FEATURES: dict[str, list[str]] = {
    "pass_yds": ["passing_yards_fast", "passing_yards_slow", "attempts_slow", "qbnew_fast", "qbnew_slow",
                 "tm_passing_yards", "tm_attempts", "def_pass_ypa", "def_pass_att"] + _CTX,
    "pass_tds": ["passing_tds_fast", "passing_tds_slow", "passing_yards_slow", "qbnew_slow",
                 "tm_passing_tds", "def_pass_td", "def_pass_ypa"] + _CTX,
    "pass_att": ["attempts_fast", "attempts_slow", "qbnew_fast", "qbnew_slow", "tm_attempts",
                 "def_pass_att", "def_rush_att"] + _CTX,
    "pass_cmp": ["completions_fast", "completions_slow", "attempts_slow", "qbnew_fast", "qbnew_slow",
                 "tm_completions", "tm_attempts", "def_pass_att"] + _CTX,
    "rush_yds": ["rushing_yards_fast", "rushing_yards_slow", "carries_fast", "carries_slow", "new_fast",
                 "new_slow", "exp_carries", "car_share_fast", "tm_carries", "def_rush_ypc",
                 "def_rush_att"] + _CTX,
    "rec_yds": ["receiving_yards_fast", "receiving_yards_slow", "targets_fast", "targets_slow",
                "new_fast", "new_slow", "exp_targets", "tgt_share_fast", "tm_attempts",
                "def_pass_ypa", "def_pass_att"] + _CTX,
    "receptions": ["receptions_fast", "receptions_slow", "targets_fast", "targets_slow", "new_fast",
                   "new_slow", "exp_targets", "tgt_share_fast", "tm_attempts", "def_pass_att"] + _CTX,
    "any_td": ["skill_tds_fast", "skill_tds_slow", "touches_fast", "touches_slow", "new_slow",
               "rushing_yards_slow", "receiving_yards_slow", "exp_carries", "exp_targets",
               "def_rush_td", "def_pass_td"] + _CTX,
}


def eligible(panel: pd.DataFrame, prop: Prop) -> np.ndarray:
    """Rows that get a line for this prop, decided from pre-game information only."""
    return (panel["position"].isin(prop.positions) & (panel[prop.gate] >= prop.gate_min)).to_numpy()


# ----------------------------------------------------------------------------- model

def nb_pmf(mu: float, alpha: float, kmax: int = 40) -> np.ndarray:
    """Negative binomial probabilities for 0..kmax with mean ``mu`` and variance mu + alpha * mu^2."""
    k = np.arange(kmax + 1)
    mu = max(mu, 1e-6)
    if alpha < 1e-6:
        logp = k * math.log(mu) - mu - np.array([math.lgamma(i + 1) for i in k])
    else:
        r = 1.0 / alpha
        logp = (np.array([math.lgamma(i + r) - math.lgamma(i + 1) for i in k]) - math.lgamma(r)
                + r * math.log(r / (r + mu)) + k * math.log(mu / (r + mu)))
    p = np.exp(logp)
    return p / p.sum()


def _fit_alpha(y: np.ndarray, mu: np.ndarray) -> float:
    """Dispersion by maximum likelihood on a grid."""
    best, best_ll = 0.0, -np.inf
    yi = y.astype(int)
    lg = np.array([math.lgamma(i + 1) for i in range(int(yi.max()) + 2)])
    for a in (0.0, 0.02, 0.05, 0.08, 0.12, 0.17, 0.23, 0.3, 0.4, 0.55, 0.75, 1.0, 1.4, 2.0):
        if a == 0.0:
            ll = np.sum(yi * np.log(mu) - mu - lg[yi])
        else:
            r = 1.0 / a
            from math import lgamma
            g = np.vectorize(lgamma)
            ll = np.sum(g(yi + r) - lg[yi] - lgamma(r) + r * np.log(r / (r + mu)) + yi * np.log(mu / (r + mu)))
        if ll > best_ll:
            best, best_ll = a, ll
    return best


@dataclass
class PropModel:
    prop: Prop
    models: dict = field(default_factory=dict)   # position -> fitted pieces

    def fit(self, panel: pd.DataFrame) -> "PropModel":
        feats = FEATURES[self.prop.key]
        ok = eligible(panel, self.prop)
        for pos in self.prop.positions:
            d = panel[ok & (panel["position"] == pos).to_numpy()]
            x = d[feats].to_numpy(float)
            y = d[self.prop.target].to_numpy(float)
            mean, sd = x.mean(0), x.std(0) + 1e-9
            z = (x - mean) / sd
            beta = np.linalg.solve(z.T @ z + RIDGE * np.eye(z.shape[1]), z.T @ (y - y.mean()))
            floor = 0.02 if self.prop.kind == "count" else 1.0
            mu = np.maximum(y.mean() + z @ beta, floor)
            m = {"mean": mean, "sd": sd, "beta": beta, "icept": y.mean(), "floor": floor}
            if self.prop.kind == "count":
                m["alpha"] = _fit_alpha(y, mu)
            else:
                edges = np.quantile(mu, np.linspace(0, 1, N_BINS + 1)[1:-1])
                which = np.searchsorted(edges, mu)
                m["edges"] = edges
                m["centers"] = np.array([mu[which == b].mean() for b in range(N_BINS)])
                m["ratios"] = np.vstack([np.quantile(y[which == b] / mu[which == b], QUANTILES)
                                         for b in range(N_BINS)])
            self.models[pos] = m
        return self

    def expected(self, panel: pd.DataFrame) -> np.ndarray:
        out = np.full(len(panel), np.nan)
        for pos, m in self.models.items():
            sel = (panel["position"] == pos).to_numpy()
            if sel.any():
                z = (panel.loc[sel, FEATURES[self.prop.key]].to_numpy(float) - m["mean"]) / m["sd"]
                out[sel] = np.maximum(m["icept"] + z @ m["beta"], m["floor"])
        return out

    def quantiles(self, pos: str, mu: float) -> np.ndarray:
        """Predictive quantiles (at ``QUANTILES``) of a yardage prop; ratio curves are blended between bins."""
        m = self.models[pos]
        c = m["centers"]
        j = int(np.clip(np.searchsorted(c, mu), 1, len(c) - 1))
        w = float(np.clip((mu - c[j - 1]) / (c[j] - c[j - 1] + 1e-9), 0.0, 1.0))
        return mu * ((1 - w) * m["ratios"][j - 1] + w * m["ratios"][j])

    def pmf(self, pos: str, mu: float) -> np.ndarray:
        return nb_pmf(mu, self.models[pos]["alpha"])

    def prob_over(self, pos: str, mu: float, line: float) -> float:
        """Chance the result is above ``line`` (use a half-point line, or expect pushes to count as not over)."""
        if self.prop.kind == "count":
            p = self.pmf(pos, mu)
            return float(p[int(math.floor(line)) + 1:].sum())
        q = self.quantiles(pos, mu)
        return float(1.0 - np.interp(line, q, QUANTILES, left=0.0, right=1.0))


def fit_all(panel: pd.DataFrame) -> dict[str, PropModel]:
    played = panel[panel["played"]] if "played" in panel else panel
    return {p.key: PropModel(p).fit(played) for p in PROPS}
