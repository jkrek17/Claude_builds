"""Own ideas: the player's own absence and season position (all from earlier weeks).

missed_games = team games this season between his last appearance for this team and today (0..8);
late_start = team games already played when he first appears this season; first_of_season = his first game
of a season; new_team = first game after a team change; week1 = team's first game of the season.
Also the offseason / team-change memory decay applied to non-QB history only.
"""
import feats, t, hist
from nflmodel import props as P
d = feats.build()
PR = ["rush_yds", "rec_yds", "receptions", "any_td"]
T = t.Tester(d, props=PR)
def r(cols): return {p: cols for p in PR}
T.run("missed_games", r(["missed_games"]))
T.run("missed_any", r(["missed_any"]))
T.run("missed_games + missed_any", r(["missed_games", "missed_any"]))
T.run("late_start", r(["late_start"]))
T.run("first_of_season", r(["first_of_season"]))
T.run("new_team", r(["new_team"]))
T.run("week1", r(["week1"]))
T.run("missed+late+first+new_team", r(["missed_games", "missed_any", "late_start", "first_of_season", "new_team"]))
T.run("exp_years", r(["exp_years"]))
print("\nmemory decay at season / team change, non-QB props only (QB passing memory untouched):")
for off, tm in ((0.5, 1.0), (0.6, 1.0), (0.7, 1.0), (0.85, 1.0), (1.0, 0.5), (0.7, 0.5), (0.7, 0.7), (0.6, 0.5), (0.85, 0.7)):
    T.run(f"offseason x{off}, team change x{tm}", panel=hist.rebuild(d, off=off, team=tm))
