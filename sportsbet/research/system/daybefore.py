"""Does the betting system survive betting the day before instead of 30 minutes before kickoff?

Usage: python research/system/daybefore.py ODDS_DIR [score|report]

    score   refit the props model with only what is known the day before and score the day-before
            prices (lines_1440.csv.gz -> scored_1440.csv.gz). Slow.
    report  apply the frozen rules (RULEBOOK.md verdicts) to the day-before prices and compare with
            the 30-minute prices for the same players.

The day before, the game-day inactive list is not known. The line-up used here is everyone on the
53-man roster (active or later inactive) except players the final injury report lists as Out or
Doubtful. The rules are not re-chosen: edge at least 10%, blended edge at least 3.1% with weight
0.34, one bet per player, two per game, ten a week in kickoff order.
"""
from __future__ import annotations

import glob
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "props" / "prices"))

W, T = 0.34, 0.031


def daybefore_lineups(data_dir: str, games: pd.DataFrame, first_season: int = 2019) -> pd.DataFrame | None:
    from nflmodel import props as P
    def read(stem, cols):
        files = [f for f in sorted(glob.glob(os.path.join(data_dir, f"{stem}_*.csv")))
                 if int(os.path.basename(f)[-8:-4]) >= first_season]
        return pd.concat([pd.read_csv(f, usecols=cols, low_memory=False) for f in files], ignore_index=True)
    r = read("roster_weekly", ["season", "week", "team", "position", "status", "gsis_id"]).dropna(subset=["gsis_id"])
    r = r[r["status"].isin(["ACT", "INA"]) & r["position"].isin(P.POSITIONS)]
    inj = read("injuries", ["season", "week", "gsis_id", "report_status"])
    out_ids = inj[inj["report_status"].isin(["Out", "Doubtful"])][["season", "week", "gsis_id"]].drop_duplicates()
    r = r.merge(out_ids.assign(ruled_out=True), on=["season", "week", "gsis_id"], how="left")
    r = r[r["ruled_out"].isna()]
    r["team"] = r["team"].replace(P.TEAM_MAP)
    g = games[["game_id", "season", "week", "home_team", "away_team"]]
    sides = pd.concat([g.rename(columns={f"{s}_team": "team"})[["game_id", "season", "week", "team"]]
                       for s in ("home", "away")])
    sides["team"] = sides["team"].replace(P.TEAM_MAP)
    out = r.merge(sides, on=["season", "week", "team"]).rename(columns={"gsis_id": "player_id"})
    return out[["game_id", "team", "player_id"]].drop_duplicates()


def score(root: Path) -> None:
    from nflmodel import props as P
    import evaluate
    P.load_lineups = daybefore_lineups
    sys.argv = ["evaluate.py", str(root), "1440"]
    evaluate.main()


def system_bets(scored: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    """(every bet the frozen rules allow, the ten-a-week bets) at FanDuel."""
    import backtest as B
    d = B.main_lines(scored[scored["prop"].isin(B.PROPS)])
    fd = d[d["book"] == "fanduel"]
    s = B.side_table(fd, W)
    u = s[(s["side"] == "under") & (s["edge"] >= 0.10)]
    u = u.assign(score=u["bedge"])
    return u, B.pick_live(u, T, True)


def report(root: Path) -> None:
    import backtest as B
    late, early = pd.read_csv(root / "scored_30.csv.gz"), pd.read_csv(root / "scored_1440.csv.gz")
    games = set(early["game_id"])
    late = late[late["game_id"].isin(games)]
    print(f"games with day-before prices: {len(games)}")
    res = {}
    for label, sc in (("30 minutes before", late), ("day before", early)):
        allowed, live = system_bets(sc)
        res[label] = (allowed, live)
        print(f"\n== Frozen rules at FanDuel prices {label}")
        for name, seasons in (("2023-2024", B.DESIGN), ("2025-2026", B.JUDGE), ("all four", B.DESIGN + B.JUDGE)):
            a, lv = allowed[allowed["season"].isin(seasons)], live[live["season"].isin(seasons)]
            print(f"  {name}: ten a week {B.fmt(lv)} | every allowed bet {B.fmt(a)}")
        blind = B.side_table(B.main_lines(sc[sc["prop"].isin(B.PROPS) & (sc["book"] == "fanduel")]), W)
        print(f"  every FanDuel under, no model: {B.fmt(blind[blind['side'] == 'under'])}")

    # Where did the price go between the day before and 30 minutes before, for the day-before bets?
    key = ["game_id", "name_key", "prop"]
    late_fd = B.main_lines(late[late["prop"].isin(B.PROPS) & (late["book"] == "fanduel")])
    late_fd = late_fd[key + ["line", "dec_under", "p_book"]].rename(
        columns={"line": "line_late", "dec_under": "price_late", "p_book": "p_book_late"})
    print("\n== Did the market move toward the day-before bets? (closing line value)")
    for label, b in (("ten a week", res["day before"][1]), ("every allowed bet", res["day before"][0])):
        j = b.merge(late_fd, on=key, how="left")
        has = j["line_late"].notna()
        j = j[has]
        down, up = (j["line_late"] < j["line"]).mean(), (j["line_late"] > j["line"]).mean()
        same = j[j["line_late"] == j["line"]]
        # at an unchanged line: did the book's own chance of the under rise?
        p_under_early, p_under_late = 1 - same["p_book"], 1 - same["p_book_late"]
        print(f"  {label}: still listed late {has.mean():.0%} (n={len(j)}); line moved down (our way) {down:.0%}, "
              f"up {up:.0%}, mean move {np.mean(j['line_late'] - j['line']):+.2f}")
        print(f"      at an unchanged line the book's under chance went {p_under_early.mean():.1%} -> "
              f"{p_under_late.mean():.1%} (n={len(same)})")
    # Same check for unders the system did not pick, as the yardstick.
    allu = B.side_table(B.main_lines(early[early["prop"].isin(B.PROPS) & (early["book"] == "fanduel")]), W)
    allu = allu[allu["side"] == "under"].merge(late_fd, on=key, how="inner")
    print(f"  every FanDuel under, for comparison: line moved down {np.mean(allu['line_late'] < allu['line']):.0%}, "
          f"up {np.mean(allu['line_late'] > allu['line']):.0%}, mean move "
          f"{np.mean(allu['line_late'] - allu['line']):+.2f} (n={len(allu)})")

    # Bets that only exist early or only late.
    e, l = res["day before"][0], res["30 minutes before"][0]
    m = e[key].merge(l[key], on=key, how="outer", indicator=True)["_merge"].value_counts()
    print(f"\nallowed bets: both times {m.get('both', 0)}, day before only {m.get('left_only', 0)}, "
          f"30 minutes only {m.get('right_only', 0)}")


if __name__ == "__main__":
    root = Path(sys.argv[1])
    (score if (sys.argv[2] if len(sys.argv) > 2 else "report") == "score" else report)(root)
