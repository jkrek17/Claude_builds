"""Idea 2, leak check: the same vac feature built from (a) stat rows of this game (leaky upper bound),
(b) the game-day active list (roster status ACT, 2019+), (c) roster minus Friday Out/Doubtful (2016+),
(d) last week's lineup (earlier weeks only). Same train window for every variant in a block.
"""
import feats, roster, t
d = roster.add(feats.build())
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
def ex(v, nm=("fast",)):
    tg = [f"vac_tgt_{v}_{n}" for n in nm]; cr = [f"vac_car_{v}_{n}" for n in nm]
    return {"rec_yds": tg, "receptions": tg, "rush_yds": cr, "any_td": tg + cr}
for tf, first, vs in ((2019, 2021, ("now", "act", "fri", "prev")), (2016, 2018, ("now", "fri", "prev"))):
    print(f"\n== train from {tf}, test {first}-2026")
    T = t.Tester(d, props=PR, first=first, train_first=tf)
    for v in vs:
        T.run(f"vac {v} fast", ex(v))
        T.run(f"vac {v} fast+slow", ex(v, ("fast", "slow")))
    T.run("vac fri fast + prev fast", {k: a + b for (k, a), b in zip(ex("fri").items(), ex("prev").values())})
