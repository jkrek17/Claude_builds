"""Idea 6: air yards / target depth for receiving yards (air yards exist from 2009; rec rows only pass the gate from 2009).

air_* = decayed air yards per game (shrink 3); adot_slow = decayed air/targets shrunk to 8.0 over 20 targets;
ypt_slow = yards per target shrunk to 7.5 over 20; catch_slow = catch rate shrunk to .62 over 20;
air_share_slow = share of team air yards; exp_air = exp_targets x adot_slow; exp_ryds_ypt = exp_targets x ypt_slow.
"""
import feats, t
d = feats.build()
PR = ["rec_yds", "receptions", "any_td"]
T = t.Tester(d, props=PR)
def r(cols): return {p: cols for p in PR}
T.run("air_slow", r(["air_slow"]))
T.run("air_fast + air_slow", r(["air_fast", "air_slow"]))
T.run("adot_slow", r(["adot_slow"]))
T.run("exp_air", r(["exp_air"]))
T.run("air f+s + adot + exp_air", r(["air_fast", "air_slow", "adot_slow", "exp_air"]))
T.run("air_share_slow", r(["air_share_slow"]))
T.run("ypt_slow + catch_slow", r(["ypt_slow", "catch_slow"]))
T.run("exp_ryds_ypt", r(["exp_ryds_ypt"]))
T.run("yac fast+slow", r(["yac_fast", "yac_slow"]))
T.run("rec first downs fast+slow", r(["rec_fd_fast", "rec_fd_slow"]))
T.run("everything", r(["air_fast", "air_slow", "adot_slow", "exp_air", "air_share_slow", "ypt_slow", "catch_slow", "yac_slow", "rec_fd_slow"]))
T2 = t.Tester(d, props=["rush_yds", "any_td"])
T2.run("rush first downs fast+slow", {"rush_yds": ["rush_fd_fast", "rush_fd_slow"], "any_td": ["rush_fd_slow", "rec_fd_slow"]})
