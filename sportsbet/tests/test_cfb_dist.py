import json

import numpy as np
import pandas as pd
import pytest

from cfbmodel import dist
from nflmodel import scoredist


def synthetic_history(n=6000, seed=0, sd_a=14.0, sd_b=0.0, key3_share=None):
    """College-like games: home spread, total, and points drawn around the closing line.

    With `key3_share`, enough games are moved onto a 3-point margin (either side) that about that
    share of all games end with |margin| == 3."""
    rng = np.random.default_rng(seed)
    spread = np.round(rng.uniform(-28, 28, n) * 2) / 2
    total = np.round(rng.uniform(40, 70, n) * 2) / 2
    sd = sd_a + sd_b * np.abs(spread)
    margin = np.rint(-spread + rng.normal(0, 1, n) * sd)
    if key3_share is not None:
        natural = np.mean(np.abs(margin) == 3)
        move = rng.random(n) < (key3_share - natural) / (1 - natural)
        margin[move] = np.where(rng.random(move.sum()) < 0.5, 3, -3)
    points = np.rint(total + rng.normal(0, 14, n))
    home = np.ceil((points + margin) / 2)
    away = home - margin
    return pd.DataFrame({"spread_close": spread, "total_close": total,
                         "home_points": home, "away_points": away})


@pytest.fixture(scope="module")
def fit():
    return dist.fit(synthetic_history())


def test_fit_is_json_serializable(fit):
    back = json.loads(json.dumps(fit))
    assert back["margin_range"] == [-90, 90] and back["total_range"] == [0, 150]
    assert len(back["margin_factors"]) == 181 and len(back["total_factors"]) == 151


@pytest.mark.parametrize("pred,line", [(-7.0, -6.5), (3.0, -3.0), (-20.0, -14.0), (10.0, 7.0), (0.0, 0.0)])
def test_probs_sum_to_one(fit, pred, line):
    assert sum(dist.cover_prob(pred, line, fit)) == pytest.approx(1.0, abs=1e-9)
    assert sum(dist.over_prob(55.0 + pred, 54.5 + line, fit)) == pytest.approx(1.0, abs=1e-9)


def test_half_point_line_has_no_push(fit):
    assert dist.cover_prob(-10.0, -6.5, fit)[1] == 0.0
    assert dist.over_prob(58.0, 55.5, fit)[1] == 0.0
    assert dist.cover_prob(-10.0, -7.0, fit)[1] > 0.0
    assert dist.over_prob(58.0, 55.0, fit)[1] > 0.0


def test_cover_direction(fit):
    # line -7 (home gives 7): a model margin of home by 14 makes the home cover likely
    home, _, away = dist.cover_prob(14.0, -7.0, fit)
    assert home > 0.6 > away
    # a model margin equal to the market's (-line) is a coin flip on a half-point line
    home, _, away = dist.cover_prob(6.5, -6.5, fit)
    assert home == pytest.approx(away, abs=1e-6)
    over, _, under = dist.over_prob(60.0, 52.5, fit)
    assert over > 0.6 > under


def test_wider_sd_for_big_spreads():
    f = dist.fit(synthetic_history(sd_a=12.0, sd_b=0.25, seed=1))
    a, b = f["sd_margin"]
    assert b > 0.1
    assert a == pytest.approx(12.0, abs=1.5)
    assert dist.sd_margin(-24.0, f) > dist.sd_margin(-3.0, f)


# NFL comparison: a synthetic NFL-like sample (sd 13.5, the NFL margin range) where about 15% of games
# end on a 3-point margin, measured with scoredist.key_factors the way the NFL card does.
def nfl_factor_at_3() -> float:
    rng = np.random.default_rng(7)
    n = 6000
    spread = np.round(rng.uniform(-14, 14, n) * 2) / 2
    margin = np.rint(-spread + rng.normal(0, 13.5, n))
    natural = np.mean(np.abs(margin) == 3)
    move = rng.random(n) < (0.15 - natural) / (1 - natural)
    margin[move] = np.where(rng.random(move.sum()) < 0.5, 3, -3)
    assert np.mean(np.abs(margin) == 3) == pytest.approx(0.15, abs=0.015)
    lo, hi = scoredist.MARGIN_RANGE
    return float(scoredist.key_factors(-spread, margin, 13.5, lo, hi, True)[3 - lo])


def test_key_three_weaker_than_nfl():
    hist = synthetic_history(sd_a=16.0, key3_share=0.09, seed=2)  # college-like width
    margin = hist["home_points"] - hist["away_points"]
    assert np.mean(np.abs(margin) == 3) == pytest.approx(0.09, abs=0.01)
    f = dist.fit(hist)
    cfb3 = f["margin_factors"][3 - f["margin_range"][0]]
    assert cfb3 > 1.0  # 3 is still a key number in college
    assert cfb3 < nfl_factor_at_3()


# ---------------------------------------------------------------------------------------------
# shrinkage toward the line
# ---------------------------------------------------------------------------------------------

def shrink_history(beta_m=0.4, beta_t=0.3, n=8000, seed=3):
    """Games where outcome - line = beta * (model - line) + noise."""
    rng = np.random.default_rng(seed)
    h = synthetic_history(n=n, seed=seed)
    line_m = -h["spread_close"].to_numpy(float)
    x_m = rng.normal(0, 6, n)
    x_t = rng.normal(0, 6, n)
    margin = line_m + beta_m * x_m + rng.normal(0, 14, n)
    total = h["total_close"].to_numpy(float) + beta_t * x_t + rng.normal(0, 14, n)
    h["home_points"] = (total + margin) / 2
    h["away_points"] = (total - margin) / 2
    h["margin"] = line_m + x_m
    h["total"] = h["total_close"] + x_t
    return h


def test_fit_shrink_recovers_betas():
    s = dist.fit_shrink(shrink_history())
    assert s["beta_margin"] == pytest.approx(0.4, abs=0.05)
    assert s["beta_total"] == pytest.approx(0.3, abs=0.05)
    assert s["n_shrink_margin"] == 8000 and s["n_shrink_total"] == 8000
    json.dumps(s)


def test_fit_shrink_clips_to_unit_interval():
    s = dist.fit_shrink(shrink_history(beta_m=-0.5, beta_t=1.8))
    assert s["beta_margin"] == 0.0 and s["beta_total"] == 1.0


def test_fit_includes_betas_when_model_columns_present():
    f = dist.fit(shrink_history())
    assert f["beta_margin"] == pytest.approx(0.4, abs=0.05)
    assert "beta_margin" not in dist.fit(synthetic_history())


def test_probabilities_centre_on_shrunk_model(fit):
    half = {**fit, "beta_margin": 0.5, "beta_total": 0.25}
    full = {**fit, "beta_margin": 1.0, "beta_total": 1.0}
    # home margin line is +7 for spread -7; model says +15 -> shrunk centre 7 + 0.5 * 8 = 11
    assert dist.cover_prob(15.0, -7.0, half) == pytest.approx(dist.cover_prob(11.0, -7.0, full))
    assert dist.over_prob(62.0, 54.0, half) == pytest.approx(dist.over_prob(56.0, 54.0, full))
    # no betas stored: the model number is the centre, as before
    assert dist.cover_prob(15.0, -7.0, fit) == pytest.approx(dist.cover_prob(15.0, -7.0, full))


def test_zero_beta_gives_a_coin_flip(fit):
    zero = {**fit, "beta_margin": 0.0, "beta_total": 0.0}
    home, push, away = dist.cover_prob(30.0, -6.5, zero)
    assert home == pytest.approx(away, abs=1e-6) and push == 0.0
    over, _, under = dist.over_prob(80.0, 52.5, zero)
    assert over == pytest.approx(under, abs=1e-6)
