"""Idea 4: the announced starter's passing form applied to his receivers (and QB-change effects).

qb_pyds / qb_patt / qb_pcmp = starter's slow per-start average + newcomer default (200 yds, 31 att, 19 cmp) x qbnew_slow
qb_*_h{fast,slow} = decayed mean of that quantity over the RECEIVER's earlier games (the QBs he played with)
qb_*_d* = today's starter minus that history; qb_unfam = share of his fast-decayed history NOT with today's starter
qb_changed = team's starter differs from its previous game. All known pre-game from the announced starter.
"""
import feats, t
d = feats.build()
PR = ["rec_yds", "receptions", "any_td", "rush_yds"]
T = t.Tester(d, props=PR)
def r(cols): return {"rec_yds": cols, "receptions": cols, "any_td": cols, "rush_yds": cols}
T.run("qb_pyds", r(["qb_pyds"]))
T.run("qb_pyds + qb_patt", r(["qb_pyds", "qb_patt"]))
T.run("raw slow yds/att + qbnew", r(["qb_passing_yards_slow", "qb_attempts_slow", "qb_qbnew_slow"]))
T.run("qb_pyds_dfast", r(["qb_pyds_dfast"]))
T.run("qb_pyds_dslow", r(["qb_pyds_dslow"]))
T.run("qb_pyds_dfast + dslow", r(["qb_pyds_dfast", "qb_pyds_dslow"]))
T.run("pyds,patt: dfast + dslow", r(["qb_pyds_dfast", "qb_pyds_dslow", "qb_patt_dfast", "qb_patt_dslow"]))
T.run("pcmp,pyds: dfast + dslow", r(["qb_pyds_dfast", "qb_pyds_dslow", "qb_pcmp_dfast", "qb_pcmp_dslow"]))
T.run("qb_ypa + ypa_dfast", r(["qb_ypa", "qb_ypa_dfast"]))
T.run("qb_changed", r(["qb_changed"]))
T.run("qb_unfam", r(["qb_unfam"]))
T.run("qb_pyds_dx (delta x unfamiliar)", r(["qb_pyds_dx"]))
T.run("level + deltas + unfam", r(["qb_pyds", "qb_patt", "qb_pyds_dfast", "qb_pyds_dslow", "qb_unfam"]))
T.run("qb fast form (yds_fast, new_fast)", r(["qb_passing_yards_fast", "qb_qbnew_fast", "qb_pyds"]))
m = (d["qb_changed"] == 1).to_numpy()
print("\nrows where the starter differs from the team's previous game only:")
T.run("  pyds dfast+dslow", r(["qb_pyds_dfast", "qb_pyds_dslow"]), mask=m)
T.run("  level + deltas + unfam", r(["qb_pyds", "qb_patt", "qb_pyds_dfast", "qb_pyds_dslow", "qb_unfam"]), mask=m)
