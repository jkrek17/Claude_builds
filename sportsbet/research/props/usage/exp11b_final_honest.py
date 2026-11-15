"""Full recommended stack with the honest who-plays sources (roster status / Friday report) in place of
"has a stat row". Train from 2019, test 2021-2026 (act needs the INA status, which starts in 2019);
fri also on train 2016 / test 2018-2026."""
import feats, t, hist, roster
import exp11_final as F
d0 = roster.add(feats.build())
d0[["snap_last", "snap_slow"]] = d0[["snap_last", "snap_slow"]].fillna(0.0)
d = hist.rebuild(d0, off=0.7, team=0.5)
hist.add_hist(d, "vf", 0.5, off=0.7, team=0.5, qb=False)
for tf, first, vs in ((2019, 2021, ("now", "act", "fri", "prev")), (2016, 2018, ("now", "fri", "prev"))):
    print(f"\n== train from {tf}, test {first}-2026")
    T = t.Tester(d0, props=F.PR, first=first, train_first=tf)
    for v in vs:
        T.run(f"full stack, vac from '{v}'", F.spec(v, True), panel=d)
