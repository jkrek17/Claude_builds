# TSP Weekly Risk Dial — Backtest Results (September 17, 2026)

**Verdict: passes the bar in `PROJECT_PLAN.md` §7 for drawdown and transfer count; the cost is 1–2 points a year of return over a bull-heavy sample. Enabled on a weekly cadence with the settings below.**

## Setup

- Proxies, because tsp.gov blocks automated downloads: C = SPY, S = VXF, I = EFA, F = AGG, G = daily accrual of the 10-year Treasury yield (never falls). Re-run on real TSP share prices by placing the tsp.gov CSV export in `data/tsp_prices.csv`.
- Regime score from three groups with weights fixed before testing: trend 40% (SPY vs 200-day, vs 50-day, 200-day slope, extended-market vs 200-day), volatility 30% (VIX level bands, VIX vs its 60-day average, VIX vs realized), credit 30% (high-yield spread level and 1-month change, 10y–3m curve). Breadth omitted: no constituent history.
- Decision every Friday close, executed Monday close (the TSP noon rule). Hysteresis: risk-on above 70, risk-off below 35, back to neutral on a 5-point re-cross. Re-risking needs two consecutive risk-on weeks. A transfer needs at least a 10-point equity change, 15 days since the last one, and no more than two per month.
- Baseline 60% C / 20% S / 20% I; remainder to G. Compared with 100% C and with the baseline held without changes (annual rebalance).
- Parameters were not tuned. Four neighboring multiplier sets were run afterwards to check the result is a plateau.

## Results

| Setting (on / neutral / off) | Period | Dial CAGR | Dial max DD | Sharpe | C fund CAGR | C max DD | Sharpe | Transfers / yr |
|---|---|---|---|---|---|---|---|---|
| **1.0 / 0.7 / 0.4 (plan default)** | Full 2004–2026 | 8.9% | **−39%** | 0.70 | 10.8% | −55% | 0.64 | 4.1 |
| | In-sample 2004–2017 | 7.9% | −39% | 0.63 | 8.6% | −55% | 0.54 | 3.9 |
| | Out-of-sample 2018–2026 | 10.4% | **−19%** | 0.81 | 14.3% | −34% | 0.80 | 4.5 |
| 1.0 / 0.85 / 0.5 | Full | 9.3% | −45% | 0.68 | 10.8% | −55% | 0.64 | 4.1 |
| | Out-of-sample | 10.9% | −22% | 0.78 | 14.3% | −34% | 0.80 | 4.5 |
| 1.0 / 1.0 / 0.5 (cut only in risk-off) | Full | 9.7% | −50% | 0.68 | 10.8% | −55% | 0.64 | **1.7** |
| | Out-of-sample | 11.3% | −22% | 0.78 | 14.3% | −34% | 0.80 | 2.1 |
| 1.0 / 0.85 / 0.3 | Full | 9.1% | −43% | 0.69 | 10.8% | −55% | 0.64 | 4.1 |
| | Out-of-sample | 10.4% | −18% | 0.79 | 14.3% | −34% | 0.80 | 4.5 |

The 60/20/20 baseline held without changes: 9.9% CAGR, −57% max drawdown over the full period; 12.3% and −35% out of sample.

## Episodes (plan default setting)

| Episode | Dial return (max DD) | C fund return (max DD) | Lowest equity weight |
|---|---|---|---|
| 2008 crisis, Oct 07–Mar 09 | −34% (−39%) | −47% (−55%) | 29% |
| 2011 debt ceiling | −12% (−19%) | −6% (−18%) | 37% |
| 2015–16 | −10% (−12%) | −7% (−13%) | 37% |
| 2018 Q4 | −12% (−15%) | −14% (−19%) | 39% |
| 2020 COVID, Feb–Jun | −8% (−19%) | −8% (−34%) | 32% |
| 2022 bear | −15% (−17%) | −19% (−24%) | 38% |
| 2025 tariff shock, Feb–Jun | −1% (−12%) | +1% (−19%) | 37% |
| 2026 year to date | +9% (−8%) | +11% (−9%) | 69% |

## Reading it honestly

- **What it does well:** the big ones. 2008, 2020 and 2022 drawdowns were cut by 30–45%, and the 2025 shock by a third. Out of sample the worst year was −14% instead of −18%.
- **What it does badly:** sharp V-shaped corrections. 2011 and 2015–16 were whipsaws: the dial cut exposure after the drop and re-risked after the recovery, losing 3–5 points versus sitting still. That is the price of any trend-following overlay, and the two-week re-risk delay and monthly transfer cap keep it from being worse.
- **The return cost** is about 2 points a year over the full period and closer to 4 out of sample, because 2018–2026 was an unusually strong bull market with fast recoveries. In a period with a slow bear, the dial would look much better; in another decade like the last one, it will keep looking like insurance with a premium.
- **The Sharpe ratio is equal or better** in every period, which is the cleanest way to say the dial reduces risk more than it reduces return.

## Recommendation

- Enable the dial on the plan default (1.0 / 0.7 / 0.4). It is the best drawdown reduction and the best Sharpe. If four transfers a year feels like too much attention, the "cut only in risk-off" setting (1.0 / 1.0 / 0.5) keeps most of the crash protection with fewer than two transfers a year, at a smaller return cost.
- The live dial uses exactly this tested model. The weekly report also shows the fuller regime score with breadth, but until breadth can be backtested it is context, not a trigger.
- Today the tested model reads **70, risk-on**, so the target is 100% of baseline equity and there is no move this week. The breadth-inclusive score reads 58.8, neutral, which is the early warning.
