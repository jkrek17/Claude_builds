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
SEASON_BREAK, TEAM_BREAK = 0.7, 0.5   # a non-quarterback's memory is cut at a new season and a new team
FIT_FIRST = 2013             # first season the regressions are fitted on (snap counts start then)
MIN_ROWS = 60                # a position needs this many past lines before the model will price it
MAX_BINS = 6                 # expected-value bins for the yardage spread (fewer for small positions)
ANYTD_RIDGE = 25.0
# Consistency: how much wider or narrower than usual a player's yardage range is, from these pre-game signs.
SPREAD_MODS = {"rec_yds": ["adot_slow", "cv_rec", "log_history", "early"],
               "rush_yds": ["cv_rush", "log_history", "early"]}
SPREAD_CLIP = (0.6, 1.6)
ADOT_PRIOR = {"WR": 11.0, "TE": 7.9, "RB": 0.9, "QB": 0.9}   # typical target depth, mixed in as 15 targets
PASS_TD_TRIALS = 9           # passing TDs follow a binomial with this many trials
QUANTILES = np.round(np.arange(0.005, 1.0, 0.005), 3)

_RAW = ["completions", "attempts", "passing_yards", "passing_tds", "passing_interceptions",
        "carries", "rushing_yards", "rushing_tds", "targets", "receptions", "receiving_yards",
        "receiving_tds", "rushing_epa", "receiving_air_yards"]
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
    Prop("rec_yds", "Receiving yards", "receiving_yards", ("RB", "WR", "TE"), "yards", "rec_gate", 1.0),
    Prop("receptions", "Receptions", "receptions", ("RB", "WR", "TE"), "count", "rec_gate", 1.0),
    Prop("any_td", "Anytime TD", "skill_tds", ("QB", "RB", "WR", "TE"), "count", "touches_slow", 4.0),
)
PROP_BY_KEY = {p.key: p for p in PROPS}


# ----------------------------------------------------------------------------- data

def load_offense(data_dir: str, first_season: int = 2009) -> pd.DataFrame:
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


def load_snaps(data_dir: str) -> pd.DataFrame | None:
    """Offensive snap share per player and game (game_id, player_id, snap_pct), or None when not cached.

    nflverse snap counts are keyed by Pro Football Reference ids; players.csv maps them to player_id.
    """
    files = sorted(glob.glob(os.path.join(data_dir, "snap_counts_*.csv")))
    ids = os.path.join(data_dir, "players.csv")
    if not files or not os.path.exists(ids):
        return None
    pl = pd.read_csv(ids, usecols=["gsis_id", "pfr_id"], low_memory=False).dropna().drop_duplicates("pfr_id")
    sc = pd.concat([pd.read_csv(f, usecols=["game_id", "pfr_player_id", "offense_pct"]) for f in files],
                   ignore_index=True)
    sc = sc.merge(pl, left_on="pfr_player_id", right_on="pfr_id")
    sc = sc.groupby(["game_id", "gsis_id"], as_index=False)["offense_pct"].max()
    return sc.rename(columns={"gsis_id": "player_id", "offense_pct": "snap_pct"})


def load_rookie_seasons(data_dir: str) -> pd.DataFrame | None:
    """player_id and rookie_season from nflverse players.csv, or None when not cached."""
    path = os.path.join(data_dir, "players.csv")
    if not os.path.exists(path):
        return None
    pl = pd.read_csv(path, usecols=["gsis_id", "rookie_season"], low_memory=False).dropna(subset=["gsis_id"])
    return pl.drop_duplicates("gsis_id").rename(columns={"gsis_id": "player_id"})


def _decayed(keys: np.ndarray, x: np.ndarray, decay: float, mult: np.ndarray | None = None) -> np.ndarray:
    """Pre-game decayed sums of the columns of ``x`` within each run of equal ``keys`` (rows in time order).

    ``mult[i]`` scales what is remembered before game ``i`` (a memory break)."""
    out = np.zeros_like(x, dtype=float)
    acc = np.zeros(x.shape[1])
    prev = None
    for i in range(len(keys)):
        if keys[i] != prev:
            acc = np.zeros(x.shape[1])
            prev = keys[i]
        elif mult is not None and mult[i] != 1.0:
            acc = acc * mult[i]
        out[i] = acc
        acc = acc * decay + x[i]
    return out


def build_panel(offense: pd.DataFrame, games: pd.DataFrame, snaps: pd.DataFrame | None = None,
                rookies: pd.DataFrame | None = None) -> pd.DataFrame:
    """One row per player per game: the outcomes plus every pre-game feature.

    ``offense`` may contain rows for games not yet played (see :func:`upcoming_rows`); their outcome
    columns are ignored because every feature looks only at earlier rows. The one thing taken from the
    game itself is its line-up: which teammates have a row. For a played game that is who appeared (known
    from the inactives list 90 minutes before kickoff); for an upcoming game it is whoever the caller lists.
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
             "rushing_tds", "completions", "rushing_epa"]
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
    d["def_cmp_rate"] = (d["opp_completions"] / d["opp_attempts"].clip(lower=1)) / (
        d["lg_completions"] / d["lg_attempts"]).fillna(1.0)
    # Team game number within the season, for absences.
    tgn = d[["game_id", "team", "season", "gameday"]].drop_duplicates(["game_id", "team"]).sort_values(["team", "gameday"])
    tgn["tgame_no"] = tgn.groupby(["team", "season"]).cumcount()
    d = d.merge(tgn[["game_id", "team", "tgame_no"]], on=["game_id", "team"], how="left")
    if snaps is not None:
        d = d.merge(snaps, on=["game_id", "player_id"], how="left")
    else:
        d["snap_pct"] = np.nan
    if rookies is not None:
        d = d.merge(rookies, on="player_id", how="left")
        d["exp_years"] = (d["season"] - d["rookie_season"].fillna(d["season"])).clip(0, 12)
        d = d.drop(columns="rookie_season")
    else:
        d["exp_years"] = 0.0

    # Player history. Quarterback passing memory counts only games he started.
    d = d.sort_values(["player_id", "gameday", "game_id"], kind="mergesort").reset_index(drop=True)
    keys = d["player_id"].to_numpy()
    same = np.r_[False, keys[1:] == keys[:-1]]
    sea, tm, no = d["season"].to_numpy(), d["team"].to_numpy(), d["tgame_no"].to_numpy()
    new_season = same & np.r_[False, sea[1:] != sea[:-1]]
    new_team = same & np.r_[False, tm[1:] != tm[:-1]]
    brk = np.ones(len(d))
    brk[new_season] *= SEASON_BREAK
    brk[new_team] *= TEAM_BREAK
    brk[(d["position"] == "QB").to_numpy()] = 1.0
    stay = same & ~new_season & ~new_team
    d["missed_games"] = np.where(stay, np.r_[0, no[1:] - no[:-1] - 1], 0).clip(0, 8).astype(float)
    d["missed_any"] = (d["missed_games"] > 0).astype(float)
    d["late_start"] = np.where(stay, 0, no).clip(0, 8).astype(float)
    d["first_of_season"] = new_season.astype(float)
    d["new_team"] = new_team.astype(float)
    stats = ["carries", "rushing_yards", "targets", "receptions", "receiving_yards", "skill_tds",
             "touches"]
    x = np.column_stack([d[stats].to_numpy(float), np.ones(len(d)),
                         d["tm_targets"].to_numpy() * 0 + d.groupby(["game_id", "team"])["targets"].transform("sum").to_numpy(),
                         d.groupby(["game_id", "team"])["carries"].transform("sum").to_numpy()])
    for name, decay in (("fast", FAST), ("slow", SLOW)):
        s = _decayed(keys, x, decay, brk)
        n = s[:, len(stats)]
        d[f"n_{name}"] = n
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
    d = d.copy()   # defragment after many column inserts

    # Consistency inputs: target depth, and how much the player's own yardage has bounced around.
    cons = _decayed(keys, np.column_stack([d["receiving_air_yards"].to_numpy(float), d["targets"].to_numpy(float),
                                           np.ones(len(d)), d["receiving_yards"].to_numpy(float),
                                           d["receiving_yards"].to_numpy(float) ** 2, d["rushing_yards"].to_numpy(float),
                                           d["rushing_yards"].to_numpy(float) ** 2]), SLOW)
    d["adot_slow"] = (cons[:, 0] + 15.0 * d["position"].map(ADOT_PRIOR).to_numpy(float)) / (cons[:, 1] + 15.0)
    d["log_history"] = np.log1p(cons[:, 2])
    games_eff = np.maximum(cons[:, 2], 1e-9)
    for name, a in (("rec", 3), ("rush", 5)):
        avg = cons[:, a] / games_eff
        sd = np.sqrt(np.maximum(cons[:, a + 1] / games_eff - avg ** 2, 0.0))
        d[f"cv_{name}"] = np.where(cons[:, 2] >= 4.0, sd / np.maximum(avg, 1.0), np.nan)   # needs about 4 games
    d["early"] = (d["week"] <= 4).astype(float)

    # Snap share: last game's and the long-run average (zero when never recorded).
    has = d["snap_pct"].notna().to_numpy()
    sp = np.nan_to_num(d["snap_pct"].to_numpy(float))
    ss = _decayed(keys, np.column_stack([sp * has, has.astype(float)]), SLOW)
    sf = _decayed(keys, np.column_stack([sp * has, has.astype(float)]), FAST)
    d["snap_slow"] = ss[:, 0] / (ss[:, 1] + 1e-12)
    last = np.r_[np.nan, d["snap_pct"].to_numpy(float)[:-1]]
    last[~same] = np.nan
    d["snap_last"] = np.where(np.isnan(last), sf[:, 0] / (sf[:, 1] + 1e-12), last)

    # Vacated volume: the share of targets and carries held by the other teammates in this game's
    # line-up, against what the player has usually had around him. Positive = less competition than usual.
    for kind, col in (("tgt", "tgt_share_slow"), ("car", "car_share_slow")):
        now = (d.groupby(["game_id", "team"])[col].transform("sum") - d[col]).to_numpy()
        h = _decayed(keys, np.column_stack([now, np.ones(len(d))]), FAST)
        usual = np.where(h[:, 1] > 0, h[:, 0] / (h[:, 1] + 1e-12), now)
        d[f"vac_{kind}"] = usual - now

    # The starting quarterback's passing level, for his receivers.
    q = d.loc[d["starter"] == 1, ["game_id", "team", "passing_yards_slow", "qbnew_slow"]].drop_duplicates(["game_id", "team"])
    q["qb_level"] = q["passing_yards_slow"] + 200.0 * q["qbnew_slow"] - 200.0
    d = d.merge(q[["game_id", "team", "qb_level"]], on=["game_id", "team"], how="left")
    d["qb_level"] = d["qb_level"].fillna(0.0)

    d = d.copy()
    d["exp_targets"] = d["tgt_share_slow"] * d["tm_targets"]
    d["exp_carries"] = d["car_share_slow"] * d["tm_carries"]
    d["fav"] = d["team_spread"].fillna(0.0)
    d["implied_pts"] = d["implied"].fillna(22.0)
    d["has_line"] = d["implied"].notna().astype(float)
    hot = d["implied_pts"] - 22.0
    for c in ("skill_tds", "receiving_yards", "receptions", "rushing_yards", "passing_tds"):
        d[f"imp_x_{c}"] = hot * d[f"{c}_slow"]
    d["qb_x_rec_yds"] = d["qb_level"] * d["receiving_yards_slow"] / 100.0
    d["qb_x_rec"] = d["qb_level"] * d["receptions_slow"] / 100.0
    # Unshrunk short-run averages, so a player whose role has just grown still gets a line.
    d["targets_recent"] = d["targets_fast"] / (1.0 - d["new_fast"]).clip(lower=1e-9) * (d["n_fast"] > 0)
    d["rec_gate"] = np.maximum(d["targets_slow"] / 2.5, d["targets_recent"] / 4.0)
    return d.sort_values(["gameday", "game_id", "player_id"], kind="mergesort").reset_index(drop=True)


_CTX = ["implied_pts", "fav", "dome"]
_ABSENCE = ["missed_games", "missed_any", "late_start", "first_of_season", "new_team", "exp_years"]
_SNAPS = ["snap_last", "snap_slow"]
FEATURES: dict[str, list[str]] = {
    "pass_yds": ["passing_yards_fast", "passing_yards_slow", "attempts_slow", "qbnew_fast", "qbnew_slow",
                 "tm_passing_yards", "tm_attempts", "tm_rushing_epa", "def_pass_ypa", "def_pass_att"] + _CTX,
    "pass_tds": ["passing_tds_fast", "passing_tds_slow", "passing_yards_slow", "qbnew_slow",
                 "tm_passing_tds", "def_pass_td", "def_pass_ypa", "imp_x_passing_tds"] + _CTX,
    "pass_att": ["attempts_fast", "attempts_slow", "qbnew_fast", "qbnew_slow", "tm_attempts", "tm_rushing_epa",
                 "def_pass_att", "def_rush_att"] + _CTX,
    "pass_cmp": ["completions_fast", "completions_slow", "attempts_slow", "qbnew_fast", "qbnew_slow",
                 "tm_completions", "tm_attempts", "tm_rushing_epa", "def_pass_att", "def_cmp_rate"] + _CTX,
    "rush_yds": ["rushing_yards_fast", "rushing_yards_slow", "carries_fast", "carries_slow", "new_fast",
                 "new_slow", "exp_carries", "car_share_fast", "tm_carries", "def_rush_ypc",
                 "def_rush_att", "vac_car", "imp_x_rushing_yards"] + _SNAPS + _CTX,
    "rec_yds": ["receiving_yards_fast", "receiving_yards_slow", "targets_fast", "targets_slow",
                "new_fast", "new_slow", "exp_targets", "tgt_share_fast", "tm_attempts",
                "def_pass_ypa", "def_pass_att", "vac_tgt", "imp_x_receiving_yards", "qb_x_rec_yds"]
               + _SNAPS + _ABSENCE + _CTX,
    "receptions": ["receptions_fast", "receptions_slow", "targets_fast", "targets_slow", "new_fast",
                   "new_slow", "exp_targets", "tgt_share_fast", "tm_attempts", "def_pass_att", "def_cmp_rate",
                   "vac_tgt", "imp_x_receptions", "qb_x_rec"] + _SNAPS + _ABSENCE + _CTX,
    "any_td": ["skill_tds_fast", "skill_tds_slow", "touches_fast", "touches_slow", "new_slow",
               "rushing_yards_slow", "receiving_yards_slow", "exp_carries", "exp_targets",
               "def_rush_td", "def_pass_td", "vac_tgt", "vac_car", "imp_x_skill_tds"] + _SNAPS + _CTX,
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


def binom_pmf(mu: float, n: int) -> np.ndarray:
    """Binomial probabilities for 0..n with mean ``mu``: tighter than a Poisson, which suits passing TDs."""
    p = min(max(mu / n, 1e-6), 0.99)
    return np.array([math.comb(n, k) * p ** k * (1 - p) ** (n - k) for k in range(n + 1)])


def _nb_loglik(y: np.ndarray, mu: np.ndarray, alpha: np.ndarray) -> float:
    """Negative binomial log likelihood, up to a constant that does not depend on alpha."""
    r = 1.0 / alpha
    rising = np.zeros(len(y))
    for j in range(int(y.max())):
        rising += np.where(y > j, np.log(r + j), 0.0)
    return float(np.sum(rising + r * np.log(r / (r + mu)) + y * np.log(mu / (r + mu))))


_ALPHAS = (0.005, 0.02, 0.05, 0.08, 0.12, 0.17, 0.23, 0.3, 0.4, 0.55, 0.75, 1.0, 1.4, 2.0)
_SLOPES = (0.0, -0.4, -0.8, -1.0, -1.2, -1.4, -1.8)


def _fit_dispersion(y: np.ndarray, mu: np.ndarray, slopes=(0.0,)) -> tuple[float, float]:
    """(a, b) of alpha = a * mu^b by maximum likelihood on a grid. With one slope of 0 alpha is constant."""
    best, best_ll = (_ALPHAS[0], 0.0), -np.inf
    for b in slopes:
        for a in _ALPHAS:
            ll = _nb_loglik(y, mu, np.clip(a * mu ** b, 1e-4, 5.0))
            if ll > best_ll:
                best, best_ll = (a, b), ll
    return best


def _fit_logistic(z: np.ndarray, y: np.ndarray, ridge: float) -> np.ndarray:
    """Ridge logistic regression by Newton steps; ``z`` is standardised, the intercept is not penalised."""
    x = np.column_stack([np.ones(len(z)), z])
    pen = ridge * np.eye(x.shape[1])
    pen[0, 0] = 0.0
    w = np.zeros(x.shape[1])
    w[0] = math.log(y.mean() / (1 - y.mean()))
    for _ in range(25):
        p = 1 / (1 + np.exp(-(x @ w)))
        step = np.linalg.solve((x * (p * (1 - p))[:, None]).T @ x + pen, x.T @ (y - p) - pen @ w)
        w += step
        if np.abs(step).max() < 1e-8:
            break
    return w


def _fit_log_glm(x: np.ndarray, y: np.ndarray, ridge: float) -> np.ndarray:
    """Coefficients (intercept first) of a log-link regression of a non-negative ``y`` on ``x``, by Newton steps."""
    z = np.column_stack([np.ones(len(x)), x])
    pen = ridge * np.eye(z.shape[1])
    pen[0, 0] = 0.0
    b = np.zeros(z.shape[1])
    b[0] = math.log(max(y.mean(), 1e-6))
    for _ in range(40):
        mu = np.exp(np.clip(z @ b, -12, 12))
        step = np.linalg.solve((z * mu[:, None]).T @ z + pen, z.T @ (y - mu) - pen @ b)
        b += step
        if np.abs(step).max() < 1e-9:
            break
    return b


def _logged(cols: list[str]) -> np.ndarray:
    """Which features are volumes (logged in the anytime-touchdown model)."""
    return np.array([(c.endswith(("_fast", "_slow")) and not c.startswith("new_")) or c.startswith("exp_") for c in cols])


@dataclass
class PropModel:
    prop: Prop
    models: dict = field(default_factory=dict)   # position -> fitted pieces

    def _design(self, d: pd.DataFrame) -> np.ndarray:
        cols = FEATURES[self.prop.key]
        x = d[cols].to_numpy(float)
        if self.prop.key == "any_td":
            lg = _logged(cols)
            x[:, lg] = np.log1p(np.maximum(x[:, lg], 0.0))
        return x

    def fit(self, panel: pd.DataFrame) -> "PropModel":
        ok = eligible(panel, self.prop) & (panel["season"] >= FIT_FIRST).to_numpy()
        for pos in self.prop.positions:
            d = panel[ok & (panel["position"] == pos).to_numpy()]
            if len(d) < MIN_ROWS:
                continue   # too little history for this position: no line
            x = self._design(d)
            y = d[self.prop.target].to_numpy(float)
            mean, sd = x.mean(0), x.std(0) + 1e-9
            z = (x - mean) / sd
            floor = 0.02 if self.prop.kind == "count" else 1.0
            m = {"mean": mean, "sd": sd, "icept": y.mean(), "floor": floor}
            if self.prop.key == "any_td":
                # The chance of scoring is modelled directly; the count around it keeps that chance of zero.
                m["logit"] = _fit_logistic(z, (y > 0).astype(float), ANYTD_RIDGE)
                m["disp"] = _fit_dispersion(y, np.full(len(y), max(y.mean(), floor)))
                self.models[pos] = m
                continue
            m["beta"] = np.linalg.solve(z.T @ z + RIDGE * np.eye(z.shape[1]), z.T @ (y - y.mean()))
            mu = np.maximum(y.mean() + z @ m["beta"], floor)
            if self.prop.key == "receptions":
                m["disp"] = _fit_dispersion(y, mu, _SLOPES)
            elif self.prop.kind == "yards":
                bins = int(np.clip(len(y) // 500, 2, MAX_BINS))
                edges = np.quantile(mu, np.linspace(0, 1, bins + 1)[1:-1])
                which = np.searchsorted(edges, mu)
                m["centers"] = np.array([mu[which == b].mean() for b in range(bins)])
                m["ratios"] = np.vstack([np.quantile(y[which == b] / mu[which == b], QUANTILES)
                                         for b in range(bins)])
                mods = SPREAD_MODS.get(self.prop.key)
                if mods:
                    # How far results land from expectation, as a function of the consistency signs,
                    # after allowing for the expected value itself.
                    raw = d[mods].to_numpy(float)
                    m["mod_mean"], m["mod_sd"] = np.nanmean(raw, 0), np.nanstd(raw, 0) + 1e-9
                    zz = self._mod_z(m, raw)
                    lm = np.log(mu)
                    ctrl = np.column_stack([lm, lm ** 2])
                    ctrl = (ctrl - ctrl.mean(0)) / (ctrl.std(0) + 1e-9)
                    b = _fit_log_glm(np.column_stack([ctrl, zz]), np.abs(y / mu - 1.0), RIDGE)
                    m["mod_coef"] = b[3:]
            self.models[pos] = m
        return self

    @staticmethod
    def _mod_z(m: dict, raw: np.ndarray) -> np.ndarray:
        return np.clip(np.nan_to_num((raw - m["mod_mean"]) / m["mod_sd"], nan=0.0), -3.0, 3.0)

    def spread_scale(self, panel: pd.DataFrame) -> np.ndarray:
        """How wide each row's range is against a typical player with the same expected value.

        1.0 is typical, below 1 a steadier player, above 1 a boom-or-bust one. Always 1.0 for props
        without a consistency adjustment."""
        out = np.ones(len(panel))
        mods = SPREAD_MODS.get(self.prop.key)
        if not mods:
            return out
        for pos, m in self.models.items():
            sel = (panel["position"] == pos).to_numpy()
            if sel.any() and "mod_coef" in m:
                z = self._mod_z(m, panel.loc[sel, mods].to_numpy(float))
                out[sel] = np.clip(np.exp(z @ m["mod_coef"]), *SPREAD_CLIP)
        return out

    def expected(self, panel: pd.DataFrame) -> np.ndarray:
        """Expected value for each row (for anytime TD: the expected number of touchdowns)."""
        out = np.full(len(panel), np.nan)
        for pos, m in self.models.items():
            sel = (panel["position"] == pos).to_numpy()
            if not sel.any():
                continue
            z = (self._design(panel[sel]) - m["mean"]) / m["sd"]
            if "logit" in m:
                p = np.clip(1 / (1 + np.exp(-(m["logit"][0] + z @ m["logit"][1:]))), 1e-4, 0.98)
                r = 1.0 / m["disp"][0]
                out[sel] = r * ((1 - p) ** (-1.0 / r) - 1.0)
            else:
                out[sel] = np.maximum(m["icept"] + z @ m["beta"], m["floor"])
        return out

    def quantiles(self, pos: str, mu: float, scale: float = 1.0) -> np.ndarray:
        """Predictive quantiles (at ``QUANTILES``) of a yardage prop; ratio curves are blended between bins.

        ``scale`` (from :meth:`spread_scale`) stretches the curve about the expected value, keeping its mean."""
        m = self.models[pos]
        c = m["centers"]
        j = int(np.clip(np.searchsorted(c, mu), 1, len(c) - 1))
        w = float(np.clip((mu - c[j - 1]) / (c[j] - c[j - 1] + 1e-9), 0.0, 1.0))
        r = (1 - w) * m["ratios"][j - 1] + w * m["ratios"][j]
        if scale != 1.0:
            wide = np.where(r <= 0, r, np.maximum(1.0 + scale * (r - 1.0), 0.0))
            r = wide * (r.mean() / max(wide.mean(), 1e-9))
        return mu * r

    def pmf(self, pos: str, mu: float) -> np.ndarray:
        """Probability of each count 0, 1, 2, ... for a count prop."""
        if self.prop.key == "pass_tds":
            return binom_pmf(mu, PASS_TD_TRIALS)
        a, b = self.models[pos]["disp"]
        return nb_pmf(mu, float(np.clip(a * max(mu, 1e-6) ** b, 1e-4, 5.0)))

    def prob_over(self, pos: str, mu: float, line: float, scale: float = 1.0) -> float:
        """Chance the result is above ``line`` (use a half-point line, or expect pushes to count as not over)."""
        if self.prop.kind == "count":
            p = self.pmf(pos, mu)
            return float(p[int(math.floor(line)) + 1:].sum())
        q = self.quantiles(pos, mu, scale)
        return float(1.0 - np.interp(line, q, QUANTILES, left=0.0, right=1.0))


def fit_all(panel: pd.DataFrame) -> dict[str, PropModel]:
    played = panel[panel["played"].astype(bool)] if "played" in panel else panel
    return {p.key: PropModel(p).fit(played) for p in PROPS}
