"""Does the choice of vig-removal method matter for the props model against FanDuel prices?

Usage: python research/props/devig/devig.py ODDS_DIR/ [minutes] [book]

Reads scored_<minutes>.csv.gz written by research/props/prices/evaluate.py (model chance, result and
posted prices per line), so the model is not refitted. Prints summary tables only; no prices are saved.

Methods are the ones in penaltyblog.implied (pip install --no-deps penaltyblog, plus whatever its
import chain asks for). The current method in evaluate.py is "multiplicative" (proportional).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd
from penaltyblog.implied import ImpliedMethod, calculate_implied

METHODS = [m.value for m in ImpliedMethod]
BASE = "multiplicative"
SHORT = {"multiplicative": "mult", "additive": "add", "power": "power", "shin": "shin",
         "differential_margin_weighting": "dmw", "odds_ratio": "oddsr", "logarithmic": "log"}


def logit(p):
    return np.log(p / (1 - p))


def fair_probs(d: pd.DataFrame) -> pd.DataFrame:
    """Fair P(over) per method, computed once per distinct (over, under) price pair."""
    pairs = d[["dec_over", "dec_under"]].drop_duplicates().reset_index(drop=True)
    for m in METHODS:
        pairs[m] = [calculate_implied([o, u], method=m).probabilities[0]
                    for o, u in zip(pairs["dec_over"], pairs["dec_under"])]
    return d.merge(pairs, on=["dec_over", "dec_under"], how="left")


def cl_se(x: np.ndarray, g: pd.Series) -> float:
    """Standard error of mean(x), clustered by game."""
    dev = pd.Series(x - x.mean()).groupby(g.to_numpy()).sum()
    return float(np.sqrt((dev ** 2).sum()) / len(x))


def fit_blend(y, p_book, p_model, groups=None):
    """evaluate.blend_weight: logistic fit, offset logit(book), intercept + w * (logit model - logit book).
    Returns intercept, weight, and the weight's game-clustered (sandwich) standard error."""
    x = np.column_stack([np.ones(len(y)), logit(p_model) - logit(p_book)])
    off = logit(p_book)
    b = np.zeros(2)
    for _ in range(30):
        p = 1 / (1 + np.exp(-(off + x @ b)))
        h = (x * (p * (1 - p))[:, None]).T @ x
        b += np.linalg.solve(h, x.T @ (y - p))
    se = float(np.sqrt(np.linalg.inv(h)[1, 1]))
    if groups is not None:
        s = pd.DataFrame(x * (y - p)[:, None]).groupby(np.asarray(groups)).sum().to_numpy()
        hi = np.linalg.inv(h)
        se = float(np.sqrt((hi @ (s.T @ s) @ hi)[1, 1]))
    return float(b[0]), float(b[1]), se


def blend_prob(a, w, p_book, p_model):
    return 1 / (1 + np.exp(-(a + logit(p_book) + w * (logit(p_model) - logit(p_book)))))


def bet_profit(d: pd.DataFrame, p_over: np.ndarray, edge: float) -> tuple[np.ndarray, np.ndarray]:
    """Per-line profit (0 where no bet) and a bet mask, betting the better side when its edge at the
    posted price exceeds ``edge``. Same rule as evaluate.bets with ``p_over`` in place of the model."""
    ev_o = p_over * d["dec_over"].to_numpy() - 1
    ev_u = (1 - p_over) * d["dec_under"].to_numpy() - 1
    over = ev_o >= ev_u
    ev = np.where(over, ev_o, ev_u)
    a, line = d["actual"].to_numpy(), d["line"].to_numpy()
    win = np.where(over, a > line, a < line)
    price = np.where(over, d["dec_over"], d["dec_under"])
    placed = (ev > edge) & (a != line)
    return np.where(placed, np.where(win, price - 1, -1.0), 0.0), placed


def roi(profit, placed, games) -> str:
    n = int(placed.sum())
    if n < 2:
        return f"{n:>5} bets"
    p = profit[placed]
    return f"{n:>5} {p.mean():+6.1%} ±{1.96 * cl_se(p, games[placed]):4.1%}"


def main() -> None:
    root = Path(sys.argv[1])
    minutes = sys.argv[2] if len(sys.argv) > 2 else "30"
    book = sys.argv[3] if len(sys.argv) > 3 else "fanduel"
    d = pd.read_csv(root / f"scored_{minutes}.csv.gz")
    d = d[d["book"] == book]
    one = d[d["under_price"].isna()]
    print(f"{book}: {len(d)} scored main lines; one-sided (no under price) {len(one)}: "
          f"{one['prop'].value_counts().to_dict()}")
    d = fair_probs(d[d["under_price"].notna()].copy()).reset_index(drop=True)
    print(f"two-sided lines {len(d)} by prop {d['prop'].value_counts().to_dict()}; games {d['game_id'].nunique()}")
    d["hold"] = 1 / d["dec_over"] + 1 / d["dec_under"] - 1
    print(f"hold: median {d['hold'].median():.3f}, 5th-95th pct {d['hold'].quantile(.05):.3f}-{d['hold'].quantile(.95):.3f}")
    print("penaltyblog methods:", ", ".join(METHODS))

    print("\n== 1. How much do the methods differ from multiplicative? (fair P(over), all two-sided lines)")
    print(f"{'method':<8}{'mean |diff|':>12}{'max |diff|':>11}{'>0.5pp':>8}{'>1pp':>8}{'>2pp':>8}")
    for m in METHODS[1:]:
        x = (d[m] - d[BASE]).abs()
        print(f"{SHORT[m]:<8}{x.mean():>12.4f}{x.max():>11.4f}{(x > .005).mean():>8.1%}{(x > .01).mean():>8.1%}{(x > .02).mean():>8.1%}")
    anyd = (d[METHODS[1:]].sub(d[BASE], axis=0).abs().max(axis=1) > .01)
    skew = (d[BASE] - .5).abs()
    print(f"lines where ANY method is >1pp from multiplicative: {anyd.mean():.1%} ({anyd.sum()} lines)")
    print("share of lines by |mult fair - 50%|: " + ", ".join(
        f"{lo:.0%}-{hi:.0%}: {((skew >= lo) & (skew < hi)).mean():.1%}" for lo, hi in ((0, .02), (.02, .05), (.05, .10), (.10, .5))))
    d["disagree"] = anyd

    s = d[~d["push"]].reset_index(drop=True)
    y, g = s["y"].to_numpy(), s["game_id"]

    print(f"\n== 2. Accuracy of the book's fair P(over) (non-push lines, n={len(s)}; over rate {y.mean():.3f})")
    print("diff = method minus multiplicative, ±1.96 game-clustered SE; negative is better")

    def acc(sub, label):
        yy, gg = sub["y"].to_numpy(), sub["game_id"]
        print(f"-- {label} (n={len(sub)})")
        print(f"{'method':<8}{'Brier':>9}{'diff x1e4':>18}{'logloss':>10}{'diff x1e4':>18}")
        b0 = (sub[BASE] - yy) ** 2
        l0 = -(yy * np.log(sub[BASE]) + (1 - yy) * np.log(1 - sub[BASE]))
        for m in METHODS:
            b = (sub[m] - yy) ** 2
            ll = -(yy * np.log(sub[m]) + (1 - yy) * np.log(1 - sub[m]))
            db, dl = (b - b0).to_numpy(), (ll - l0).to_numpy()
            print(f"{SHORT[m]:<8}{b.mean():>9.5f}{db.mean() * 1e4:>+10.2f} ±{1.96 * cl_se(db, gg) * 1e4:5.2f}"
                  f"{ll.mean():>10.5f}{dl.mean() * 1e4:>+10.2f} ±{1.96 * cl_se(dl, gg) * 1e4:5.2f}")
    acc(s, "all lines")
    acc(s[s["disagree"]], "only lines where some method is >1pp from multiplicative")
    for season, sub in s.groupby("season"):
        b0 = (sub[BASE] - sub["y"]) ** 2
        print(f"  season {season} (n={len(sub)}) Brier diff x1e4 vs mult: " + "  ".join(
            f"{SHORT[m]} {(((sub[m] - sub['y']) ** 2) - b0).mean() * 1e4:+.2f}" for m in METHODS[1:]))

    print("\n== 3. Reliability by price: favourite side of each line (the side with the shorter price)")
    print("bucket = raw implied chance of the favourite side (1/decimal, vig included); cells are fair chance minus win rate, in pp")
    fav_over = s["dec_over"] <= s["dec_under"]
    raw = np.where(fav_over, 1 / s["dec_over"], 1 / s["dec_under"])
    won = np.where(fav_over, y, 1 - y)
    edges = [0, .54, .56, .60, .65, 1]
    print(f"{'raw implied':<13}{'n':>6}{'win rate':>9}{'±':>6}" + "".join(f"{SHORT[m]:>7}" for m in METHODS))
    for lo, hi in zip(edges[:-1], edges[1:]):
        k = (raw >= lo) & (raw < hi)
        if k.sum() < 20:
            continue
        wr = won[k].mean()
        cells = "".join(f"{(np.where(fav_over[k], s.loc[k, m], 1 - s.loc[k, m]).mean() - wr) * 100:>+7.1f}" for m in METHODS)
        print(f"{lo:.2f}-{hi:.2f}    {k.sum():>6}{wr:>9.3f}{1.96 * cl_se(won[k].astype(float), g[k]) * 100:>5.1f}p{cells}")
    print("same, split by which side is the favourite (tests the general lean to unders)")
    for label, side in (("over fav", fav_over & (s["dec_over"] < s["dec_under"])), ("under fav", ~fav_over)):
        for lo, hi in ((.54, .60), (.60, 1)):
            k = (side & (raw >= lo) & (raw < hi)).to_numpy()
            if k.sum() < 20:
                continue
            wr = won[k].mean()
            cells = "".join(f"{(np.where(fav_over[k], s.loc[k, m], 1 - s.loc[k, m]).mean() - wr) * 100:>+7.1f}" for m in METHODS)
            print(f"{label:<9} {lo:.2f}-{hi:.2f} {k.sum():>5}{wr:>7.3f}{1.96 * cl_se(won[k].astype(float), g[k]) * 100:>5.1f}p{cells}")
    print("calibration slope: logistic fit of result on logit(fair P(over)); slope 1 = no favourite-longshot bias")
    for m in METHODS:
        x = np.column_stack([np.ones(len(s)), logit(s[m])])
        b = np.zeros(2)
        for _ in range(30):
            p = 1 / (1 + np.exp(-(x @ b)))
            h = (x * (p * (1 - p))[:, None]).T @ x
            b += np.linalg.solve(h, x.T @ (y - p))
        sc = pd.DataFrame(x * (y - p)[:, None]).groupby(g.to_numpy()).sum().to_numpy()
        hi_ = np.linalg.inv(h)
        print(f"  {SHORT[m]:<6} intercept {b[0]:+.3f}  slope {b[1]:.3f} ±{1.96 * np.sqrt((hi_ @ (sc.T @ sc) @ hi_)[1, 1]):.3f}")

    print("\n== 4. Blend weight on the model against each fair price (evaluate.blend_weight form; ± is 1.96 clustered SE)")
    seasons = sorted(s["season"].unique())
    print(f"{'method':<8}{'ALL':>14}" + "".join(f"{x:>14}" for x in seasons) + f"{'disagree>1pp':>15}")
    for m in METHODS:
        row = f"{SHORT[m]:<8}"
        for sub in [s] + [s[s["season"] == x] for x in seasons] + [s[s["disagree"]]]:
            _, w, se = fit_blend(sub["y"].to_numpy(), sub[m].to_numpy(), sub["p_model"].to_numpy(), sub["game_id"])
            row += f"{w:>8.3f} ±{1.96 * se:.2f}"
        print(row)
    print(f"Brier of the model on the same lines: {np.mean((s['p_model'] - y) ** 2):.5f}")

    print("\n== 5. Betting. Rule A (current): model edge at the posted price. De-vig does not enter.")
    dd = d.reset_index(drop=True)
    gg = dd["game_id"]
    later = (dd["season"] > min(seasons)).to_numpy()
    for e in (0, .05, .10):
        pr, pl = bet_profit(dd, dd["p_model"].to_numpy(), e)
        print(f"  edge > {e:.0%}: all seasons {roi(pr, pl, gg)} | seasons after {min(seasons)}: {roi(pr[later], pl[later], gg[later])}")

    print("\nRule B: bet on the edge of a blend of model and fair price; intercept and weight fitted on earlier seasons only")
    print(f"(so no bets in {min(seasons)}). Columns: bets, ROI, ±1.96 clustered SE.")
    blends, fits = {}, {}
    for m in METHODS:
        pb = np.full(len(dd), np.nan)
        for season in seasons[1:]:
            tr = s[s["season"] < season]
            a, w, _ = fit_blend(tr["y"].to_numpy(), tr[m].to_numpy(), tr["p_model"].to_numpy())
            fits[(m, season)] = (a, w)
            k = (dd["season"] == season).to_numpy()
            pb[k] = blend_prob(a, w, dd.loc[k, m].to_numpy(), dd.loc[k, "p_model"].to_numpy())
        blends[m] = pb
    print("walk-forward fits (intercept, weight): " + "; ".join(
        f"{SHORT[m]} " + " ".join(f"{sn}:({a:+.3f},{w:.3f})" for (mm, sn), (a, w) in fits.items() if mm == m) for m in (BASE, "power", "additive")))
    x = dd[later].reset_index(drop=True)
    gx = x["game_id"]
    for e in (0, .05, .10):
        print(f"-- blend edge > {e:.0%}")
        print(f"{'method':<8}{'pooled':>20}" + "".join(f"{sn:>20}" for sn in seasons[1:]) + f"{'units vs mult (±1.96se)':>26}{'bets changed':>14}")
        p0, l0 = bet_profit(x, blends[BASE][later], e)
        for m in METHODS:
            pr, pl = bet_profit(x, blends[m][later], e)
            row = f"{SHORT[m]:<8}{roi(pr, pl, gx):>20}"
            for sn in seasons[1:]:
                k = (x["season"] == sn).to_numpy()
                row += f"{roi(pr[k], pl[k], gx[k]):>20}"
            diff = pr - p0
            row += f"{diff.sum():>+14.1f} ±{1.96 * cl_se(diff, gx) * len(diff):5.1f}{int((pl != l0).sum() + ((pl & l0) & (pr != p0)).sum()):>14}"
            print(row)
    print("\nRule B without the fitted intercept (weight only), edge > 5%: isolates the de-vig effect from the under tilt")
    for m in METHODS:
        pb = np.full(len(dd), np.nan)
        for season in seasons[1:]:
            k = (dd["season"] == season).to_numpy()
            pb[k] = blend_prob(0.0, fits[(m, season)][1], dd.loc[k, m].to_numpy(), dd.loc[k, "p_model"].to_numpy())
        pr, pl = bet_profit(x, pb[later], .05)
        print(f"  {SHORT[m]:<6}{roi(pr, pl, gx)}")


if __name__ == "__main__":
    main()
