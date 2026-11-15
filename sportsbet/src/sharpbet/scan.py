"""Compare each book's price with the de-vigged sharp line and return the +EV plays."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from .odds_math import expected_value, fair_probs, kelly_fraction

MARKETS = ("h2h", "spreads", "totals")
MARKET_NAMES = {"h2h": "Moneyline", "spreads": "Spread", "totals": "Total"}


@dataclass(frozen=True)
class Play:
    event_id: str
    sport: str
    matchup: str
    commence: datetime
    market: str
    selection: str
    book: str
    price: float
    sharp_price: float
    fair_prob: float
    ev: float
    kelly: float


def parse_time(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _selection(market: str, outcome: dict) -> str:
    point = outcome.get("point")
    if market == "spreads":
        return f"{outcome['name']} {point:+g}"
    if market == "totals":
        return f"{outcome['name']} {point:g}"
    return outcome["name"]


def _fresh(market: dict, book: dict, now: datetime, max_age: timedelta) -> bool:
    stamp = market.get("last_update") or book.get("last_update")
    return bool(stamp) and now - parse_time(stamp) <= max_age


def find_plays(
    events: list[dict],
    now: datetime,
    *,
    sharp_book: str = "pinnacle",
    books: set[str] | None = None,
    min_ev: float = 0.02,
    max_ev: float = 0.15,
    max_price: float = 6.0,
    max_age_minutes: float = 20,
) -> list[Play]:
    """Return every play whose EV against the sharp fair line is within [min_ev, max_ev].

    A line is only compared when the book offers the same point as the sharp book. Quotes older than
    max_age_minutes are ignored, and an edge above max_ev is treated as a stale or wrong line.
    """
    max_age = timedelta(minutes=max_age_minutes)
    plays: list[Play] = []
    for event in events:
        commence = parse_time(event["commence_time"])
        if commence <= now:
            continue  # pre-game only
        by_key = {b["key"]: b for b in event.get("bookmakers", [])}
        sharp = by_key.get(sharp_book)
        if not sharp:
            continue
        for sharp_market in sharp.get("markets", []):
            mkey = sharp_market["key"]
            outcomes = sharp_market.get("outcomes", [])
            if mkey not in MARKETS or len(outcomes) < 2 or not _fresh(sharp_market, sharp, now, max_age):
                continue
            if any(o["price"] <= 1 for o in outcomes):
                continue
            fair = {
                (o["name"], o.get("point")): (prob, o["price"])
                for o, prob in zip(outcomes, fair_probs([o["price"] for o in outcomes]))
            }
            for bkey, book in by_key.items():
                if bkey == sharp_book or (books is not None and bkey not in books):
                    continue
                for market in book.get("markets", []):
                    if market["key"] != mkey or not _fresh(market, book, now, max_age):
                        continue
                    for o in market.get("outcomes", []):
                        match = fair.get((o["name"], o.get("point")))
                        price = o["price"]
                        if not match or not 1 < price <= max_price:
                            continue
                        prob, sharp_price = match
                        ev = expected_value(prob, price)
                        if min_ev <= ev <= max_ev:
                            plays.append(Play(
                                event_id=event["id"],
                                sport=event.get("sport_title") or event.get("sport_key", ""),
                                matchup=f"{event['away_team']} @ {event['home_team']}",
                                commence=commence,
                                market=MARKET_NAMES[mkey],
                                selection=_selection(mkey, o),
                                book=book.get("title", bkey),
                                price=price,
                                sharp_price=sharp_price,
                                fair_prob=prob,
                                ev=ev,
                                kelly=kelly_fraction(prob, price),
                            ))
    return sorted(plays, key=lambda p: p.ev, reverse=True)
