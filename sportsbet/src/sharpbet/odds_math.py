"""Odds conversions, vig removal, expected value and Kelly. All prices are decimal odds."""
from __future__ import annotations


def american_to_decimal(american: float) -> float:
    if american == 0:
        raise ValueError("American odds cannot be 0")
    return 1 + (american / 100 if american > 0 else 100 / -american)


def decimal_to_american(decimal: float) -> int:
    if decimal <= 1:
        raise ValueError("decimal odds must be above 1")
    return round((decimal - 1) * 100) if decimal >= 2 else round(-100 / (decimal - 1))


def _check(prices: list[float]) -> None:
    if len(prices) < 2 or any(p <= 1 for p in prices):
        raise ValueError("need at least two prices, each above 1.0")


def devig_multiplicative(prices: list[float]) -> list[float]:
    """Scale the implied probabilities so they sum to 1."""
    _check(prices)
    implied = [1 / p for p in prices]
    total = sum(implied)
    return [q / total for q in implied]


def devig_power(prices: list[float]) -> list[float]:
    """Raise each implied probability to a common power k so they sum to 1.

    Compared with the multiplicative method this takes more vig out of longshots, which matches the
    favourite-longshot bias seen in real markets.
    """
    _check(prices)
    implied = [1 / p for p in prices]
    lo, hi = 0.01, 100.0  # sum(q**k) falls as k rises
    for _ in range(100):
        mid = (lo + hi) / 2
        if sum(q**mid for q in implied) > 1:
            lo = mid
        else:
            hi = mid
    k = (lo + hi) / 2
    return [q**k for q in implied]


def fair_probs(prices: list[float]) -> list[float]:
    """Conservative fair win probabilities: the lower of the two de-vig methods for each outcome.

    These deliberately sum to slightly under 1, so an edge has to survive either method.
    """
    return [min(a, b) for a, b in zip(devig_multiplicative(prices), devig_power(prices))]


def expected_value(prob: float, price: float) -> float:
    """Expected profit per $1 staked."""
    return prob * price - 1


def kelly_fraction(prob: float, price: float) -> float:
    """Full-Kelly share of bankroll; 0 when there is no edge."""
    return max(0.0, expected_value(prob, price) / (price - 1))
