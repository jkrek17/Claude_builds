"""The weekly loop: freeze each week's picks before kickoff, grade them afterwards, and report honestly.

State lives in one directory (in production, a checkout of the `sportsbet-data` branch):

    picks.csv        append-only ledger of frozen picks, one row per (game_id, snapshot_type), hash-chained
    grades.csv       derived from picks.csv and the final scores; rebuilt on every run
    report.json      the numbers behind the report, also read by the web page
    REPORT.md        the latest report; reports/<season>_wNN.md keeps one per graded week
    status.json      what the last run did and whether any check failed

Three kinds of snapshot are kept. `official` is the pick of record, frozen on Thursday before the week's
first kickoff (later runs fill in any game that was missed, marked late_freeze). `early` is the Tuesday or
Wednesday pick, graded on its own line. `lookahead` is next week's games as seen on Thursday; it is used
only to notice a model that has stopped updating.
"""
from __future__ import annotations

import csv
import datetime as dt
import hashlib
import json
import math
from pathlib import Path

import pandas as pd

from . import params, weather
from .card import market_home_prob, week_card

UTC = dt.timezone.utc
GENESIS = "0" * 64
PICK_COLS = [
    "season", "week", "game_type", "game_id", "snapshot_type", "frozen_at_utc", "kickoff_utc", "away", "home",
    "model_version", "pred_margin", "pred_total", "home_win_prob", "qb_assumed", "wind_mph", "wind_source",
    "spread_line", "home_spread_odds", "away_spread_odds", "total_line", "over_odds", "under_odds",
    "home_moneyline", "away_moneyline", "market_home_prob", "ml_pick", "ml_pick_prob",
    "ats_side", "ats_pick", "ats_gap", "ats_prob", "ats_push", "ou_side", "ou_pick", "ou_gap", "ou_prob",
    "ou_push", "data_through", "games_csv_sha256", "late_freeze", "prev_hash", "row_hash",
]
GRADED_TYPES = ("official", "early")
PRICE = 100 / 110            # profit on a one-unit winner at -110
STALE_HOURS = 18             # a game this long past kickoff should have a score
MIN_PICKS_WIN_RATE = 270     # below this a win rate cannot tell skill from luck
MIN_PICKS_CLV = 120          # below this the average line move says nothing

WEEK_NOTE = "One week of results tells you nothing about the model; a perfect forecaster has losing weeks."
PAPER_NOTE = "A coin-flip picker paying standard prices is expected to be down about 4.5 units per 100 bets."
CLV_NOTE = "Too few picks to say whether the market is moving toward or away from these picks."


class LedgerError(RuntimeError):
    """The picks ledger was edited after the fact (its hash chain no longer verifies)."""


# ---------------------------------------------------------------- small helpers

def _fmt(value) -> str:
    """One canonical text form per value, so a row hashes the same after a round trip through CSV."""
    if value is None:
        return ""
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, bool):
        return str(int(value))
    if isinstance(value, float):
        if math.isnan(value):
            return ""
        return repr(round(value, 4))
    return str(value)


def _num(text) -> float | None:
    return None if text in ("", None) else float(text)


def _row_hash(row: dict) -> str:
    body = "|".join(row[c] for c in PICK_COLS if c != "row_hash")
    return hashlib.sha256(body.encode()).hexdigest()


def kickoff_utc(gameday: str, gametime) -> dt.datetime:
    """Kickoff in UTC; a game with no time yet is assumed to start at 1 PM Eastern."""
    start = weather.kickoff(str(gameday), gametime) or weather.kickoff(str(gameday), "13:00")
    return start.astimezone(UTC)


def snapshot_type(now: dt.datetime) -> str:
    """Tuesday and Wednesday runs are the early look; Thursday through Monday freeze the pick of record."""
    return "early" if now.astimezone(UTC).weekday() in (1, 2) else "official"


def model_version(commit: str | None = None) -> str:
    """The settings' fingerprint, plus the code commit when known."""
    digest = params.digest(params.DEFAULT)
    return f"{commit[:7]}+{digest}" if commit else digest


def file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest() if Path(path).exists() else ""


# ---------------------------------------------------------------- the ledger

def read_picks(state_dir: Path, verify: bool = True) -> list[dict]:
    """Every frozen pick as text fields, oldest first. Raises LedgerError when the hash chain is broken."""
    path = Path(state_dir) / "picks.csv"
    if not path.exists():
        return []
    with path.open(newline="") as fh:
        rows = list(csv.DictReader(fh))
    if verify:
        prev = GENESIS
        for i, r in enumerate(rows, start=2):
            if r.get("prev_hash") != prev or _row_hash(r) != r.get("row_hash"):
                raise LedgerError(f"picks.csv line {i} ({r.get('game_id')}, {r.get('snapshot_type')}) "
                                  "does not match its hash; the ledger was changed after it was written")
            prev = r["row_hash"]
    return rows


def _append_picks(state_dir: Path, new_rows: list[dict], existing: list[dict]) -> None:
    path = Path(state_dir) / "picks.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    prev = existing[-1]["row_hash"] if existing else GENESIS
    fresh = not path.exists()
    with path.open("a", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=PICK_COLS, lineterminator="\n")
        if fresh:
            writer.writeheader()
        for r in new_rows:
            r["prev_hash"] = prev
            r["row_hash"] = prev = _row_hash(r)
            writer.writerow(r)


def _pick_row(card: dict, game: pd.Series, kind: str, now: dt.datetime, version: str, through: str,
              games_sha: str, late: bool) -> dict:
    ats_side = "" if card["ats_pick"] is None else ("home" if card["ats_pick"].startswith(card["home"] + " ") else "away")
    ou_side = "" if card["ou_pick"] is None else ("over" if card["ou_pick"].startswith("Over") else "under")
    values = {
        "season": int(game["season"]), "week": int(game["week"]), "game_type": game.get("game_type", ""),
        "game_id": card["game_id"], "snapshot_type": kind,
        "frozen_at_utc": now.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "kickoff_utc": kickoff_utc(game["gameday"], game.get("gametime")).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "away": card["away"], "home": card["home"], "model_version": version,
        "pred_margin": card["pred_margin"], "pred_total": card["pred_total"],
        "home_win_prob": card["home_win_prob"], "qb_assumed": card["qb_assumed"],
        "wind_mph": card["wind_mph"], "wind_source": card["wind_source"],
        "spread_line": card["spread_line"], "home_spread_odds": game.get("home_spread_odds"),
        "away_spread_odds": game.get("away_spread_odds"), "total_line": card["total_line"],
        "over_odds": game.get("over_odds"), "under_odds": game.get("under_odds"),
        "home_moneyline": game.get("home_moneyline"), "away_moneyline": game.get("away_moneyline"),
        "market_home_prob": card["market_home_prob"], "ml_pick": card["ml_pick"],
        "ml_pick_prob": card["ml_pick_prob"], "ats_side": ats_side, "ats_pick": card["ats_pick"],
        "ats_gap": card["ats_gap"], "ats_prob": card["ats_prob"], "ats_push": card["ats_push"],
        "ou_side": ou_side, "ou_pick": card["ou_pick"], "ou_gap": card["ou_gap"], "ou_prob": card["ou_prob"],
        "ou_push": card["ou_push"], "data_through": through, "games_csv_sha256": games_sha,
        "late_freeze": late, "prev_hash": "", "row_hash": "",
    }
    return {c: _fmt(values[c]) for c in PICK_COLS}


def data_through(games: pd.DataFrame) -> str:
    done = games[games["result"].notna()]
    return "" if done.empty else str(done["gameday"].max())


def stale_games(games: pd.DataFrame, now: dt.datetime) -> list[str]:
    """Games that kicked off more than STALE_HOURS ago and still have no score in the data."""
    recent = games[games["result"].isna() & (games["gameday"].astype(str) <= now.strftime("%Y-%m-%d"))]
    limit = now - dt.timedelta(hours=STALE_HOURS)
    return [r["game_id"] for _, r in recent.iterrows() if kickoff_utc(r["gameday"], r.get("gametime")) < limit]


def current_weeks(games: pd.DataFrame, now: dt.datetime) -> list[tuple[int, int]]:
    """(season, week) of the earliest game that has not kicked off, and of the week after it."""
    todo = games[games["result"].isna()].sort_values(["gameday", "game_id"])
    todo = todo[[kickoff_utc(r["gameday"], r.get("gametime")) > now for _, r in todo.iterrows()]]
    weeks = list(dict.fromkeys((int(s), int(w)) for s, w in zip(todo["season"], todo["week"])))
    return weeks[:2]


def freeze(state_dir: Path, preds: pd.DataFrame, now: dt.datetime, version: str, games_sha: str = "",
           allow_early: bool = True) -> list[dict]:
    """Append this run's picks for games that have not kicked off. Returns the rows written.

    Idempotent: a (game_id, snapshot_type) already in the ledger is never written again."""
    existing = read_picks(state_dir)
    have = {(r["game_id"], r["snapshot_type"]) for r in existing}
    kind = snapshot_type(now)
    if kind == "early" and not allow_early:
        return []
    weeks = current_weeks(preds, now)
    plan = [(wk, kind) for wk in weeks[:1]]
    if kind == "official":
        plan += [(wk, "lookahead") for wk in weeks[1:2]]
    late = kind == "official" and now.astimezone(UTC).weekday() != 3
    through = data_through(preds)
    new = []
    for (season, week), snap in plan:
        games = preds[(preds["season"] == season) & (preds["week"] == week)].set_index("game_id", drop=False)
        for card in week_card(preds, season, week):
            game = games.loc[card["game_id"]]
            if (card["game_id"], snap) in have or not pd.isna(game["result"]):
                continue
            if kickoff_utc(game["gameday"], game.get("gametime")) <= now:
                continue
            if math.isnan(card["pred_margin"]) or math.isnan(card["pred_total"]):
                continue
            new.append(_pick_row(card, game, snap, now, version, through, games_sha, late and snap == "official"))
    if new:
        _append_picks(state_dir, new, existing)
    return new


# ---------------------------------------------------------------- grading

GRADE_COLS = [
    "season", "week", "game_id", "snapshot_type", "away", "home", "away_score", "home_score",
    "ml_pick", "ml_result", "fav_result", "ats_pick", "ats_result", "ou_pick", "ou_result",
    "spread_frozen", "spread_close", "clv_spread", "total_frozen", "total_close", "clv_total",
    "margin_err", "margin_err_line", "total_err", "total_err_line", "brier", "brier_market", "units",
]


def _wlp(sign: float, actual: float, line: float) -> str:
    edge = sign * (actual - line)
    return "W" if edge > 0 else "L" if edge < 0 else "P"


def grade_pick(pick: dict, game: pd.Series) -> dict:
    """Grade one frozen pick against the final score and the closing line (nflverse keeps the close)."""
    result, total = float(game["result"]), float(game["total"])
    margin, ptotal, p_home = float(pick["pred_margin"]), float(pick["pred_total"]), float(pick["home_win_prob"])
    close_s, close_t = game.get("spread_line"), game.get("total_line")
    out = {c: "" for c in GRADE_COLS}
    out.update({c: pick[c] for c in ("season", "week", "game_id", "snapshot_type", "away", "home", "ml_pick",
                                     "ats_pick", "ou_pick")})
    out.update(away_score=int(game["away_score"]), home_score=int(game["home_score"]))
    if result != 0:
        out["ml_result"] = "W" if (p_home >= 0.5) == (result > 0) else "L"
        if not pd.isna(close_s) and close_s != 0:
            out["fav_result"] = "W" if (close_s > 0) == (result > 0) else "L"
        out["brier"] = (p_home - (result > 0)) ** 2
        market = market_home_prob(game.get("home_moneyline"), game.get("away_moneyline"))
        if market is not None:
            out["brier_market"] = (market - (result > 0)) ** 2
    units = 0.0
    frozen_s, frozen_t = _num(pick["spread_line"]), _num(pick["total_line"])
    if frozen_s is not None and pick["ats_side"]:
        sign = 1.0 if pick["ats_side"] == "home" else -1.0
        out["ats_result"] = _wlp(sign, result, frozen_s)
        out["spread_frozen"] = frozen_s
        units += {"W": PRICE, "L": -1.0, "P": 0.0}[out["ats_result"]]
        if not pd.isna(close_s):
            out.update(spread_close=float(close_s), clv_spread=sign * (float(close_s) - frozen_s))
    if frozen_t is not None and pick["ou_side"]:
        sign = 1.0 if pick["ou_side"] == "over" else -1.0
        out["ou_result"] = _wlp(sign, total, frozen_t)
        out["total_frozen"] = frozen_t
        units += {"W": PRICE, "L": -1.0, "P": 0.0}[out["ou_result"]]
        if not pd.isna(close_t):
            out.update(total_close=float(close_t), clv_total=sign * (float(close_t) - frozen_t))
    out["units"] = units
    out["margin_err"] = abs(result - margin)
    out["total_err"] = abs(total - ptotal)
    if not pd.isna(close_s):
        out["margin_err_line"] = abs(result - float(close_s))
    if not pd.isna(close_t):
        out["total_err_line"] = abs(total - float(close_t))
    return {c: _fmt(v) if not isinstance(v, str) else v for c, v in out.items()}


def complete_weeks(games: pd.DataFrame) -> set[tuple[int, int]]:
    """Weeks in which every game has a final score. A partial week is never graded."""
    by_week = games.groupby(["season", "week"])["result"].apply(lambda s: s.notna().all())
    return {(int(s), int(w)) for (s, w), ok in by_week.items() if ok}


def grade(state_dir: Path, games: pd.DataFrame) -> list[dict]:
    """Rebuild grades.csv from the ledger and the final scores. Returns the grade rows."""
    picks = [p for p in read_picks(state_dir) if p["snapshot_type"] in GRADED_TYPES]
    done = complete_weeks(games)
    by_id = games.set_index("game_id", drop=False)
    rows = []
    for p in picks:
        if (int(p["season"]), int(p["week"])) not in done or p["game_id"] not in by_id.index:
            continue
        rows.append(grade_pick(p, by_id.loc[p["game_id"]]))
    path = Path(state_dir) / "grades.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=GRADE_COLS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    return rows


# ---------------------------------------------------------------- summary statistics

def _mean_se(values: list[float]) -> tuple[float, float] | tuple[None, None]:
    n = len(values)
    if n == 0:
        return None, None
    mean = sum(values) / n
    if n < 2:
        return mean, None
    var = sum((v - mean) ** 2 for v in values) / (n - 1)
    return mean, math.sqrt(var / n)


def _record(results: list[str]) -> dict:
    w, l, p = results.count("W"), results.count("L"), results.count("P")
    n = w + l
    half = 2 * math.sqrt(0.25 / n) if n else None
    return {"w": w, "l": l, "p": p, "rate": w / n if n else None,
            "luck_lo": max(0.0, 0.5 - half) if n else None, "luck_hi": min(1.0, 0.5 + half) if n else None}


def _diff(rows: list[dict], a: str, b: str) -> dict:
    pairs = [(float(r[a]), float(r[b])) for r in rows if r[a] != "" and r[b] != ""]
    mean, se = _mean_se([x - y for x, y in pairs])
    return {"n": len(pairs), "model": _mean_se([x for x, _ in pairs])[0], "market": _mean_se([y for _, y in pairs])[0],
            "diff": mean, "pm": None if se is None else 2 * se}


def _clv(rows: list[dict]) -> dict:
    values = [float(r[c]) for r in rows for c in ("clv_spread", "clv_total") if r[c] != ""]
    mean, se = _mean_se(values)
    moved = [v for v in values if v != 0]
    return {"n": len(values), "mean": mean, "pm": None if se is None else 2 * se,
            "t": None if not se else mean / se,
            "beat_rate": sum(v > 0 for v in moved) / len(moved) if moved else None,
            "unchanged": (len(values) - len(moved)) / len(values) if values else None}


def _paper(rows: list[dict]) -> dict:
    bets = sum((r["ats_result"] != "") + (r["ou_result"] != "") for r in rows)
    units = sum(float(r["units"]) for r in rows if r["units"] != "")
    expected = -bets * (1 - 0.5 * (1 + PRICE))
    spread = 2 * 0.955 * math.sqrt(bets) if bets else 0.0
    return {"bets": bets, "units": units, "luck_lo": expected - spread, "luck_hi": expected + spread}


def summarize(rows: list[dict]) -> dict:
    """Every reported number for one set of graded picks."""
    return {
        "games": len(rows),
        "winners": _record([r["ml_result"] for r in rows if r["ml_result"]]),
        "favorites": _record([r["fav_result"] for r in rows if r["ml_result"] and r["fav_result"]]),
        "spread": _record([r["ats_result"] for r in rows if r["ats_result"]]),
        "total": _record([r["ou_result"] for r in rows if r["ou_result"]]),
        "margin_mae": _diff(rows, "margin_err", "margin_err_line"),
        "total_mae": _diff(rows, "total_err", "total_err_line"),
        "brier": _diff(rows, "brier", "brier_market"),
        "clv": _clv(rows),
        "paper": _paper(rows),
    }


# ---------------------------------------------------------------- health checks

def check_before(preds: pd.DataFrame, now: dt.datetime, picks: list[dict]) -> tuple[list[str], list[str]]:
    """Checks on the data and on this week's predictions. Returns (red alarms, amber notes)."""
    red, amber = [], []
    stale = stale_games(preds, now)
    if stale:
        red.append(f"{len(stale)} game(s) finished more than {STALE_HOURS} hours ago but have no score in the "
                   f"data yet ({', '.join(stale[:3])}); results have not been refreshed")
    weeks = current_weeks(preds, now)
    if not weeks:
        return red, amber
    season, week = weeks[0]
    rows = preds[(preds["season"] == season) & (preds["week"] == week)]
    missing = rows[rows["pred_margin"].isna() | rows["pred_total"].isna()]
    if len(missing):
        red.append(f"{len(missing)} game(s) in week {week} have no prediction")
    rows = rows.dropna(subset=["pred_margin", "pred_total"])
    lined = rows.dropna(subset=["spread_line", "total_line"])
    if snapshot_type(now) == "official" and len(rows) - len(lined) > 2:
        red.append(f"{len(rows) - len(lined)} game(s) in week {week} have no betting line")
    if len(lined) >= 8:
        gm, gt = lined["pred_margin"] - lined["spread_line"], lined["pred_total"] - lined["total_line"]
        if lined["pred_margin"].corr(lined["spread_line"]) < 0.5:
            red.append("the model's margins barely track the betting spreads this week")
        if gm.abs().mean() > 4.0 or gt.abs().mean() > 4.0:
            red.append(f"the model is unusually far from the lines this week (average gap {gm.abs().mean():.1f} "
                       f"points on spreads, {gt.abs().mean():.1f} on totals)")
        if abs(gt.mean()) > 3.5 or abs(gm.mean()) > 2.5:
            red.append(f"the model is tilted one way against the lines this week (totals {gt.mean():+.1f}, "
                       f"spreads {gm.mean():+.1f} on average)")
    if len(rows) and (not rows["pred_total"].between(30, 62).all() or (rows["pred_margin"].abs() > 24).any()):
        red.append("a prediction is outside any score the model has produced before")
    for _, r in lined.iterrows():
        if abs(r["pred_margin"] - r["spread_line"]) > 10:
            amber.append(f"{r['away_team']} at {r['home_team']}: the model is more than 10 points from the spread, "
                         "usually a sign of quarterback news the model has not seen")
    # A model that has stopped updating repeats last week's look-ahead numbers exactly.
    ahead = {p["game_id"]: p for p in picks if p["snapshot_type"] == "lookahead"}
    both = [r for _, r in rows.iterrows() if r["game_id"] in ahead]
    if len(both) >= 8:
        same = sum(abs(float(ahead[r["game_id"]]["pred_margin"]) - r["pred_margin"]) < 0.01 for r in both)
        if same / len(both) > 0.5:
            red.append("more than half of this week's predictions are unchanged from a week ago; "
                       "the model does not appear to have taken in last week's games")
    return red, amber


def check_after(grades: list[dict]) -> tuple[list[str], list[str]]:
    """Checks on graded results (official picks). Returns (red alarms, amber notes)."""
    red, amber = [], []
    official = [g for g in grades if g["snapshot_type"] == "official"]
    weeks = sorted({(int(g["season"]), int(g["week"])) for g in official})
    if not weeks:
        return red, amber
    by_week = {wk: [g for g in official if (int(g["season"]), int(g["week"])) == wk] for wk in weeks}
    last = by_week[weeks[-1]]
    m, t = _diff(last, "margin_err", "margin_err_line"), _diff(last, "total_err", "total_err_line")
    if m["n"] >= 10 and m["diff"] > 2.5:
        red.append(f"last week the model's margin error was {m['diff']:.1f} points worse than the line's")
    elif m["n"] >= 10 and m["diff"] > 1.5:
        amber.append(f"last week the model's margin error was {m['diff']:.1f} points worse than the line's")
    if t["n"] >= 10 and t["diff"] > 2.0:
        red.append(f"last week the model's total error was {t['diff']:.1f} points worse than the line's")
    elif t["n"] >= 10 and t["diff"] > 1.5:
        amber.append(f"last week the model's total error was {t['diff']:.1f} points worse than the line's")
    if len(weeks) >= 4 and all(wk[0] == weeks[-1][0] for wk in weeks[-4:]):
        four = [g for wk in weeks[-4:] for g in by_week[wk]]
        m, t = _diff(four, "margin_err", "margin_err_line"), _diff(four, "total_err", "total_err_line")
        if m["diff"] is not None and m["diff"] > 1.0:
            red.append(f"over four weeks the model's margin error is {m['diff']:.1f} points worse than the line's")
        if t["diff"] is not None and t["diff"] > 1.25:
            red.append(f"over four weeks the model's total error is {t['diff']:.1f} points worse than the line's")
    season = [g for g in official if int(g["season"]) == weeks[-1][0]]
    clv = _clv(season)
    if clv["n"] >= MIN_PICKS_CLV and clv["t"] is not None and clv["t"] < -2.5:
        amber.append("the betting lines have been moving against these picks all season, which means the "
                     "market knows something the model does not; it is not bad luck")
    return red, amber


# ---------------------------------------------------------------- the report

def _pct(x) -> str:
    return "n/a" if x is None else f"{x:.1%}"


def _rec_line(name: str, rec: dict) -> str:
    n = rec["w"] + rec["l"]
    if not n:
        return f"- {name}: no graded picks yet"
    push = f", {rec['p']} push" + ("es" if rec["p"] != 1 else "") if rec["p"] else ""
    line = f"- {name}: {rec['w']}-{rec['l']}{push} ({_pct(rec['rate'])})"
    if n < MIN_PICKS_WIN_RATE:
        line += (f". With {n} picks, a coin flip would land anywhere from {_pct(rec['luck_lo'])} to "
                 f"{_pct(rec['luck_hi'])}. This record cannot yet tell skill from luck.")
    return line


def _diff_line(name: str, d: dict, unit: str, digits: int = 2) -> str:
    if not d["n"]:
        return f"- {name}: nothing to compare yet"
    pm = "" if d["pm"] is None else f" ± {d['pm']:.{digits}f}"
    unit = f" {unit}" if unit else ""
    word = "worse" if d["diff"] > 0 else "better"
    sure = "" if d["pm"] is None or abs(d["diff"]) > d["pm"] else "; the difference is within the range of chance"
    return (f"- {name}: model {d['model']:.{digits}f}, market {d['market']:.{digits}f}. The model is "
            f"{abs(d['diff']):.{digits}f}{pm}{unit} {word} over {d['n']} games{sure}")


def _clv_lines(label: str, c: dict) -> list[str]:
    if not c["n"]:
        return [f"- {label}: no lines to compare yet"]
    pm = "" if c["pm"] is None else f" ± {c['pm']:.2f}"
    out = [f"- {label}: the line moved {c['mean']:+.2f}{pm} points toward the pick on average over {c['n']} picks; "
           f"{_pct(c['beat_rate'])} of the picks whose line moved got the better number"]
    if c["n"] < MIN_PICKS_CLV:
        out.append(f"  {CLV_NOTE}")
    return out


def _trust_table(official: dict) -> list[str]:
    n_sp = official["spread"]["w"] + official["spread"]["l"]
    n_clv = official["clv"]["n"]
    return [
        "| Number | Picks so far | Needed before it means anything |",
        "|---|---|---|",
        f"| Spread or total win rate | {n_sp} spread, {official['total']['w'] + official['total']['l']} total | "
        f"about {MIN_PICKS_WIN_RATE} to see a 56% picker, several seasons to see 53% |",
        f"| Error against the line | {official['margin_mae']['n']} games | about one season to see a change of half a point |",
        f"| Line movement toward the pick | {n_clv} | about {MIN_PICKS_CLV}; this is the fastest honest signal |",
        f"| Paper units | {official['paper']['bets']} bets | never within one season |",
    ]


def build_report(state_dir: Path, preds: pd.DataFrame, grades: list[dict], now: dt.datetime,
                 alarms: tuple[list[str], list[str]], model_note: str = "Nothing.") -> dict:
    """The numbers for the report and the page (also written to report.json)."""
    weeks = sorted({(int(g["season"]), int(g["week"])) for g in grades})
    report = {"generated_at": now.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
              "red": alarms[0], "amber": alarms[1], "model_change": model_note,
              "season": None, "week": None, "last_week": None, "season_to_date": None, "games": []}
    if not weeks:
        return report
    season, week = weeks[-1]
    in_season = [g for g in grades if int(g["season"]) == season]
    pick = lambda rows, kind: [g for g in rows if g["snapshot_type"] == kind]  # noqa: E731
    last = [g for g in in_season if int(g["week"]) == week]
    report.update(
        season=season, week=week,
        last_week={k: summarize(pick(last, k)) for k in GRADED_TYPES},
        season_to_date={k: summarize(pick(in_season, k)) for k in GRADED_TYPES},
        games=[{c: g[c] for c in ("away", "home", "away_score", "home_score", "ml_pick", "ml_result", "ats_pick",
                                  "ats_result", "ou_pick", "ou_result", "spread_frozen", "spread_close",
                                  "total_frozen", "total_close")} for g in pick(last, "official")],
    )
    return report


def render_report(report: dict) -> str:
    out = ["# NFL model: weekly report", ""]
    out += ["## 1. Health", ""]
    if report["red"]:
        out += ["**A check failed. Do not trust this week's numbers until it is looked at.**", ""]
        out += [f"- {a}" for a in report["red"]]
    else:
        out.append("All checks passed.")
    out += [f"- Note: {a}" for a in report["amber"]]
    out.append("")
    if report["season"] is None:
        out += ["No week has been graded yet. Picks are frozen each Thursday and graded once every game of the "
                "week has a final score.", ""]
        return "\n".join(out)
    season, week = report["season"], report["week"]
    out += [f"## 2. Week {week} games ({season})", "",
            "| Game | Score | Winner pick | Spread pick (line then, close) | Total pick (line then, close) |",
            "|---|---|---|---|---|"]
    mark = {"W": "won", "L": "lost", "P": "push", "": "no pick"}
    for g in report["games"]:
        ats = f"{g['ats_pick']} {mark[g['ats_result']]} ({g['spread_frozen']}, {g['spread_close']})" if g["ats_pick"] else "no line"
        ou = f"{g['ou_pick']} {mark[g['ou_result']]} ({g['total_frozen']}, {g['total_close']})" if g["ou_pick"] else "no line"
        out.append(f"| {g['away']} at {g['home']} | {g['away_score']}-{g['home_score']} | "
                   f"{g['ml_pick']} {mark[g['ml_result']] if g['ml_result'] else 'tie'} | {ats} | {ou} |")
    lw = report["last_week"]["official"]
    out += ["", f"Week {week}: winners {lw['winners']['w']}-{lw['winners']['l']}, spread "
            f"{lw['spread']['w']}-{lw['spread']['l']}, totals {lw['total']['w']}-{lw['total']['l']}.", "", WEEK_NOTE, ""]
    std, early = report["season_to_date"]["official"], report["season_to_date"]["early"]
    fav = std["favorites"]
    out += [f"## 3. Season so far ({season}, Thursday picks)", "",
            _rec_line("Winners", std["winners"]) + (f" The betting favorite went {fav['w']}-{fav['l']} in the same games."
                                                    if fav["w"] + fav["l"] else ""),
            _rec_line("Against the spread", std["spread"]), _rec_line("Totals", std["total"]), ""]
    out += ["## 4. Forecast accuracy against the market", "",
            _diff_line("Margin error (points)", std["margin_mae"], "points"),
            _diff_line("Total error (points)", std["total_mae"], "points"),
            _diff_line("Win chance error (Brier score, lower is better)", std["brier"], "", 4), ""]
    out += ["## 5. Did the market move our way?", "",
            "A pick that the closing line agrees with more than the line it was made at is the best early sign of "
            "real skill. About +0.8 points on average would be needed to break even.", ""]
    out += _clv_lines("Thursday picks", std["clv"]) + _clv_lines("Tuesday picks", early["clv"]) + [""]
    p = std["paper"]
    out += ["## 6. Paper bets", "",
            f"One unit on every Thursday spread and total pick at -110: {p['units']:+.1f} units on {p['bets']} bets; "
            f"luck alone ranges from {p['luck_lo']:+.1f} to {p['luck_hi']:+.1f}.", "", PAPER_NOTE, ""]
    out += ["## 7. How much to trust this", ""] + _trust_table(std) + [""]
    out += ["## 8. What changed in the model", "", report["model_change"], ""]
    return "\n".join(out)


def write_report(state_dir: Path, report: dict) -> None:
    state_dir = Path(state_dir)
    state_dir.mkdir(parents=True, exist_ok=True)
    text = render_report(report)
    (state_dir / "report.json").write_text(json.dumps(report, indent=1, sort_keys=True) + "\n")
    (state_dir / "REPORT.md").write_text(text)
    if report["season"] is not None:
        (state_dir / "reports").mkdir(exist_ok=True)
        (state_dir / "reports" / f"{report['season']}_w{report['week']:02d}.md").write_text(text)


def write_status(state_dir: Path, status: dict) -> None:
    Path(state_dir).mkdir(parents=True, exist_ok=True)
    (Path(state_dir) / "status.json").write_text(json.dumps(status, indent=1, sort_keys=True) + "\n")


def read_json(state_dir: Path, name: str) -> dict | None:
    path = Path(state_dir) / name
    return json.loads(path.read_text()) if path.exists() else None


def run(state_dir: Path, preds: pd.DataFrame, now: dt.datetime, version: str, games_sha: str = "",
        run_url: str = "", games: pd.DataFrame | None = None, data_dir: str | None = None) -> dict:
    """One pass of the loop: check, freeze, grade, test alternative settings, report. Safe to repeat.

    `preds` is card.predict_all output. With `games` (the full games.csv) and `data_dir` the alternative
    settings are tested too."""
    state_dir = Path(state_dir)
    status = {"updated_at": now.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"), "run_url": run_url,
              "model_version": version, "ok": False, "red": [], "amber": [], "froze": 0, "graded_weeks": 0,
              "snapshot": snapshot_type(now), "summary": "", "promote": False}
    try:
        picks = read_picks(state_dir)
        red, amber = check_before(preds, now, picks)
        # An early look built on stale results would be wrong in a way that cannot be undone, so skip it.
        new = freeze(state_dir, preds, now, version, games_sha, allow_early=not red)
        grades = grade(state_dir, preds)
        red2, amber2 = check_after(grades)
        red, amber = red + red2, amber + amber2
        model_note = "Nothing changed. Alternative settings were not tested this run."
        if red:
            model_note = "Nothing changed. Alternative settings are not tested while a check is failing."
        elif games is not None and data_dir is not None:
            from . import challenge
            tested = challenge.run(state_dir, games, preds, str(data_dir), now)
            status["promote"] = bool(tested["promote"])
            model_note = "Nothing changed in the model this week. " + challenge.summary_line(tested)
        report = build_report(state_dir, preds, grades, now, (red, amber), model_note)
        write_report(state_dir, report)
        weeks = {(g["season"], g["week"]) for g in grades}
        status.update(ok=not red, red=red, amber=amber, froze=len(new), graded_weeks=len(weeks),
                      season=report["season"], week=report["week"])
        status["summary"] = (f"froze {len(new)} pick(s), {len(weeks)} week(s) graded"
                             + (f", {len(red)} check(s) failed" if red else ""))
    except LedgerError as err:
        status.update(red=[str(err)], summary="picks ledger failed its integrity check")
        write_status(state_dir, status)
        raise
    except Exception as err:   # leave a trace for the page before failing the run
        status.update(red=[f"the weekly run stopped with an error ({type(err).__name__}: {err})"],
                      summary="run failed")
        write_status(state_dir, status)
        raise
    write_status(state_dir, status)
    return status
