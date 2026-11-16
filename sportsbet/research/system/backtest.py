"""Backtest the betting system rulebook (RULEBOOK.md) against real FanDuel and Fanatics prop prices.

Usage: python research/system/backtest.py ODDS_DIR [DATA_DIR]

ODDS_DIR holds scored_30.csv.gz (written by research/props/prices/evaluate.py). DATA_DIR holds the
nflverse stats_player_week files (default ~/.cache/nflmodel); they are used only to count how many
games a player has had with their current team.

Choices are made on the design seasons (2023, 2024) and the chosen system is scored once on the
judging seasons (2025, 2026).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).parents[1] / "props" / "prices"))
from parse import norm  # noqa: E402

DESIGN, JUDGE = (2023, 2024), (2025, 2026)
PROPS = ("rec_yds", "receptions")
STAKE, WEEK_BETS = 5.0, 10
MIN_EDGE = 0.05
RNG = np.random.default_rng(20261115)
DRAWS = 2000


def logit(p):
    return np.log(p / (1 - p))


def main_lines(d: pd.DataFrame) -> pd.DataFrame:
    """One two-sided line per book and player: the most evenly priced (drops alternate lines)."""
    d = d.dropna(subset=["over_price", "under_price", "line"]).copy()
    d["skew"] = (1 / d["dec_over"] - 1 / d["dec_under"]).abs()
    return d.sort_values("skew").drop_duplicates(["game_id", "book", "prop", "name_key"]).drop(columns="skew")


def team_games(data_dir: Path) -> pd.DataFrame:
    """Games already played with the current team, and whether the player was on another team before."""
    frames = []
    for f in sorted(data_dir.glob("stats_player_week_*.csv")):
        frames.append(pd.read_csv(f, usecols=["player_id", "player_display_name", "season", "week", "game_id",
                                               "team"], low_memory=False))
    s = pd.concat(frames).dropna(subset=["game_id"]).sort_values(["player_id", "season", "week"])
    s = s.drop_duplicates(["player_id", "game_id"])
    moved = (s["team"] != s.groupby("player_id")["team"].shift()) & s.groupby("player_id").cumcount().gt(0)
    s["stint"] = moved.groupby(s["player_id"]).cumsum()
    s["with_team"] = s.groupby(["player_id", "stint"]).cumcount()
    s["name_key"] = s["player_display_name"].map(norm)
    s["new_team"] = (s["stint"] > 0) & (s["with_team"] < 8)
    return s[["game_id", "name_key", "with_team", "new_team"]].drop_duplicates(["game_id", "name_key"])


def blend_weight(y, p_book, p_model) -> float:
    """Weight on (model - book) in a logistic fit with the book's chance as an offset (with an intercept)."""
    x = np.column_stack([np.ones(len(y)), logit(p_model) - logit(p_book)])
    off, b = logit(p_book), np.zeros(2)
    for _ in range(30):
        p = 1 / (1 + np.exp(-(off + x @ b)))
        b += np.linalg.solve((x * (p * (1 - p))[:, None]).T @ x, x.T @ (y - p))
    return float(b[1])


def side_table(d: pd.DataFrame, w: float) -> pd.DataFrame:
    """Two rows per line (over and under) with price, model edge, blended edge and profit per $1."""
    p_blend = 1 / (1 + np.exp(-(logit(d["p_book"]) + w * (logit(d["p_model"]) - logit(d["p_book"])))))
    out = []
    for side in ("under", "over"):
        pm = d["p_model"] if side == "over" else 1 - d["p_model"]
        pb = p_blend if side == "over" else 1 - p_blend
        price = d[f"dec_{side}"]
        win = (d["actual"] > d["line"]) if side == "over" else (d["actual"] < d["line"])
        profit = np.where(d["actual"] == d["line"], 0.0, np.where(win, price - 1, -1.0))
        out.append(d.assign(side=side, price=price, edge=pm * price - 1, bedge=pb * price - 1, p_side=pm,
                            win=win, profit=profit))
    return pd.concat(out, ignore_index=True)


def roi(b: pd.DataFrame, stake: np.ndarray | None = None) -> tuple[int, float, float]:
    """Bets, return per dollar staked, and its standard error clustered by game."""
    if len(b) == 0:
        return 0, float("nan"), float("nan")
    st = np.ones(len(b)) if stake is None else stake
    r = float((b["profit"] * st).sum() / st.sum())
    dev = pd.Series((b["profit"].to_numpy() - r) * st).groupby(b["game_id"].to_numpy()).sum()
    return len(b), r, float(np.sqrt((dev ** 2).sum()) / st.sum())


def fmt(b: pd.DataFrame) -> str:
    n, r, se = roi(b)
    return "no bets" if n == 0 else f"{n:>5} bets  win {b['win'].mean():.1%}  return {r:+.1%} (±{1.96 * se:.1%})"


def per_season(b: pd.DataFrame, seasons) -> list[float]:
    return [roi(b[b["season"] == s])[1] for s in seasons]


def cap_spread(b: pd.DataFrame) -> pd.DataFrame:
    """C6: best-scoring bet per player each week, then the best two per game."""
    b = b.sort_values("score", ascending=False)
    b = b.drop_duplicates(["season", "week", "name_key"])
    return b[b.groupby("game_id").cumcount() < 2]


def pick_live(b: pd.DataFrame, t: float, spread: bool) -> pd.DataFrame:
    """Kickoff order, bet every line scoring at least t until the weekly cap is reached."""
    b = b[b["score"] >= t].sort_values(["season", "week", "kickoff", "score"], ascending=[True, True, True, False])
    if spread:
        b = b.drop_duplicates(["season", "week", "name_key"])
        b = b[b.groupby("game_id").cumcount() < 2]
    return b[b.groupby(["season", "week"]).cumcount() < WEEK_BETS]


def pick_best(b: pd.DataFrame, spread: bool) -> pd.DataFrame:
    b = cap_spread(b) if spread else b
    b = b.sort_values("score", ascending=False)
    return b[b.groupby(["season", "week"]).cumcount() < WEEK_BETS]


def random_weeks(b: pd.DataFrame, draws: int = DRAWS) -> np.ndarray:
    """Return per dollar for `draws` seasons of WEEK_BETS random bets a week from b."""
    groups = [g["profit"].to_numpy() for _, g in b.groupby(["season", "week"])]
    out = np.zeros(draws)
    for i in range(draws):
        picks = [RNG.choice(g, size=min(WEEK_BETS, len(g)), replace=False) for g in groups]
        out[i] = np.concatenate(picks).mean()
    return out


def threshold_for(b: pd.DataFrame, lo: float = 10, hi: float = 12) -> float:
    """Lowest score cut that leaves an average of at most `hi` qualifying bets a week."""
    weeks = b.groupby(["season", "week"]).ngroups
    s = np.sort(b["score"].to_numpy())[::-1]
    k = min(len(s) - 1, int(round((lo + hi) / 2 * weeks)))
    return float(s[k])


def simulate_stops(prices: np.ndarray, true_roi: float, weeks: int = 18, draws: int = 20000) -> dict:
    """Seasons of flat $5 bets at the system's prices with a given true return per dollar."""
    n = weeks * WEEK_BETS
    pr = RNG.choice(prices, size=(draws, n))
    win = RNG.random((draws, n)) < (1 + true_roi) / pr
    pnl = np.where(win, pr - 1, -1.0) * STAKE
    cum = pnl.cumsum(axis=1)
    dd = (np.maximum.accumulate(np.maximum(cum, 0), axis=1) - cum).max(axis=1)
    # two seasons back to back for the 200-bet retire check
    first200 = np.concatenate([pnl, RNG.permuted(pnl, axis=0)], axis=1)[:, :200].sum(axis=1) / (200 * STAKE)
    end = cum[:, -1]
    return {"p5": np.percentile(end, 5), "p50": np.percentile(end, 50), "p95": np.percentile(end, 95),
            "lose": float((end < 0).mean()), "pause": float((dd >= 150).mean()),
            "retire": float((first200 < -0.10).mean()), "worst_dd": float(np.percentile(dd, 95))}


def main() -> None:
    root = Path(sys.argv[1])
    data_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else Path.home() / ".cache" / "nflmodel"
    raw = pd.read_csv(root / "scored_30.csv.gz")
    raw = raw[raw["prop"].isin(PROPS)]
    raw = main_lines(raw).merge(team_games(data_dir), on=["game_id", "name_key"], how="left")
    raw["new_team"] = raw["new_team"].fillna(False).astype(bool)
    fd = raw[raw["book"] == "fanduel"]
    design = fd[fd["season"].isin(DESIGN)]
    w = blend_weight(design["y"].to_numpy(), design["p_book"].to_numpy(), design["p_model"].to_numpy())
    sides = side_table(fd, w)
    print(f"FanDuel main lines, receiving yards and receptions: {len(fd)} in {fd['game_id'].nunique()} games; "
          f"weeks {fd.groupby(['season', 'week']).ngroups}")
    print(f"blend weight on the model, fitted on {DESIGN}: {w:.3f}")

    under = sides[sides["side"] == "under"]
    base = under[under["edge"] >= MIN_EDGE]
    both = sides[sides["edge"] >= MIN_EDGE].sort_values("edge", ascending=False).drop_duplicates(
        ["game_id", "name_key", "prop"])
    cands = {
        "B  base rule (unders, edge >= 5%)": base,
        "C1 both sides": both,
        "C2 edge >= 10%": base[base["edge"] >= 0.10],
        "C3 drop edge > 25%": base[base["edge"] <= 0.25],
        "C5 skip first 8 games with a new team": base[~base["new_team"]],
        "C6 1 per player, 2 per game": cap_spread(base.assign(score=base["edge"])),
        "C7a receptions only": base[base["prop"] == "receptions"],
        "C7b receiving yards only": base[base["prop"] == "rec_yds"],
    }
    print("\n== Step 1. Candidate changes on the design seasons (every qualifying bet, $1 each)")
    print(f"{'rule':<40}{'2023':>8}{'2024':>8}   both design seasons")
    b0 = per_season(base, DESIGN)
    adopted = []
    for name, b in cands.items():
        r = per_season(b, DESIGN)
        verdict = ""
        if not name.startswith("B "):
            ok = r[0] > b0[0] and r[1] > b0[1]
            verdict = "ADOPT" if ok else "reject"
            if ok:
                adopted.append(name[:3].strip())
        print(f"{name:<40}{r[0]:>+8.1%}{r[1]:>+8.1%}   {fmt(b[b['season'].isin(DESIGN)])}  {verdict}")
    extra = base[base["new_team"] & base["season"].isin(DESIGN)]
    print(f"   (the bets C5 would skip: {fmt(extra)})")

    # C4: a ranking change, so it is judged on the best 10 a week.
    print("\n== Step 2. C4, ranking by blended edge instead of model edge (best 10 a week, design seasons)")
    r_edge = per_season(pick_best(base.assign(score=base["edge"]), False), DESIGN)
    r_blend = per_season(pick_best(base.assign(score=base["bedge"]), False), DESIGN)
    c4 = r_blend[0] > r_edge[0] and r_blend[1] > r_edge[1]
    print(f"  by model edge:   2023 {r_edge[0]:+.1%}  2024 {r_edge[1]:+.1%}")
    print(f"  by blended edge: 2023 {r_blend[0]:+.1%}  2024 {r_blend[1]:+.1%}   {'ADOPT' if c4 else 'reject'}")
    if c4:
        adopted.append("C4")
    print(f"\nAdopted on the design seasons: {', '.join(adopted) if adopted else 'none'}")

    # Build the system from the adopted switches.
    sysb = base
    if "C2" in adopted:
        sysb = sysb[sysb["edge"] >= 0.10]
    if "C3" in adopted:
        sysb = sysb[sysb["edge"] <= 0.25]
    if "C5" in adopted:
        sysb = sysb[~sysb["new_team"]]
    if "C7a" in adopted and "C7b" not in adopted:
        sysb = sysb[sysb["prop"] == "receptions"]
    if "C7b" in adopted and "C7a" not in adopted:
        sysb = sysb[sysb["prop"] == "rec_yds"]
    sysb = sysb.assign(score=sysb["bedge" if c4 else "edge"])
    spread = "C6" in adopted
    pool = cap_spread(sysb) if spread else sysb
    t = threshold_for(pool[pool["season"].isin(DESIGN)])
    per_week = pool[pool["season"].isin(DESIGN)].groupby(["season", "week"]).size()
    print(f"\n== Step 3. Weekly cap of {WEEK_BETS}")
    print(f"qualifying bets a week on the design seasons: median {per_week.median():.0f}")
    print(f"W-live score cut T = {t:.3f} ({'blended' if c4 else 'model'} edge), fixed from the design seasons")

    live, best = pick_live(sysb, t, spread), pick_best(sysb, spread)
    blind = under  # every FanDuel under on these props
    for label, seasons in (("DESIGN 2023-2024 (choices were made here)", DESIGN),
                           ("JUDGING 2025-2026 (scored once)", JUDGE)):
        sel = lambda x: x[x["season"].isin(seasons)]  # noqa: E731
        lv = sel(live)
        print(f"\n-- {label}")
        print(f"  every bet the system allows:      {fmt(sel(sysb))}")
        print(f"  W-live, 10 a week in kickoff order: {fmt(lv)}   "
              f"({lv.groupby(['season', 'week']).size().mean():.1f} bets a week)")
        for s in seasons:
            print(f"      season {s}: {fmt(lv[lv['season'] == s])}")
        print(f"  W-best, top 10 of the week:        {fmt(sel(best))}")
        rq = random_weeks(sel(sysb))
        rb = random_weeks(sel(blind))
        lr = roi(lv)[1]
        print(f"  W-random, 10 random qualifying bets a week: middle {np.median(rq):+.1%}, "
              f"90% of draws between {np.percentile(rq, 5):+.1%} and {np.percentile(rq, 95):+.1%}")
        print(f"  10 blind unders a week:                     middle {np.median(rb):+.1%}, "
              f"90% of draws between {np.percentile(rb, 5):+.1%} and {np.percentile(rb, 95):+.1%}")
        print(f"  W-live beats {np.mean(lr > rb):.0%} of blind-under draws and {np.mean(lr > rq):.0%} of random "
              f"qualifying draws")
        print(f"  W-live in dollars at ${STAKE:.0f} a bet: staked ${len(lv) * STAKE:,.0f}, "
              f"profit ${lv['profit'].sum() * STAKE:+,.0f}")

    print("\n== Step 3b. Did each adopted change hold up on the judging seasons? (every qualifying bet)")
    for name, b in cands.items():
        tag = name[:3].strip()
        mark = "adopted" if tag in adopted else ("" if tag == "B" else "rejected")
        print(f"  {name:<40}{fmt(b[b['season'].isin(JUDGE)])}  {mark}")
    print("\n== Step 3c. How much W-live depends on the score cut (shown for honesty, not used to choose)")
    for cut in (0.0, 0.02, t, 0.05, 0.08):
        lv = pick_live(sysb, cut, spread)
        print(f"  cut {cut:.3f}: design {fmt(lv[lv['season'].isin(DESIGN)])} | judging {fmt(lv[lv['season'].isin(JUDGE)])}")

    # Line shopping on the judging seasons.
    print("\n== Step 4. Line shopping: FanDuel against Fanatics for the system's bets, 2025-2026")
    fn = raw[raw["book"] == "fanatics"][["game_id", "name_key", "prop", "line", "dec_under"]].rename(
        columns={"line": "fn_line", "dec_under": "fn_price"})
    for label, b in (("every bet the system allows", sysb), ("W-live bets", live)):
        j = b[b["season"].isin(JUDGE)].merge(fn, on=["game_id", "name_key", "prop"], how="left")
        has = j["fn_line"].notna()
        better_line = has & (j["fn_line"] > j["line"])
        better_price = has & (j["fn_line"] == j["line"]) & (j["fn_price"] > j["price"])
        use = better_line | better_price
        line = np.where(use, j["fn_line"], j["line"])
        price = np.where(use, j["fn_price"], j["price"])
        prof = np.where(j["actual"] == line, 0.0, np.where(j["actual"] < line, price - 1, -1.0))
        shop = j.assign(profit=prof, win=j["actual"] < line)
        print(f"  {label}: Fanatics lists the player on {has.mean():.0%}; better line {better_line.mean():.1%}, "
              f"same line at a better price {better_price.mean():.1%}")
        print(f"      FanDuel only:        {fmt(j)}")
        print(f"      better of the two:   {fmt(shop)}")
        both_books = j[has]
        print(f"      where both list him, FanDuel {roi(both_books)[1]:+.1%} against the better of the two "
              f"{roi(shop[has.to_numpy()])[1]:+.1%} (n={int(has.sum())})")

    # Staking.
    print("\n== Step 5. Staking on the W-live bets, all four seasons")
    live = live.sort_values(["season", "week", "kickoff"])
    kf = (live["edge"] / (live["price"] - 1)).to_numpy()
    k_stake = np.minimum(kf / kf.mean() * STAKE, 10.0)
    k_stake *= STAKE / k_stake.mean()
    for label, st in (("flat $5", np.full(len(live), STAKE)), ("Kelly-shaped, average $5, at most ~$10", k_stake)):
        n, r, se = roi(live, st)
        bet_pnl = live["profit"].to_numpy() * st
        pnl = pd.Series(bet_pnl).groupby([live["season"].to_numpy(), live["week"].to_numpy()]).sum()
        cum = bet_pnl.cumsum()
        dd = (np.maximum.accumulate(np.maximum(cum, 0)) - cum).max()
        print(f"  {label:<40} return {r:+.1%} (±{1.96 * se:.1%}), profit ${cum[-1]:+,.0f}, "
              f"worst week ${pnl.min():+.0f}, losing weeks {np.mean(pnl < 0):.0%}, deepest drop ${dd:.0f}")

    # Stop rules and luck.
    print(f"\n== Step 6. One season of {WEEK_BETS} bets a week at ${STAKE:.0f} (18 weeks, $900 staked), by luck alone")
    prices = live["price"].to_numpy()
    print(f"{'true return':<28}{'5% worst':>10}{'middle':>9}{'5% best':>9}{'lose money':>12}"
          f"{'$150 pause':>12}{'retire at 200':>15}")
    for label, tr in (("-4.5% (no skill, pay the vig)", -0.045), ("0%", 0.0), ("+3% (the hoped-for edge)", 0.03),
                      ("+6%", 0.06)):
        s = simulate_stops(prices, tr)
        print(f"{label:<28}{s['p5']:>+10.0f}{s['p50']:>+9.0f}{s['p95']:>+9.0f}{s['lose']:>12.0%}"
              f"{s['pause']:>12.0%}{s['retire']:>15.0%}")
    n_live = len(live[live["season"].isin(JUDGE)])
    print(f"\nA 95% range on the return from {n_live} bets is about ±{1.96 / np.sqrt(n_live):.0%}; "
          f"from one season of 180 bets it is about ±{1.96 / np.sqrt(180):.0%}.")


if __name__ == "__main__":
    main()
