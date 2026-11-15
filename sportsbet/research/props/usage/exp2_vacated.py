"""Idea 2: vacated volume / competition for targets and carries.

comp_X_now  = sum of pre-game slow shares of the other teammates who have a row in this game [LIVE: inactives]
comp_X_prev = same sum over teammates who played the team's previous game this season (earlier weeks only)
comp_X_h*   = decayed average of comp_X_now over the player's own earlier games
vac = hist - now (positive: less competition than he is used to); ratio = (own+hist+.05)/(own+now+.05) clipped [.5,2]
"""
import feats, t
d = feats.build()
REC = ("rec_yds", "receptions"); RUSH = ("rush_yds",)
T = t.Tester(d, props=["rush_yds", "rec_yds", "receptions", "any_td"])
def ex(tg, cr):
    return {"rec_yds": tg, "receptions": tg, "rush_yds": cr, "any_td": tg + cr}
for ver in ("now", "prev"):
    tag = "[LIVE]" if ver == "now" else "[prior]"
    T.run(f"{tag} vac fast", ex([f"vac_tgt_{ver}_fast"], [f"vac_car_{ver}_fast"]))
    T.run(f"{tag} vac slow", ex([f"vac_tgt_{ver}_slow"], [f"vac_car_{ver}_slow"]))
    T.run(f"{tag} vac fast+slow", ex([f"vac_tgt_{ver}_fast", f"vac_tgt_{ver}_slow"], [f"vac_car_{ver}_fast", f"vac_car_{ver}_slow"]))
    T.run(f"{tag} comp + hfast + hslow", ex([f"comp_tgt_{ver}", "comp_tgt_hfast", "comp_tgt_hslow"], [f"comp_car_{ver}", "comp_car_hfast", "comp_car_hslow"]))
    T.run(f"{tag} ratio fast+slow", ex([f"ratio_tgt_{ver}_fast", f"ratio_tgt_{ver}_slow"], [f"ratio_car_{ver}_fast", f"ratio_car_{ver}_slow"]))
    T.run(f"{tag} exp x ratio, share x ratio", ex([f"exp_targets_{ver}", f"tgt_share_fast_{ver}"], [f"exp_carries_{ver}", f"car_share_fast_{ver}"]))
    T.run(f"{tag} vac f+s + exp x ratio", ex([f"vac_tgt_{ver}_fast", f"vac_tgt_{ver}_slow", f"exp_targets_{ver}", f"tgt_share_fast_{ver}"],
                                            [f"vac_car_{ver}_fast", f"vac_car_{ver}_slow", f"exp_carries_{ver}", f"car_share_fast_{ver}"]))
T.run("[LIVE+prior] vac now f+s, prev f", ex(["vac_tgt_now_fast", "vac_tgt_now_slow", "vac_tgt_prev_fast"], ["vac_car_now_fast", "vac_car_now_slow", "vac_car_prev_fast"]))
