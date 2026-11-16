"""nflsim: simulate a week of NFL games and print the odds the simulation implies.

Research tool. The backtest (research/stochastic/REPORT.md) found it no more accurate than the
nflmodel command and without an edge against closing lines; its use is pricing spreads, totals and
key numbers consistently from one picture of the game.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import zlib
from pathlib import Path

import numpy as np
import pandas as pd

from . import data, gamesim as gs, margin_model, qb_form, totals_model

CALIBRATION_SEASONS = 8


def fair_american(p: float) -> str:
    if not 0 < p < 1:
        return "n/a"
    return f"{-100 * p / (1 - p):+.0f}" if p >= 0.5 else f"+{100 * (1 - p) / p:.0f}"


def simulate_week(games: pd.DataFrame, data_dir: str, season: int, week: int | None = None,
                  n: int = gs.N_SIMS) -> list[dict]:
    rows = gs.fit_means(gs.team_rows(games, margin_model.load_team_stats(data_dir), qb_form.load_player_stats(data_dir),
                                     totals_model.load_team_stats(data_dir), gs.load_scoring(data_dir)))
    table = gs.game_table(rows)
    todo = games[(games["season"] == season) & games["result"].isna()]
    if week is None:
        if todo.empty:
            return []
        week = int(todo["week"].min())
    info = games.set_index("game_id")
    past = gs.simulate_table(table[table["home_points"].notna()], rows, games, n=n,
                             seasons=set(range(season - CALIBRATION_SEASONS, season + 1)))
    done = info.loc[past["game_id"]]
    fm = gs.calibration_factors(past["margin"], done["result"].to_numpy(float), gs.MARGIN_LO, True)
    ft = gs.calibration_factors(past["total"], done["total"].to_numpy(float), gs.TOTAL_LO, False)
    shape = gs.fit_shape(rows, games, season)
    km = np.arange(gs.MARGIN_LO, gs.MARGIN_HI + 1)
    out = []
    for r in table[(table["season"] == season) & (table["week"] == week)].itertuples(index=False):
        g = info.loc[r.game_id]
        h, a = gs.simulate(r.mu_home, r.mu_away, r.var_home, r.var_away, shape, n=n, seed=zlib.crc32(r.game_id.encode()))
        pm, pt = gs.pmfs(h, a)
        pm, pt = gs.apply_factors(pm, fm), gs.apply_factors(pt, ft)
        row = {"game_id": r.game_id, "home": g["home_team"], "away": g["away_team"], "gameday": g["gameday"],
               "home_points": round(float(r.mu_home), 1), "away_points": round(float(r.mu_away), 1),
               "rating_sd_home": round(float(r.var_home) ** 0.5, 2), "rating_sd_away": round(float(r.var_away) ** 0.5, 2),
               "home_win": float(pm[km > 0].sum() + 0.5 * pm[km == 0].sum()),
               "by_3": float(pm[np.abs(km) == 3].sum()), "by_7": float(pm[np.abs(km) == 7].sum())}
        if pd.notna(g.get("spread_line")):
            cover, push, _ = gs.line_probs(pm, float(g["spread_line"]), gs.MARGIN_LO)
            row.update(spread_line=float(g["spread_line"]), home_cover=cover, spread_push=push)
            # what one point either side of the posted spread is worth
            row["ladder"] = {f"{float(g['spread_line']) + d:+.1f}": round(gs.line_probs(pm, float(g["spread_line"]) + d, gs.MARGIN_LO)[0], 4)
                             for d in (-1.0, -0.5, 0.5, 1.0)}
        if pd.notna(g.get("total_line")):
            over, push, _ = gs.line_probs(pt, float(g["total_line"]), gs.TOTAL_LO)
            row.update(total_line=float(g["total_line"]), over=over, total_push=push)
        out.append(row)
    return sorted(out, key=lambda x: (x["gameday"], x["game_id"]))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="nflsim", description=__doc__.splitlines()[0])
    ap.add_argument("--season", type=int)
    ap.add_argument("--week", type=int, help="default: the next week with unplayed games")
    ap.add_argument("--sims", type=int, default=gs.N_SIMS, help="runs per game (default 10000)")
    ap.add_argument("--data-dir", type=Path, default=data.DEFAULT_DIR)
    ap.add_argument("--no-refresh", action="store_true", help="use the cached files as they are")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)
    today = dt.date.today()
    season = args.season or (today.year if today.month >= 8 else today.year - 1)
    if not args.no_refresh:
        data.refresh(args.data_dir, season)
    games = pd.read_csv(args.data_dir / "games.csv")
    rows = simulate_week(games, str(args.data_dir), season, args.week, args.sims)
    if args.json:
        print(json.dumps(rows, indent=1))
        return 0
    if not rows:
        print("No games to simulate.")
        return 0
    print(f"Simulated {args.sims:,} times per game. No proven edge against the betting line; see research/stochastic/REPORT.md.\n")
    for r in rows:
        print(f"{r['away']} {r['away_points']:.1f} at {r['home']} {r['home_points']:.1f}"
              f"   (rating uncertainty: {r['away']} ±{r['rating_sd_away']:.1f}, {r['home']} ±{r['rating_sd_home']:.1f} points)")
        print(f"    {r['home']} wins {r['home_win']:.1%} (fair price {fair_american(r['home_win'])}), "
              f"{r['away']} {1 - r['home_win']:.1%} ({fair_american(1 - r['home_win'])})")
        if "spread_line" in r:
            print(f"    spread {r['home']} {-r['spread_line']:+.1f}: {r['home']} covers {r['home_cover']:.1%}, push {r['spread_push']:.1%}, "
                  f"{r['away']} covers {1 - r['home_cover'] - r['spread_push']:.1%}")
        if "total_line" in r:
            print(f"    total {r['total_line']:.1f}: over {r['over']:.1%}, push {r['total_push']:.1%}, under {1 - r['over'] - r['total_push']:.1%}")
        print(f"    lands on 3: {r['by_3']:.1%}   lands on 7: {r['by_7']:.1%}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
