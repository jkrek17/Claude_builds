"""Minimal client for The Odds API v4 (https://the-odds-api.com), standard library only."""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://api.the-odds-api.com/v4"


class OddsApiError(RuntimeError):
    pass


def fetch_odds(api_key: str, sport: str, bookmakers: list[str], markets: list[str]) -> tuple[list[dict], str | None]:
    """Return (events, requests remaining this month) for one sport."""
    query = urllib.parse.urlencode({
        "apiKey": api_key,
        "bookmakers": ",".join(bookmakers),
        "markets": ",".join(markets),
        "oddsFormat": "decimal",
        "dateFormat": "iso",
    })
    try:
        with urllib.request.urlopen(f"{BASE}/sports/{sport}/odds?{query}", timeout=30) as resp:
            return json.load(resp), resp.headers.get("x-requests-remaining")
    except urllib.error.HTTPError as err:
        hints = {401: "the API key was rejected", 404: "unknown sport key", 422: "bad request parameters",
                 429: "the monthly quota is used up or requests are too frequent"}
        raise OddsApiError(f"{sport}: HTTP {err.code}, {hints.get(err.code, err.reason)}") from None
    except urllib.error.URLError as err:
        raise OddsApiError(f"{sport}: could not reach The Odds API ({err.reason})") from None
