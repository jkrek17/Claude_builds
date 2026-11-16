"""Exploration: where do the current models go wrong around backup and injured quarterbacks?"""
import sys
from collections import defaultdict
import numpy as np, pandas as pd
from nflmodel import margin_model as mm, qb_form, totals_model as tm

D = sys.argv[1]
games = pd.read_csv(f"{D}/games.csv")
team, players, tstats = mm.load_team_stats(D), qb_form.load_player_stats(D), tm.load_team_stats(D)
pm = mm.predict_margins(games, team, players)[["game_id", "pred_margin"]]
pt = tm.predict_totals(games, tstats)[["game_id", "pred_total"]]
g = games.merge(pm, on="game_id").merge(pt, on="game_id")
g = g[g.result.notna()].sort_values(["gameday", "game_id"]).reset_index(drop=True)

ps = players.copy()
for c in ("attempts", "sacks_suffered", "carries"):
    ps[c] = pd.to_numeric(ps[c], errors="coerce").fillna(0)
ps["db"] = ps.attempts + ps.sacks_suffered
db = ps.groupby(["game_id", "player_id"]).db.sum()
tot = defaultdict(float)
# team of a qb in a game is unknown in this slim file; use starter ids from games
share = {}
gdb = ps.groupby("game_id").apply(lambda d: dict(zip(d.player_id, d.db)), include_groups=False).to_dict()

rows = []
order = defaultdict(list); career = defaultdict(int); last = {}
for r in g.itertuples():
    for side, sign in (("home", 1), ("away", -1)):
        t = getattr(r, f"{side}_team"); q = getattr(r, f"{side}_qb_id")
        opp_q = getattr(r, "away_qb_id" if side == "home" else "home_qb_id")
        if not isinstance(q, str):
            continue
        L = order[(r.season, t)]
        if q not in L: L.append(q)
        d = gdb.get(r.game_id, {})
        mine = d.get(q, 0.0)
        # other qbs who are not the opposing starter and threw: attribute by exclusion is unsafe; flag only
        pts = getattr(r, f"{side}_score")
        imp = (r.total_line + sign * r.spread_line) / 2 if pd.notna(r.total_line) and pd.notna(r.spread_line) else np.nan
        mp = (r.pred_total + sign * r.pred_margin) / 2
        rows.append(dict(game_id=r.game_id, season=r.season, week=r.week, team=t, qb=q, nth=L.index(q) + 1,
                         career=career[q], starter_db=mine, pts=pts, model_pts=mp, mkt_pts=imp,
                         m_err=sign * (r.result - r.pred_margin), l_err=sign * (r.result - r.spread_line),
                         prev_same=last.get(t) == q))
        career[q] += 1; last[t] = q
x = pd.DataFrame(rows)
x = x[(x.season >= 2010) & (x.season <= 2025)]
x["d_model"] = x.pts - x.model_pts; x["d_mkt"] = x.pts - x.mkt_pts

def show(label, m):
    s = x[m]
    print(f"{label:<46} n={len(s):5d} pts-model {s.d_model.mean():+.2f} (se {s.d_model.std()/np.sqrt(len(s)):.2f})  pts-market {s.d_mkt.mean():+.2f}  margin err model {s.m_err.mean():+.2f} line {s.l_err.mean():+.2f}")

show("all", x.nth > 0)
for n in (1, 2, 3):
    show(f"nth distinct starter = {n}{'+' if n == 3 else ''}", (x.nth >= n) if n == 3 else (x.nth == n))
for lo, hi in ((0, 0), (1, 3), (4, 15), (16, 47), (48, 999)):
    show(f"backup (nth>=2), career starts {lo}-{hi}", (x.nth >= 2) & (x.career >= lo) & (x.career <= hi))
show("first start for this team this season, nth>=2", (x.nth >= 2) & ~x.prev_same)
show("first start this season nth>=3", (x.nth >= 3) & ~x.prev_same)
show("starter threw < 10 dropbacks (left early)", x.starter_db < 10)

