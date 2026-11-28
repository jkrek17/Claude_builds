"""College margin and total distributions that respect key numbers.

College margins spread wider than the NFL's and grow with the size of the spread, and 3 and 7 matter
less because scoring is higher and more varied. This module reuses nflmodel.scoredist and fits, from
past closing lines, how wide outcomes run around the line and how often each whole number comes up.

The fit is a plain dict of lists and floats so it can be saved as JSON.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from nflmodel import scoredist

MARGIN_RANGE = (-90, 90)
TOTAL_RANGE = (0, 150)
MIN_SD = 3.0  # floor so a fitted line can never give a degenerate distribution
_ABS_TO_SD = math.sqrt(math.pi / 2)  # E|N(0, s)| = s * sqrt(2 / pi)


def _linear_sd(x: np.ndarray, resid: np.ndarray) -> list[float]:
    """[intercept, slope] of sd = intercept + slope * x, fitted on |residual| scaled to an sd."""
    if len(x) < 2 or np.ptp(x) == 0:
        return [float(np.sqrt(np.mean(resid ** 2))) if len(resid) else 14.0, 0.0]
    slope, intercept = np.polyfit(x, np.abs(resid) * _ABS_TO_SD, 1)
    return [float(intercept), float(slope)]


def fit(history: pd.DataFrame) -> dict:
    """Spread and total widths plus key-number factors from finished games and their closing lines.

    `history` needs spread_close (home perspective, negative = home favoured), total_close, home_points
    and away_points. The line-implied home margin is -spread_close.
    """
    spread = history["spread_close"].to_numpy(float)
    total_line = history["total_close"].to_numpy(float)
    margin = (history["home_points"] - history["away_points"]).to_numpy(float)
    total = (history["home_points"] + history["away_points"]).to_numpy(float)

    m_ok = np.isfinite(spread) & np.isfinite(margin)
    t_ok = np.isfinite(total_line) & np.isfinite(total)
    m_resid = margin[m_ok] + spread[m_ok]
    t_resid = total[t_ok] - total_line[t_ok]

    # key_factors takes one sd, so the factors are measured with the pooled residual sd
    m_sd = float(np.sqrt(np.mean(m_resid ** 2))) if m_ok.any() else 16.0
    t_sd = float(np.sqrt(np.mean(t_resid ** 2))) if t_ok.any() else 16.0
    lo, hi = MARGIN_RANGE
    tlo, thi = TOTAL_RANGE
    return {
        "margin_range": list(MARGIN_RANGE),
        "total_range": list(TOTAL_RANGE),
        "sd_margin": _linear_sd(np.abs(spread[m_ok]), m_resid),
        "sd_total": _linear_sd(total_line[t_ok], t_resid),
        "factor_sd_margin": max(m_sd, MIN_SD),
        "factor_sd_total": max(t_sd, MIN_SD),
        "margin_factors": [float(v) for v in scoredist.key_factors(
            -spread, margin, max(m_sd, MIN_SD), lo, hi, True)],
        "total_factors": [float(v) for v in scoredist.key_factors(
            total_line, total, max(t_sd, MIN_SD), tlo, thi, False)],
        "n_margin": int(m_ok.sum()),
        "n_total": int(t_ok.sum()),
    }


def sd_margin(spread: float, fit: dict) -> float:
    a, b = fit["sd_margin"]
    return max(a + b * abs(spread), MIN_SD)


def sd_total(total: float, fit: dict) -> float:
    c, d = fit["sd_total"]
    return max(c + d * total, MIN_SD)


def _probs(center: float, line: float, sd: float, factors: list[float], lo: int, hi: int):
    factors = np.asarray(factors, dtype=float)
    center += scoredist.balanced_center(line, sd, factors, lo, hi) - line  # a fair line is 50/50
    return scoredist.line_probs(scoredist.keyed_pmf(center, sd, factors, lo, hi), line, lo)


def cover_prob(pred_margin: float, line: float, fit: dict) -> tuple[float, float, float]:
    """(home covers, push, away covers) for home spread `line` (negative = home favoured).

    The home side covers when margin + line > 0."""
    lo, hi = fit["margin_range"]
    return _probs(pred_margin, -line, sd_margin(line, fit), fit["margin_factors"], lo, hi)


def over_prob(pred_total: float, line: float, fit: dict) -> tuple[float, float, float]:
    """(over, push, under) for total `line`."""
    lo, hi = fit["total_range"]
    return _probs(pred_total, line, sd_total(line, fit), fit["total_factors"], lo, hi)
