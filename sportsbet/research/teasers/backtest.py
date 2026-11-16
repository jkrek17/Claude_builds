"""Teaser backtest: python3 research/teasers/backtest.py DATA_DIR

DATA_DIR holds the nflverse files the nflmodel command caches (~/.cache/nflmodel). The rules being
tested are in RULEBOOK.md, which was committed before this was first run.
"""
from __future__ import annotations

import itertools
import sys

import numpy as np
import pandas as pd

from nflmodel import teasers as tz
from nflmodel.card import predict_all

PRICES = (-110, -120, -125, -130, -134)
DESIGN, JUDGE = (2010, 2019), (2020, 2026)
ERAS = ((2003, 2014), (2015, 2019), (2020, 2022), (2023, 2026), (2003, 2026))
CANDIDATES = {"M1 gap >= 0": lambda g: g >= 0, "M2 gap >= 1": lambda g: g >= 1,
              "M3 gap >= 2": lambda g: g >= 2, "M4 gap > -2": lambda g: g > -2}


def rec(d, col="res"):
    w, l, p = int((d[col] > 0).sum()), int((d[col] < 0).sum()), int((d[col] == 0).sum())
    n = w + l
    if n == 0:
        return "no legs"
    r = w / n
    return f"{w}-{l}" + (f"-{p}" if p else "") + f"  {r:.1%} (±{np.sqrt(r * (1 - r) / n):.1%})"


def rate_se(d):
    d = d[d.res != 0]
    n = len(d)
    r = (d.res > 0).mean() if n else np.nan
    return r, (np.sqrt(r * (1 - r) / n) if n else np.nan), n


def between(d, span):
    return d[d.season.between(*span)]


def all_pairs(d):
    """Every same-week pair of legs from different games: (season, week, res_a, res_b)."""
    out = []
    for (s, w), x in d.groupby(["season", "week"]):
        x = x[["game_id", "res"]].to_numpy()
        for a, b in itertools.combinations(range(len(x)), 2):
            if x[a][0] != x[b][0]:
                out.append((s, w, x[a][1], x[b][1]))
    return pd.DataFrame(out, columns=["season", "week", "a", "b"])


def live_pairs(d):
    out = []
    for (s, w), x in d.groupby(["season", "week"]):
        for a, b in tz.pair_up(x):
            out.append((s, w, x.loc[a, "res"], x.loc[b, "res"]))
    return pd.DataFrame(out, columns=["season", "week", "a", "b"])


def roi(pairs, price, boot=2000, seed=7):
    """Return per teaser and a bootstrap SE that resamples whole weeks."""
    if pairs.empty:
        return np.nan, np.nan
    profit = np.array([tz.settle(a, b, price) for a, b in zip(pairs.a, pairs.b)])
    key = pairs.season.to_numpy() * 100 + pairs.week.to_numpy()
    weeks, inv = np.unique(key, return_inverse=True)
    tot, cnt = np.bincount(inv, profit), np.bincount(inv)
    rng = np.random.default_rng(seed)
    pick = rng.integers(0, len(weeks), size=(boot, len(weeks)))
    return profit.mean(), (tot[pick].sum(1) / cnt[pick].sum(1)).std()


def pair_table(label, legs, maker):
    print(f"\n{label}")
    print(f"  {'seasons':<10} {'teasers':>7} {'won':>6}  " + "  ".join(f"{p:>12}" for p in PRICES))
    for span in ERAS:
        p = maker(between(legs, span))
        if p.empty:
            continue
        live = p[(p.a != 0) & (p.b != 0)]
        won = ((live.a > 0) & (live.b > 0)).mean()
        cells = []
        for price in PRICES:
            m, se = roi(p, price)
            cells.append(f"{m:+6.1%} ±{se:4.1%}")
        print(f"  {span[0]}-{span[1]:<5} {len(p):>7} {won:>6.1%}  " + "  ".join(f"{c:>12}" for c in cells))


def main(data_dir: str) -> None:
    games = pd.read_csv(f"{data_dir}/games.csv")
    preds = predict_all(games, data_dir)
    preds = preds[(preds.season >= 2003) & preds.result.notna()]
    rows = tz.team_rows(preds)
    legs = tz.qualifying(rows)
    last = preds[preds.season == preds.season.max()]
    print(f"Games with a closing spread, 2003 to {int(last.season.max())} week {int(last.week.max())}: {len(preds)}")
    print("Each leg must win: " + ", ".join(f"{p} {tz.break_even_leg(p):.1%}" for p in PRICES))

    print("\nBASE RULE: classic legs (dog +1.5 to +2.5, fav -7.5 to -8.5), 6 points")
    for span in ERAS:
        d = between(legs, span)
        print(f"  {span[0]}-{span[1]}  all {rec(d)}   dogs {rec(d[d.kind == 'dog'])}   favs {rec(d[d.kind == 'fav'])}")
    print("  by season:")
    for s in sorted(legs.season.unique()):
        print(f"    {s}  {rec(legs[legs.season == s])}")
    r, se, n = rate_se(legs)
    print(f"  all seasons: {r:.2%} (±{se:.2%}), fair price {tz.price_needed(r):+.0f}; "
          f"one SE either way {tz.price_needed(r - se):+.0f} to {tz.price_needed(r + se):+.0f}")

    pair_table("ALL SAME-WEEK PAIRS (return per teaser, ± bootstrap SE over weeks)", legs, all_pairs)

    print("\nMODEL FILTER (gap = model margin for the team minus the margin its spread implies)")
    m = legs[legs.season >= tz.MODEL_FIRST].copy()
    print(f"  gap on qualifying legs: mean {m.gap.mean():+.2f}, SD {m.gap.std():.2f}, share >= 0: {(m.gap >= 0).mean():.1%}")
    adopted = []
    for name, keep in CANDIDATES.items():
        k = keep(m.gap)
        print(f"  {name}")
        halves = []
        for span in ((2010, 2014), (2015, 2019), DESIGN):
            a, b = between(m[k], span), between(m[~k], span)
            (ra, sa, na), (rb, sb, nb) = rate_se(a), rate_se(b)
            z = (ra - rb) / np.hypot(sa, sb)
            halves.append((ra > rb, z, na / (na + nb), ra))
            print(f"    {span[0]}-{span[1]}  kept {rec(a)}   dropped {rec(b)}   difference {ra - rb:+.1%} (z {z:+.2f})")
        ok = halves[0][0] and halves[1][0] and halves[2][2] >= 0.40 and halves[2][1] >= 1.5
        print(f"    keeps {halves[2][2]:.0%} of legs  ->  {'PASSES' if ok else 'fails'} the adoption test")
        if ok:
            adopted.append((halves[2][3], name))
    choice = max(adopted)[1] if adopted else None
    print(f"  ADOPTED ON DESIGN SEASONS: {choice or 'none (base rule, no model filter)'}")

    print(f"\nJUDGING SEASONS {JUDGE[0]}-{JUDGE[1]} (scored once)")
    j = between(m, JUDGE)
    print(f"  base rule  {rec(j)}")
    for name, keep in CANDIDATES.items():
        k = keep(j.gap)
        (ra, sa, _), (rb, sb, _) = rate_se(j[k]), rate_se(j[~k])
        tag = "  <- adopted" if name == choice else ""
        print(f"  {name}  kept {rec(j[k])}   dropped {rec(j[~k])}   difference {ra - rb:+.1%} (z {(ra - rb) / np.hypot(sa, sb):+.2f}){tag}")
    c = np.corrcoef(m.gap, (m.margin + m.line))[0, 1]
    print(f"  correlation of gap with (margin + line) on qualifying legs 2010 on: {c:+.3f} (n={len(m)})")
    allrows = rows[rows.season >= tz.MODEL_FIRST]
    print("  win rate of ANY 6-point teased leg by gap, 2010 on (does the model help outside the classic range?):")
    for lo, hi in ((-99, -3), (-3, -1), (-1, 1), (1, 3), (3, 99)):
        d = allrows[(allrows.gap >= lo) & (allrows.gap < hi)]
        print(f"    gap {lo:>3} to {hi:<3} {rec(d)}")

    use = (lambda d: d[CANDIDATES[choice](d.gap)]) if choice else (lambda d: d)
    pair_table("LIVE RULE, base legs ranked by gap, at most 2 teasers a week (2010 on has the model; earlier ranks by line)",
               legs, live_pairs)
    if choice:
        pair_table(f"LIVE RULE with the adopted filter {choice}", use(m), live_pairs)

    print("\nPRICE GATE, walk-forward: the past record each season started with and the price it allowed")
    for s in range(2015, int(rows.season.max()) + 1):
        r, n = tz.past_rate(rows, s)
        need = r - tz.GATE_MARGIN
        print(f"  {s}: past record {r:.1%} on {n} legs -> bet at {tz.price_needed(need):+.0f} or better"
              f"   (-134: {'bet' if tz.gate(-134, r) else 'no bet'}, -120: {'bet' if tz.gate(-120, r) else 'no bet'})")

    print("\nHALF-POINT CHECK (base legs; the line you actually bet differs from the close)")
    for shift, label in ((-0.5, "half a point worse"), (0.0, "closing line"), (0.5, "half a point better")):
        for span in ((2003, 2026), (2015, 2026)):
            d = between(legs, span).copy()
            d["r2"] = tz.leg_result(d.margin, d.line + shift)
            print(f"  {label:<20} {span[0]}-{span[1]}  {rec(d, 'r2')}")

    print("\nREPORTED, NOT CHOSEN (all seen before the rulebook was written)")
    for pts, dr, fr in ((6.5, (1.5, 2.5), (-9.0, -7.5)), (7.0, (1.5, 2.5), (-9.5, -7.5)), (6.0, (1.0, 3.0), (-9.0, -7.0))):
        d = rows[rows.line.between(*dr) | rows.line.between(*fr)].copy()
        d["r2"] = tz.leg_result(d.margin, d.line, pts)
        print(f"  {pts:g} points, dogs {dr[0]:+g} to {dr[1]:+g}, favs {fr[0]:+g} to {fr[1]:+g}:  {rec(d, 'r2')}   2015 on {rec(d[d.season >= 2015], 'r2')}")
    splits = {"home": legs.home, "away": ~legs.home, "total 42 or less": legs.total_line <= 42,
              "total 42.5 to 47": legs.total_line.between(42.5, 47), "total above 47": legs.total_line > 47,
              "division game": legs.div_game == 1, "other game": legs.div_game == 0,
              "home dog": legs.home & (legs.kind == "dog"), "away dog": ~legs.home & (legs.kind == "dog"),
              "home fav": legs.home & (legs.kind == "fav"), "away fav": ~legs.home & (legs.kind == "fav")}
    for name, mask in splits.items():
        print(f"  {name:<18} {rec(legs[mask])}   2015 on {rec(legs[mask & (legs.season >= 2015)])}")
    print("  three-team teaser, each leg must win: " + ", ".join(f"{p:+d} {tz.break_even_leg(p, 3):.1%}" for p in (160, 150, 140)))


if __name__ == "__main__":
    main(sys.argv[1].rstrip("/"))
