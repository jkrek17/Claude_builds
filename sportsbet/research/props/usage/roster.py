"""Honest "who is expected to play" versions of the competition feature, from nflverse weekly rosters
(status; game-day INA exists from 2019, RES reliable from 2016) and the Friday injury report.

  act: roster status ACT in the week of the game            (2019+: this IS the inactives list)
  fri: roster status ACT or INA, minus players the injury report lists Out or Doubtful (2016+; known Friday)
For each version: comp_{tgt,car}_{v} = sum over those teammates of their latest slow share (state after
their last game), minus the player's own; h{fast,slow} = decayed average of it over his earlier games;
vac_{kind}_{v}_{fast,slow} = hist - now.
"""
import numpy as np, pandas as pd
import lib, feats, hist
from nflmodel import props as P

FIRST = 2016


def add(d):
    games = pd.read_csv(lib.CACHE / "games.csv", usecols=["game_id", "season", "week", "gameday", "home_team", "away_team"])
    gl = pd.concat([games.rename(columns={"home_team": "team"}).drop(columns="away_team"),
                    games.rename(columns={"away_team": "team"}).drop(columns="home_team")])
    gl["team"] = gl["team"].replace(P.TEAM_MAP)
    ros, inj = [], []
    for s in range(FIRST, int(d["season"].max()) + 1):
        r = pd.read_csv(lib.DATA / f"roster_weekly_{s}.csv", low_memory=False,
                        usecols=["season", "team", "position", "status", "gsis_id", "week"])
        ros.append(r[r["position"].isin(P.POSITIONS) & r["status"].isin(["ACT", "INA"])])
        i = pd.read_csv(lib.DATA / f"injuries_{s}.csv", usecols=["season", "team", "week", "gsis_id", "report_status"])
        inj.append(i[i["report_status"].isin(["Out", "Doubtful"])])
    ros = pd.concat(ros).dropna(subset=["gsis_id"]); inj = pd.concat(inj).dropna(subset=["gsis_id"])
    for t in (ros, inj):
        t["team"] = t["team"].replace(P.TEAM_MAP | {"LAR": "LA"})
    ros = ros.drop_duplicates(["season", "week", "team", "gsis_id"])
    ros = ros.merge(inj.drop_duplicates(["season", "week", "team", "gsis_id"]), on=["season", "week", "team", "gsis_id"], how="left")
    ros = ros.merge(gl, on=["season", "week", "team"], how="inner").rename(columns={"gsis_id": "player_id"})
    ros["act"] = (ros["status"] == "ACT").astype(float)
    ros["fri"] = ros["report_status"].isna().astype(float)
    # latest post-game slow shares of every rostered player, strictly before the game date
    key = ["game_id", "team"]
    tt = d.groupby(key)["targets"].transform("sum").to_numpy(float)
    tc = d.groupby(key)["carries"].transform("sum").to_numpy(float)
    x = np.column_stack([d["targets"].to_numpy(float), tt, d["carries"].to_numpy(float), tc])
    post = feats._sorted_apply(d, x, P.SLOW) * P.SLOW + x
    st = pd.DataFrame({"player_id": d["player_id"], "day": pd.to_datetime(d["gameday"]),
                       "tgt": post[:, 0] / (post[:, 1] + P.SHRINK * 34.0),
                       "car": post[:, 2] / (post[:, 3] + P.SHRINK * 27.0)}).sort_values("day")
    ros["day"] = pd.to_datetime(ros["gameday"])
    ros = pd.merge_asof(ros.sort_values("day"), st, on="day", by="player_id", allow_exact_matches=False)
    ros[["tgt", "car"]] = ros[["tgt", "car"]].fillna(0.0)
    agg = {}
    for v in ("act", "fri"):
        for k in ("tgt", "car"):
            ros[f"_{k}_{v}"] = ros[k] * ros[v]
            agg[f"tot_{k}_{v}"] = (f"_{k}_{v}", "sum")
    tot = ros.groupby(key).agg(**agg).reset_index()
    d = d.merge(tot, on=key, how="left")
    d = d.merge(ros[key + ["player_id", "act", "fri", "status", "report_status"]].rename(
        columns={"act": "own_act", "fri": "own_fri", "status": "ros_status"}), on=key + ["player_id"], how="left")
    d["has_roster"] = d["tot_tgt_act"].notna() & (d["season"] >= FIRST)
    for v in ("act", "fri"):
        for k, own in (("tgt", "tgt_share_slow"), ("car", "car_share_slow")):
            c = d[f"tot_{k}_{v}"] - d[own] * d[f"own_{v}"].fillna(0.0)
            d[f"comp_{k}_{v}"] = c
            for nm, dec in (("fast", P.FAST), ("slow", P.SLOW)):
                a, n = feats.dec_avg(d, [f"comp_{k}_{v}"], dec, valid=d["has_roster"].to_numpy())
                h = np.where(n > 0, a[:, 0], c)
                d[f"vac_{k}_{v}_{nm}"] = (h - c).fillna(0.0)
    return d.sort_values(["gameday", "game_id", "player_id"], kind="mergesort").reset_index(drop=True)


if __name__ == "__main__":
    d = add(feats.build())
    s = d[d.season >= FIRST]
    print("rows with roster match", s["has_roster"].mean(), "own listed", s["own_act"].notna().mean())
    print(s.groupby("season")[["own_act", "own_fri"]].mean().round(3).T)
    print(s[["comp_tgt_now", "comp_tgt_act", "comp_tgt_fri", "vac_tgt_now_fast", "vac_tgt_act_fast", "vac_tgt_fri_fast"]].describe().round(3).T)
    print(s[s.season >= 2019][["vac_tgt_now_fast", "vac_tgt_act_fast", "vac_tgt_fri_fast", "vac_tgt_prev_fast"]].corr().round(2))
