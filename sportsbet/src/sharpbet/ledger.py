"""A CSV ledger of bets placed, so the bankroll and daily exposure are tracked between runs."""
from __future__ import annotations

import csv
from datetime import date, datetime, timezone
from pathlib import Path

from .sizing import Pick

FIELDS = ["id", "placed_at", "sport", "event_id", "matchup", "commence", "market", "selection",
          "book", "price", "fair_prob", "ev", "stake", "status", "pnl"]
RESULTS = ("win", "loss", "push")


def load(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(newline="") as f:
        return list(csv.DictReader(f))


def _save(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def record(path: Path, picks: list[Pick], now: datetime) -> list[dict]:
    rows = load(path)
    next_id = max((int(r["id"]) for r in rows), default=0) + 1
    added = []
    for pick in picks:
        p = pick.play
        added.append({
            "id": next_id, "placed_at": now.astimezone(timezone.utc).isoformat(timespec="seconds"),
            "sport": p.sport, "event_id": p.event_id, "matchup": p.matchup,
            "commence": p.commence.isoformat(), "market": p.market, "selection": p.selection,
            "book": p.book, "price": f"{p.price:.3f}", "fair_prob": f"{p.fair_prob:.4f}",
            "ev": f"{p.ev:.4f}", "stake": f"{pick.stake:.2f}", "status": "open", "pnl": "",
        })
        next_id += 1
    _save(path, rows + added)
    return added


def settle(path: Path, bet_id: int, result: str) -> dict:
    if result not in RESULTS:
        raise ValueError(f"result must be one of {RESULTS}")
    rows = load(path)
    for row in rows:
        if int(row["id"]) == bet_id:
            stake, price = float(row["stake"]), float(row["price"])
            pnl = {"win": stake * (price - 1), "loss": -stake, "push": 0.0}[result]
            row["status"], row["pnl"] = result, f"{pnl:.2f}"
            _save(path, rows)
            return row
    raise KeyError(f"no bet with id {bet_id}")


def settled_pnl(rows: list[dict]) -> float:
    return sum(float(r["pnl"]) for r in rows if r["status"] in RESULTS)


def open_rows(rows: list[dict]) -> list[dict]:
    return [r for r in rows if r["status"] == "open"]


def staked_on(rows: list[dict], day: date) -> float:
    """Total staked on a local calendar day, settled or not."""
    return sum(float(r["stake"]) for r in rows
               if datetime.fromisoformat(r["placed_at"]).astimezone().date() == day)
