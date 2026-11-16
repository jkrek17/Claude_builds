"""Three-team teasers from the classic legs: python3 research/teasers/three_team.py GAMES_CSV

Reported, not chosen (see RULEBOOK.md): same legs, same closing lines, three legs instead of two.
A pushed leg drops the teaser to a two-team teaser, paid here at -134; two pushes refund.
"""
from __future__ import annotations

import itertools
import sys

import numpy as np
import pandas as pd

from nflmodel import teasers as tz

PRICES = (130, 140, 150, 160, 180)
TWO_TEAM = -134
ERAS = ((2003, 2014), (2015, 2019), (2020, 2022), (2023, 2026), (2003, 2026))


def settle3(res, price):
    live = [r for r in res if r != 0]
    if any(r < 0 for r in live):
        return -1.0
    if len(live) == 3:
        return tz.payout(price)
    return tz.payout(TWO_TEAM) if len(live) == 2 else 0.0


def triples(d, live: bool):
    out = []
    for (s, w), x in d.groupby(["season", "week"]):
        if live:  # one teaser a week: the three best-placed legs, one per game, nearest the middle of the range first
            x = x.sort_values(["line", "game_id"], ascending=[False, True]).drop_duplicates("game_id")
            sets = [tuple(x.res.iloc[:3])] if len(x) >= 3 else []
        else:
            v = x[["game_id", "res"]].to_numpy()
            sets = [(a[1], b[1], c[1]) for a, b, c in itertools.combinations(v, 3) if len({a[0], b[0], c[0]}) == 3]
        out += [(s, w, t) for t in sets]
    return out


def roi(tr, price, boot=2000):
    profit = np.array([settle3(t, price) for _, _, t in tr])
    key = np.array([s * 100 + w for s, w, _ in tr])
    weeks, inv = np.unique(key, return_inverse=True)
    tot, cnt = np.bincount(inv, profit), np.bincount(inv)
    pick = np.random.default_rng(7).integers(0, len(weeks), size=(boot, len(weeks)))
    return profit.mean(), (tot[pick].sum(1) / cnt[pick].sum(1)).std()


def main(games_csv: str) -> None:
    games = pd.read_csv(games_csv)
    legs = tz.qualifying(tz.team_rows(games[(games.season >= 2003) & games.result.notna()]))
    r = (legs.res > 0).sum() / (legs.res != 0).sum()
    print(f"Leg win rate {r:.1%}; three independent legs all win {r ** 3:.1%}")
    print("Each leg must win: " + ", ".join(f"{p:+d} {tz.break_even_leg(p, 3):.1%}" for p in PRICES))
    for label, live in (("ALL SAME-WEEK TRIPLES (three different games)", False),
                        ("ONE TEASER A WEEK (first three qualifying games by line)", True)):
        print(f"\n{label}\n  {'seasons':<10} {'teasers':>7} {'all 3 won':>9}  " + "  ".join(f"{p:>+13d}" for p in PRICES))
        for span in ERAS:
            tr = triples(legs[legs.season.between(*span)], live)
            if not tr:
                continue
            won = np.mean([all(x > 0 for x in t) for _, _, t in tr])
            cells = ["{:+6.1%} ±{:4.1%}".format(*roi(tr, p)) for p in PRICES]
            print(f"  {span[0]}-{span[1]:<5} {len(tr):>7} {won:>9.1%}  " + "  ".join(f"{c:>13}" for c in cells))
    wk = legs.groupby(["season", "week"]).game_id.nunique()
    print(f"\nWeeks with at least 3 qualifying games: {(wk >= 3).sum()} of {games[(games.season >= 2003) & games.result.notna()].groupby(['season', 'week']).ngroups}"
          f" ({(wk >= 3).groupby('season').sum().mean():.1f} a season); at least 2: {(wk >= 2).sum()}")


if __name__ == "__main__":
    main(sys.argv[1])
