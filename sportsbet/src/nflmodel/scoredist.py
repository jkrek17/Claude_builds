"""Score distributions that respect key numbers.

NFL margins pile up on 3, 7, 10 and 6, which a normal curve misses: it puts about 5% of games on a
3-point margin when 15% land there. This module turns a centre and a standard deviation into a
distribution over whole-number outcomes, re-weighted by how often each number has actually come up,
so that push and half-point probabilities are realistic.
"""
from __future__ import annotations

import math

import numpy as np

MARGIN_RANGE = (-70, 70)
TOTAL_RANGE = (0, 130)
KAPPA = 5.0  # pseudo-count that keeps rarely seen numbers near the normal curve


def _cdf(x: np.ndarray) -> np.ndarray:
    return 0.5 * (1.0 + np.vectorize(math.erf, otypes=[float])(x / math.sqrt(2.0)))


def discrete_normal(center, sd: float, lo: int, hi: int) -> np.ndarray:
    """Rows of P(outcome = k) for k in lo..hi under a normal rounded to whole numbers."""
    c = np.atleast_1d(np.asarray(center, dtype=float))[:, None]
    k = np.arange(lo, hi + 1)[None, :]
    p = _cdf((k + 0.5 - c) / sd) - _cdf((k - 0.5 - c) / sd)
    return p / p.sum(1, keepdims=True)


def key_factors(centers, outcomes, sd: float, lo: int, hi: int, symmetric: bool) -> np.ndarray:
    """How much more (or less) often each whole number occurs than the normal curve expects.

    `centers` are forecasts for past games (use the closing line) and `outcomes` what happened.
    With `symmetric`, k and -k are pooled, which suits margins.
    """
    centers = np.asarray(centers, dtype=float)
    outcomes = np.asarray(outcomes, dtype=float)
    ok = np.isfinite(centers) & np.isfinite(outcomes)
    if not ok.any():  # no history yet: fall back to the plain normal curve
        return np.ones(hi - lo + 1)
    expected = discrete_normal(centers[ok], sd, lo, hi).sum(0)
    idx = (np.clip(np.rint(outcomes[ok]), lo, hi) - lo).astype(int)
    observed = np.bincount(idx, minlength=hi - lo + 1).astype(float)
    if symmetric:
        observed, expected = observed + observed[::-1], expected + expected[::-1]
    return (observed + KAPPA) / (expected + KAPPA)


def keyed_pmf(center: float, sd: float, factors: np.ndarray, lo: int, hi: int) -> np.ndarray:
    p = discrete_normal(center, sd, lo, hi)[0] * factors
    return p / p.sum()


def line_probs(pmf: np.ndarray, line: float, lo: int) -> tuple[float, float, float]:
    """P(outcome > line), P(outcome == line), P(outcome < line)."""
    k = np.arange(len(pmf)) + lo
    return (float(pmf[k > line + 1e-9].sum()), float(pmf[np.abs(k - line) < 1e-9].sum()),
            float(pmf[k < line - 1e-9].sum()))


def balanced_center(line: float, sd: float, factors: np.ndarray, lo: int, hi: int) -> float:
    """The centre at which the keyed distribution gives both sides of `line` the same chance.

    Re-weighting by key numbers moves the distribution's median slightly off its centre. A closing
    line is taken as a fair bet, so the market's view is the centre that balances the two sides.
    """
    def tilt(center: float) -> float:
        above, _, below = line_probs(keyed_pmf(center, sd, factors, lo, hi), line, lo)
        return above - below

    low, high = line - 6.0, line + 6.0
    for _ in range(40):
        mid = (low + high) / 2
        if tilt(mid) > 0:
            high = mid
        else:
            low = mid
    return (low + high) / 2
