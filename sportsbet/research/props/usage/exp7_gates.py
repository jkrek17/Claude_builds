"""Idea 7: who gets a line. Replays the live card's candidate rule (props_card.upcoming_rows: latest
appearance was for this team in one of its last 2 games this season, or last season's last 2 before the
team has played) plus the PROPS gates for every team-game 2012-2026, and compares with who really played.

"relevant" player-game = one a book would plausibly list: >=5 targets (receiving), >=8 carries (rushing).
"""
import numpy as np, pandas as pd
import feats, hist, lib
from nflmodel import props as P

d = feats.build()
o = hist.order(d)
pid = d["player_id"].to_numpy()[o]; day = d["gameday"].to_numpy()[o]
nxt = np.append(day[1:].astype(object), "9999").astype(object); nxt[np.append(pid[1:] != pid[:-1], True)] = "9999"
inv = np.empty(len(o), dtype=int); inv[o] = np.arange(len(o))
d["next_day"] = nxt[inv]
# post-game gate values (what the card sees for a player who then does not play)
for c in ("targets", "carries", "touches"):
    n = P.SHRINK / d["new_slow"] - P.SHRINK
    d[f"{c}_post"] = (d[f"{c}_slow"] * (n + P.SHRINK) * P.SLOW + d[c]) / (n * P.SLOW + 1 + P.SHRINK)
d["snap_post"] = d["snap_pct"]
tg = d[["game_id", "team", "season", "gameday"]].drop_duplicates(["game_id", "team"]).sort_values(["team", "gameday"]).reset_index(drop=True)
rec = []
for team, g in tg.groupby("team"):
    gid = g["game_id"].tolist(); sea = g["season"].tolist(); dy = g["gameday"].tolist()
    for i in range(len(gid)):
        cur = [j for j in range(i) if sea[j] == sea[i]]
        prev = cur if cur else [j for j in range(i) if sea[j] == sea[i] - 1]
        for j in prev[-2:]:
            rec.append((gid[i], team, dy[i], sea[i], gid[j]))
rec = pd.DataFrame(rec, columns=["game_id", "team", "gameday", "season", "src_game"])
src = d[["game_id", "team", "player_id", "position", "next_day", "targets_post", "carries_post", "touches_post", "snap_post", "games_played"]].rename(columns={"game_id": "src_game"})
cand = rec.merge(src, on=["src_game", "team"])
cand = cand[cand["next_day"] >= cand["gameday"]].drop_duplicates(["game_id", "team", "player_id"], keep="last")
act = d[["game_id", "team", "player_id", "targets", "carries", "touches", "snap_pct", "targets_slow", "carries_slow", "touches_slow",
         "targets_fast", "carries_fast", "snap_last", "draft_val", "rookie", "games_played", "position", "season", "tgame_no"]].rename(columns={"games_played": "gp", "position": "pos"})
m = cand.merge(act, on=["game_id", "team", "player_id"], how="outer", indicator=True, suffixes=("", "_a"))
m["season"] = m["season"].fillna(m["season_a"]); m["position"] = m["position"].fillna(m["pos"])
m = m[m["season"] >= 2012]
m["cand"] = m["_merge"] != "right_only"; m["played"] = m["_merge"] != "left_only"
print(f"candidate player-games {int(m.cand.sum())}, of which had a stat row {m.loc[m.cand, 'played'].mean():.3f}")
print(f"player-games with a stat row {int(m.played.sum())}, of which were candidates {m.loc[m.played, 'cand'].mean():.3f}")
for name, gate, minv, stat, rel, poss in (("receiving (rec_yds/receptions)", "targets", 2.5, "targets", 5, ("RB", "WR", "TE")),
                                          ("rushing (rush_yds)", "carries", 5.0, "carries", 8, ("QB", "RB")),
                                          ("anytime TD", "touches", 4.0, "touches", 8, ("QB", "RB", "WR", "TE"))):
    s = m[m["position"].isin(poss)]
    g_card = s["cand"] & (s[f"{gate}_post"] >= minv)            # what the live card would list
    g_bt = s["played"] & (s[f"{gate}_slow"] >= minv)            # what the backtest scores
    relv = s["played"] & (s[stat] >= rel)
    print(f"\n{name}: card lines {int(g_card.sum())}; backtest rows {int(g_bt.sum())}")
    print(f"  card lines for players with NO stat row that game (inactive/unused): {(g_card & ~s.played).sum() / g_card.sum():.3f}")
    print(f"  card lines where he played but had {stat} <= 1: {(g_card & s.played & (s[stat] <= 1)).sum() / g_card.sum():.3f}")
    print(f"  relevant player-games ({stat} >= {rel}): {int(relv.sum())}; on the card {(relv & g_card).sum() / relv.sum():.3f}; "
          f"missed because not a candidate {(relv & ~s.cand).sum() / relv.sum():.3f}; candidate but below the gate {(relv & s.cand & ~g_card).sum() / relv.sum():.3f}")
    miss = relv & ~g_card
    mm = s[miss]
    print(f"  misses by type: rookie season {mm['rookie'].mean():.2f}; career games <4 {(mm['gp'] < 4).mean():.2f}; team's first game of season {(mm['tgame_no'] == 0).mean():.2f}; not a candidate {(~mm['cand']).mean():.2f}")
    if gate != "touches":
        print("  alternative gates among players who played (recall of relevant / share of listed with stat<=1 / lines):")
        fast = s[f"{gate}_fast"]; slow = s[f"{gate}_slow"]
        alts = {f"baseline {gate}_slow>={minv}": slow >= minv,
                f"{gate}_slow>={minv * 0.8:.1f}": slow >= minv * 0.8,
                f"{gate}_slow>={minv * 0.6:.1f}": slow >= minv * 0.6,
                f"max(slow,fast)>={minv}": np.maximum(slow, fast) >= minv,
                }
        if gate == "targets":
            alts["slow>=2.5 or (rookie & draft pick<=64 & fast>=1.5)"] = (slow >= minv) | ((s["rookie"] == 1) & (s["draft_val"] >= 1 - np.log(64) / np.log(262)) & (fast >= 1.5))
            alts["slow>=2.5 or (snap_last>=.6 & fast>=2.5) [2013+]"] = (slow >= minv) | ((s["snap_last"] >= 0.6) & (fast >= minv))
        else:
            alts["slow>=5 or (rookie & pick<=100 & fast>=3)"] = (slow >= minv) | ((s["rookie"] == 1) & (s["draft_val"] >= 1 - np.log(100) / np.log(262)) & (fast >= 3))
        for k, g in alts.items():
            g = g & s["played"]
            print(f"    {k:<52} recall {(g & relv).sum() / relv.sum():.3f}   dud share {(g & (s[stat] <= 1)).sum() / g.sum():.3f}   lines {int(g.sum())}")

# ---------------------------------------------------------------- part 2: a gate on the unshrunk recent average
print("\n== gate on the unshrunk fast average (targets_fast / (1 - new_fast)), players who played")
import t
d["tgt_raw_fast"] = d["targets_fast"] / (1 - d["new_fast"]).clip(lower=1e-9) * (d["new_fast"] < 1)
d["car_raw_fast"] = d["carries_fast"] / (1 - d["new_fast"]).clip(lower=1e-9) * (d["new_fast"] < 1)
te = d["season"] >= 2012
for gate, minv, raw, stat, rel, poss, props in (("targets_slow", 2.5, "tgt_raw_fast", "targets", 5, ("RB", "WR", "TE"), ["rec_yds", "receptions"]),
                                                ("carries_slow", 5.0, "car_raw_fast", "carries", 8, ("QB", "RB"), ["rush_yds"])):
    pos = d["position"].isin(poss)
    relv = pos & te & (d[stat] >= rel)
    for thr in ((3.5, 4.0, 4.5, 5.0) if stat == "targets" else (6.0, 7.0, 8.0, 9.0)):
        g = pos & ((d[gate] >= minv) | (d[raw] >= thr))
        print(f"  {gate}>={minv} or raw fast avg>={thr}: recall {(g & relv).sum() / relv.sum():.3f}  dud share {(g & te & (d[stat] <= 1)).sum() / (g & te).sum():.3f}  lines {int((g & te).sum())}")
    thr = 4.0 if stat == "targets" else 7.0
    d[f"elig2_{props[0]}"] = (pos & ((d[gate] >= minv) | (d[raw] >= thr))).to_numpy()
    for p in props:
        d[f"elig2_{p}"] = d[f"elig2_{props[0]}"]
    base = lib.evaluate(d, props=props)
    wide = lib.evaluate(d, props=props, elig={p: f"elig2_{p}" for p in props})
    print(f"  model refitted on the wider gate (raw fast >= {thr}):")
    lib.compare(base, wide, "  same rows as baseline: wide-gate fit vs baseline fit")
    add = wide[~wide.set_index(["prop", "idx"]).index.isin(base.set_index(["prop", "idx"]).index)]
    old = wide[wide.set_index(["prop", "idx"]).index.isin(base.set_index(["prop", "idx"]).index)]
    for nm, x in (("added rows", add), ("baseline rows", old)):
        s = x.assign(ae=(x.y - x.mu).abs(), bias=x.mu - x.y).groupby("prop").agg(n=("y", "size"), mean_y=("y", "mean"), mae=("ae", "mean"), crps=("crps", "mean"), bias=("bias", "mean"))
        print(f"  {nm}:\n{s.round(3).to_string()}")

# ---------------------------------------------------------------- part 3: can roster status remove the no-show lines?
print("\n== card lines for players with no stat row: how many does roster / injury status catch (2019+)")
import roster as R
games = pd.read_csv(lib.CACHE / "games.csv", usecols=["game_id", "season", "week"])
ros = []
for s_ in range(2019, int(d["season"].max()) + 1):
    r = pd.read_csv(lib.DATA / f"roster_weekly_{s_}.csv", low_memory=False, usecols=["season", "team", "status", "gsis_id", "week"])
    i = pd.read_csv(lib.DATA / f"injuries_{s_}.csv", usecols=["season", "team", "week", "gsis_id", "report_status"])
    r = r.merge(i.dropna(subset=["report_status"]).drop_duplicates(["season", "week", "gsis_id"])[["season", "week", "gsis_id", "report_status"]], on=["season", "week", "gsis_id"], how="left")
    ros.append(r)
ros = pd.concat(ros).drop_duplicates(["season", "week", "gsis_id"], keep="last").rename(columns={"gsis_id": "player_id"})
c = m[(m["season"] >= 2019) & m["cand"] & m["position"].isin(("RB", "WR", "TE")) & (m["targets_post"] >= 2.5)].merge(games, on="game_id", suffixes=("", "_g"))
c = c.merge(ros[["season", "week", "player_id", "status", "report_status"]], left_on=["season_g", "week", "player_id"], right_on=["season", "week", "player_id"], how="left", suffixes=("", "_r"))
c["status"] = c["status"].fillna("not on roster file")
ns = c[~c["played"]]
print(f"  receiving card lines 2019+: {len(c)}, no stat row: {len(ns)} ({len(ns) / len(c):.3f})")
print("  roster status of the no-shows:", (ns["status"].value_counts() / len(ns)).round(3).to_dict())
fri = ~ns["status"].isin(["ACT", "INA"]) | ns["report_status"].isin(["Out", "Doubtful"])
print(f"  caught by Friday info (not ACT/INA on the roster, or listed Out/Doubtful): {fri.mean():.3f}; by the inactives list (status != ACT): {(ns['status'] != 'ACT').mean():.3f}")
pl = c[c["played"]]
print(f"  players who DID play wrongly dropped: Friday rule {(~pl['status'].isin(['ACT', 'INA']) | pl['report_status'].isin(['Out', 'Doubtful'])).mean():.4f}; inactives rule {(pl['status'] != 'ACT').mean():.4f}")
