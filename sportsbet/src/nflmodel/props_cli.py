"""Command line entry point: player prop predictions for an NFL week, made without sportsbook prices."""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

import pandas as pd

from . import availability, data
from . import props as P
from . import props_card as C
from .card import next_week, predict_all

NOTE = ("How to read this: each line is the model's middle estimate and the range it expects 8 times in 10\n"
        "(10th to 90th percentile). 'Even line' is the half-point number the model thinks is a coin flip.\n"
        "The model never sees sportsbook prop prices, so a gap between its even line and a book's line is\n"
        "NOT proof of an edge: books know about injuries, snap counts and matchups that this model does\n"
        "not, and no test against prop prices has been run. Players come from this week's rosters and injury\n"
        "report when published; (Q) means questionable, and late scratches are not known. Bet only what you can afford to lose. If gambling stops being fun, call 1-800-GAMBLER.")

_ORDER = ["pass_yds", "pass_tds", "pass_att", "pass_cmp", "rush_yds", "rec_yds", "receptions", "any_td"]


def _cell(r: pd.Series) -> str:
    if r["prop"] == "any_td":
        return f"TD {r['chance']:.0%}"
    short = {"pass_yds": "pass", "pass_tds": "pTD", "pass_att": "att", "pass_cmp": "cmp", "rush_yds": "rush",
             "rec_yds": "rec", "receptions": "catches"}[r["prop"]]
    return f"{short} {r['median']:.0f} ({r['low']:.0f}-{r['high']:.0f})"


def render(df: pd.DataFrame, season: int, week: int) -> str:
    out = [f"NFL {season} week {week}: player prop predictions (median, 10th-90th percentile range)", ""]
    for gid, g in df.groupby("game_id", sort=False):
        day = dt.date.fromisoformat(str(g["gameday"].iloc[0])).strftime("%a %b %d")
        teams = list(dict.fromkeys(g["team"]))
        out.append(f"{' vs '.join(teams)}  ({day})")
        for team in teams:
            t = g[g["team"] == team]
            rank = {"QB": 0, "RB": 1, "WR": 2, "TE": 3}
            main = t[t["prop"].isin(["pass_yds", "rush_yds", "rec_yds"])].groupby("player_id")["expected"].max()
            players = sorted(t.drop_duplicates("player_id").itertuples(),
                             key=lambda r: (rank[r.position], -main.get(r.player_id, 0.0)))
            for p in players:
                rows = t[t["player_id"] == p.player_id].set_index("prop")
                cells = [_cell(_with_prop(rows.loc[k], k)) for k in _ORDER if k in rows.index]
                name = p.player + (" (Q)" if p.status == "Questionable" else "")
                out.append(f"  {team:<3} {p.position:<2} {name:<26} " + "  ".join(cells))
        out.append("")
    out.append(NOTE)
    return "\n".join(out)


def _with_prop(r: pd.Series, key: str) -> pd.Series:
    r = r.copy()
    r["prop"] = key
    return r


def lookup(df: pd.DataFrame, models: dict, player: str, prop: str, line: float) -> str:
    hit = df[df["player"].str.lower().str.contains(player.lower(), regex=False) & (df["prop"] == prop)]
    if hit.empty:
        return f"No {prop} prediction for a player matching '{player}' this week."
    out = []
    for _, r in hit.iterrows():
        scale = r["range_width"] if pd.notna(r.get("range_width")) else 1.0
        p = models[prop].prob_over(r["position"], r["expected"], line, scale)
        out.append(f"{r['player']} ({r['team']}) {P.PROP_BY_KEY[prop].label} over {line:g}: {p:.1%}   under: {1 - p:.1%}"
                   f"   (median {r['median']:.0f}, range {r['low']:.0f}-{r['high']:.0f}"
                   + (f", {r['consistency']}: range {r['range_width']:.2f}x a typical player's" if r.get("consistency") else "") + ")")
    return "\n".join(out)


def to_json(df: pd.DataFrame, season: int, week: int) -> dict:
    path = Path(__file__).with_name("props_backtest.json")
    bt = json.loads(path.read_text()) if path.exists() else {}
    recs = json.loads(df.to_json(orient="records", double_precision=4))
    return {"generated_at": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "season": season, "week": week, "probs": [float(x) for x in C.JSON_PROBS],
            "props": {p.key: {"label": p.label, "kind": p.kind} for p in P.PROPS},
            "backtest": bt.get("props", {}), "backtest_seasons": [bt.get("first"), bt.get("last")], "rows": recs}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="nflprops", description=__doc__)
    parser.add_argument("--data-dir", default=str(data.DEFAULT_DIR), help="where nflverse files are cached")
    parser.add_argument("--season", type=int, help="season (default: next unplayed week)")
    parser.add_argument("--week", type=int, help="week (default: next unplayed week)")
    parser.add_argument("--offline", action="store_true", help="use the cached files without downloading")
    parser.add_argument("--csv", help="also write one row per player and prop to this CSV file")
    parser.add_argument("--json", help="also write the predictions as JSON")
    parser.add_argument("--player", help="with --prop and --line: the chance one player clears a number")
    parser.add_argument("--prop", choices=[p.key for p in P.PROPS])
    parser.add_argument("--line", type=float)
    args = parser.parse_args(argv)

    data_dir = Path(args.data_dir)
    if not args.offline:
        today = dt.date.today()
        data.refresh(data_dir, today.year if today.month >= 9 else today.year - 1)
    games = pd.read_csv(data_dir / "games.csv")
    season, week = next_week(games)
    season, week = args.season or season, args.week or week
    offense = P.load_offense(str(data_dir))
    this = games[(games["season"] == season) & (games["week"] == week)]
    need_model = this["spread_line"].isna().any() or this["total_line"].isna().any()
    preds = predict_all(games, str(data_dir)) if need_model else None
    avail = availability.for_week(availability.load(str(data_dir), [season]), season, week)
    df, models = C.week_props(offense, games, season, week, preds, avail,
                               P.load_snaps(str(data_dir)), P.load_rookie_seasons(str(data_dir)),
                               P.load_lineups(str(data_dir), games))
    if df.empty:
        print(f"No games found for {season} week {week}.", file=sys.stderr)
        return 1
    if args.player:
        if not args.prop or args.line is None:
            parser.error("--player needs --prop and --line")
        print(lookup(df, models, args.player, args.prop, args.line))
        return 0
    print(render(df, season, week))
    if args.csv:
        flat = df.drop(columns=["ladder", "q", "pmf"]).copy()
        flat["ladder"] = [" ".join(f"{k}:{v:.0%}" for k, v in lad.items()) for lad in df["ladder"]]
        flat.to_csv(args.csv, index=False, float_format="%.3f")
    if args.json:
        Path(args.json).parent.mkdir(parents=True, exist_ok=True)
        Path(args.json).write_text(json.dumps(to_json(df, season, week), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
