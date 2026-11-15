"""Candidate game-environment and opponent features, all from games on earlier calendar dates.

`team_table(panel)` gives one row per offence per game (position-split and team totals, sacks, EPA,
points).  `Ctx` then makes pre-game decayed quantities:
  off(col)            the offence's shrunk decayed average
  dfn(col, adjust)    what the defence allows relative to league (adjust=False) or relative to what
                      the offences it faced were averaging going into those games (adjust=True)
All shrink toward a slowly decaying league average with `k` pseudo-games.
"""
from __future__ import annotations

import glob
import warnings
import numpy as np
warnings.simplefilter("ignore")
import pandas as pd

from nflmodel import props as P
from harness import DATA

POS = ("QB", "RB", "WR", "TE")
PSTATS = ["targets", "receptions", "receiving_yards", "receiving_tds", "carries", "rushing_yards",
          "rushing_tds", "skill_tds"]
TSTATS = ["attempts", "completions", "passing_yards", "passing_tds", "passing_interceptions"]


def team_table(panel: pd.DataFrame) -> pd.DataFrame:
    key = ["game_id", "team", "opponent_team", "gameday"]
    p = panel.copy()
    p["skill_tds"] = p["rushing_tds"] + p["receiving_tds"]
    T = p.groupby(key, as_index=False)[TSTATS + PSTATS].sum()
    T = T.rename(columns={c: f"all_{c}" for c in PSTATS})
    for pos in POS:
        s = p[p["position"] == pos].groupby(["game_id", "team"])[PSTATS].sum().add_prefix(f"{pos}_")
        T = T.merge(s, left_on=["game_id", "team"], right_index=True, how="left")
    T = T.fillna(0.0)
    cols = ["game_id", "team", "sacks_suffered", "passing_epa", "rushing_epa", "passing_air_yards",
            "def_qb_hits", "passing_first_downs", "rushing_first_downs"]
    ts = pd.concat([pd.read_csv(f, usecols=cols) for f in sorted(glob.glob(str(DATA / "stats_team_week_*.csv")))])
    ts["team"] = ts["team"].replace(P.TEAM_MAP)
    T = T.merge(ts, on=["game_id", "team"], how="left")
    # qb hits belong to the defence: move to the offence that suffered them
    hits = T[["game_id", "team", "def_qb_hits"]].rename(columns={"team": "opponent_team", "def_qb_hits": "hits_taken"})
    T = T.merge(hits, on=["game_id", "opponent_team"], how="left").drop(columns="def_qb_hits")
    g = pd.read_csv(DATA / "games.csv")
    g["home_team"] = g["home_team"].replace(P.TEAM_MAP)
    g["away_team"] = g["away_team"].replace(P.TEAM_MAP)
    pts = pd.concat([g[["game_id", "home_team", "home_score"]].set_axis(["game_id", "team", "points"], axis=1),
                     g[["game_id", "away_team", "away_score"]].set_axis(["game_id", "team", "points"], axis=1)])
    T = T.merge(pts, on=["game_id", "team"], how="left")
    for c in ["sacks_suffered", "passing_epa", "rushing_epa", "passing_air_yards", "hits_taken", "points",
              "passing_first_downs", "rushing_first_downs"]:
        T[c] = pd.to_numeric(T[c], errors="coerce").fillna(T[c].mean())
    T["dropbacks"] = T["attempts"] + T["sacks_suffered"]
    T["plays"] = T["dropbacks"] + T["all_carries"]
    T["first_downs"] = T["passing_first_downs"] + T["rushing_first_downs"]
    T["one"] = 1.0
    return T.sort_values(["gameday", "game_id", "team"], kind="mergesort").reset_index(drop=True)


class Ctx:
    def __init__(self, T: pd.DataFrame, decay: float = 0.85, k: float = 1.0, lg_decay: float = 0.97):
        self.T, self.decay, self.k = T, decay, k
        self.num = [c for c in T.columns if c not in ("game_id", "team", "opponent_team", "gameday", "one")]
        X = T[self.num].to_numpy(float)
        # league average per team-game from earlier dates only (decayed per date)
        days = T["gameday"].to_numpy()
        L = np.zeros_like(X)
        acc, cnt, i = np.zeros(X.shape[1]), 0.0, 0
        first = X[: 512].mean(0)
        while i < len(T):
            j = i
            while j < len(T) and days[j] == days[i]:
                j += 1
            L[i:j] = acc / cnt if cnt > 30 else first   # first weeks of the file: placeholder, long before any test season
            acc = acc * lg_decay + X[i:j].sum(0)
            cnt = cnt * lg_decay + (j - i)
            i = j
        self.L = pd.DataFrame(L, columns=self.num)
        self.X = pd.DataFrame(X, columns=self.num)
        self._off = self._sums("team", self.X)
        self._offn = self._sums("team", T[["one"]])["one"]
        self._def = self._sums("opponent_team", self.X)
        self._defn = self._sums("opponent_team", T[["one"]])["one"]
        self._exp = None

    def _sums(self, who, X):
        o = np.argsort(self.T[who].to_numpy(), kind="mergesort")
        s = P._decayed(self.T[who].to_numpy()[o], X.to_numpy(float)[o], self.decay)
        out = np.empty_like(s)
        out[o] = s
        return pd.DataFrame(out, columns=X.columns)

    def off(self, col):
        """Offence's own pre-game average, shrunk."""
        return (self._off[col] + self.k * self.L[col]) / (self._offn + self.k)

    def dfn(self, col, adjust=False):
        """Defence allowed / league (or / what the offences faced were expected to do)."""
        if not adjust:
            return ((self._def[col] + self.k * self.L[col]) / (self._defn + self.k)) / self.L[col].clip(lower=1e-6)
        if self._exp is None:
            e = pd.DataFrame({c: self.off(c) for c in self.num})
            self._exp = self._sums("opponent_team", e)
        return (self._def[col] + self.k * self.L[col]) / (self._exp[col] + self.k * self.L[col]).clip(lower=1e-6)

    def off_diff(self, col):
        return self.off(col) - self.L[col]

    def dfn_diff(self, col, adjust=False):
        """Allowed minus league per game (or minus what the offences faced were averaging); for signed stats such as EPA."""
        if not adjust:
            return (self._def[col] + self.k * self.L[col]) / (self._defn + self.k) - self.L[col]
        self.dfn(col, True)
        return (self._def[col] - self._exp[col]) / (self._defn + self.k)

    def off_rate(self, num, den):
        return (self._off[num] + self.k * self.L[num]) / (self._off[den] + self.k * self.L[den]).clip(lower=1e-6)

    def dfn_rate(self, num, den, adjust=False):
        """Rate allowed (e.g. yards per target) relative to league or to the offences' own rates."""
        return self.dfn(num, adjust) / self.dfn(den, adjust).clip(lower=1e-6)


def opp_side(T, v):
    """Re-index a per-offence series so each row gets the value of the other team in the same game."""
    t = T[["game_id", "team", "opponent_team"]].copy()
    t["v"] = np.asarray(v)
    m = t.merge(t[["game_id", "team", "v"]].rename(columns={"team": "opponent_team", "v": "o"}),
                on=["game_id", "opponent_team"], how="left")
    return m["o"].to_numpy()


def attach(panel: pd.DataFrame, T: pd.DataFrame, cols: dict) -> pd.DataFrame:
    """Add team-game columns (dict name -> Series aligned with T) to the player panel."""
    t = T[["game_id", "team"]].copy()
    for k, v in cols.items():
        t[k] = np.asarray(v)
    out = panel.merge(t, on=["game_id", "team"], how="left", validate="m:1")
    assert len(out) == len(panel)
    return out


def by_position(panel, name, fmt, positions=POS):
    """Column `name` = the column fmt.format(pos) for each player's own position."""
    v = np.full(len(panel), np.nan)
    for pos in positions:
        s = (panel["position"] == pos).to_numpy()
        v[s] = panel.loc[s, fmt.format(pos=pos)].to_numpy()
    panel[name] = v
    return panel


def game_columns(panel: pd.DataFrame) -> pd.DataFrame:
    """Kickoff-known game facts: weather (observed: upper bound), surface, rest, division, week."""
    g = pd.read_csv(DATA / "games.csv")
    g["home_team"] = g["home_team"].replace(P.TEAM_MAP)
    g["away_team"] = g["away_team"].replace(P.TEAM_MAP)
    indoor = g["roof"].isin(["dome", "closed"])
    wind = pd.to_numeric(g["wind"], errors="coerce")
    temp = pd.to_numeric(g["temp"], errors="coerce")
    g["wx_known"] = (indoor | wind.notna()).astype(float)
    g["wind_f"] = np.where(indoor, 0.0, wind)
    g["temp_f"] = np.where(indoor, 70.0, temp)
    out_w = g.loc[~indoor, "wind_f"].mean()
    g["wind_f"] = g["wind_f"].fillna(out_w)                      # unknown outdoor game -> outdoor average
    g["temp_f"] = g["temp_f"].fillna(g.loc[~indoor, "temp_f"].mean())
    g["turf"] = (~g["surface"].astype(str).str.strip().isin(["grass", "dessograss", "nan"])).astype(float)
    g["week_n"] = g["week"].astype(float)
    g["playoff"] = (g["game_type"] != "REG").astype(float)
    sides = []
    for side, opp in (("home", "away"), ("away", "home")):
        sides.append(pd.DataFrame({
            "game_id": g["game_id"], "team": g[f"{side}_team"],
            "rest": pd.to_numeric(g[f"{side}_rest"], errors="coerce").fillna(7.0),
            "opp_rest": pd.to_numeric(g[f"{opp}_rest"], errors="coerce").fillna(7.0),
            "div_game": g["div_game"].astype(float), "wind_f": g["wind_f"], "temp_f": g["temp_f"],
            "wx_known": g["wx_known"], "turf": g["turf"], "week_n": g["week_n"], "playoff": g["playoff"],
            "total_line": pd.to_numeric(g["total_line"], errors="coerce"),
        }))
    s = pd.concat(sides)
    out = panel.merge(s, on=["game_id", "team"], how="left", validate="m:1")
    out["short_week"] = (out["rest"] <= 5).astype(float)
    out["off_bye"] = (out["rest"] >= 13).astype(float)
    out["rest_diff"] = (out["rest"] - out["opp_rest"]).clip(-7, 7)
    out["late"] = (out["week_n"] >= 14).astype(float)
    out["wind_hi"] = (out["wind_f"] - 10).clip(lower=0)
    out["wind_15"] = (out["wind_f"] >= 15).astype(float)
    out["cold"] = (40 - out["temp_f"]).clip(lower=0)
    out["hot"] = (out["temp_f"] - 80).clip(lower=0)
    return out
