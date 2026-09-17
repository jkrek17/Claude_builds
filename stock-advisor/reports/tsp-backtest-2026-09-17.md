# TSP Weekly Risk Dial — Backtest Results (September 17, 2026)

**Verdict: passes the bar in `PROJECT_PLAN.md` §7 for drawdown and transfer count; the cost is 1–2 points a year of return over a bull-heavy sample. Enabled on a weekly cadence with the settings below.**

## Setup

- Proxies, because tsp.gov blocks automated downloads: C = SPY, S = VXF, I = EFA, F = AGG, G = daily accrual of the 10-year Treasury yield (never falls). Re-run on real TSP share prices by placing the tsp.gov CSV export in `data/tsp_prices.csv`.
- Regime score from three groups with weights fixed before testing: trend 40% (SPY vs 200-day, vs 50-day, 200-day slope, extended-market vs 200-day), volatility 30% (VIX level bands, VIX vs its 60-day average, VIX vs realized), credit 30% (high-yield spread level and 1-month change, 10y–3m curve). Breadth omitted: no constituent history.
- Decision every Friday close, executed Monday close (the TSP noon rule). Hysteresis: risk-on above 70, risk-off below 35, back to neutral on a 5-point re-cross. Re-risking needs two consecutive risk-on weeks. A transfer needs at least a 10-point equity change, 15 days since the last one, and no more than two per month.
- Baseline **100% C** (your actual allocation); remainder to G when the dial cuts. Compared with staying 100% in C. (An earlier run on a 60/20/20 C/S/I baseline gave the same shape with slightly worse numbers.)
- Parameters were not tuned. Four neighboring multiplier sets were run afterwards to check the result is a plateau.

## Results (100% C baseline)

| Setting (on / neutral / off) | Period | Dial CAGR | Dial max DD | Sharpe | C fund CAGR | C max DD | Sharpe | Transfers / yr |
|---|---|---|---|---|---|---|---|---|
| **1.0 / 0.7 / 0.4 (plan default)** | Full 2004–2026 | 9.7% | **−39%** | **0.77** | 10.8% | −55% | 0.64 | 4.1 |
| | In-sample 2004–2017 | 7.9% | −39% | 0.65 | 8.6% | −55% | 0.54 | 3.9 |
| | Out-of-sample 2018–2026 | 12.5% | **−19%** | **0.94** | 14.3% | −34% | 0.80 | 4.5 |
| 1.0 / 1.0 / 0.5 (cut only in risk-off) | Full | 10.4% | −49% | 0.74 | 10.8% | −55% | 0.64 | **1.7** |
| | Out-of-sample | 13.3% | −21% | 0.90 | 14.3% | −34% | 0.80 | 2.1 |

## Episodes (plan default setting)

| Episode | Dial return (max DD) | C fund return (max DD) | Lowest equity weight |
|---|---|---|---|
| 2008 crisis, Oct 07–Mar 09 | −34% (−39%) | −47% (−55%) | 29% C |
| 2011 debt ceiling | −10% (−17%) | −6% (−18%) | 38% C |
| 2015–16 | −8% (−11%) | −7% (−13%) | 38% C |
| 2018 Q4 | −11% (−15%) | −14% (−19%) | 39% C |
| 2020 COVID, Feb–Jun | −8% (−19%) | −8% (−34%) | 33% C |
| 2022 bear | −14% (−16%) | −19% (−24%) | 38% C |
| 2025 tariff shock, Feb–Jun | −1% (−13%) | +1% (−19%) | 37% C |
| 2026 year to date | +9% (−7%) | +11% (−9%) | 69% C |

## Reading it honestly

- **What it does well:** the big ones. 2008, 2020 and 2022 drawdowns were cut by 30–45%, and the 2025 shock by a third. Out of sample the worst year was −14% instead of −18%.
- **What it does badly:** sharp V-shaped corrections. 2011 and 2015–16 were whipsaws: the dial cut exposure after the drop and re-risked after the recovery, losing 3–5 points versus sitting still. That is the price of any trend-following overlay, and the two-week re-risk delay and monthly transfer cap keep it from being worse.
- **The return cost** is about 1 point a year over the full period and closer to 2 out of sample, because 2018–2026 was an unusually strong bull market with fast recoveries. In a period with a slow bear, the dial would look much better; in another decade like the last one, it will keep looking like insurance with a premium.
- **The Sharpe ratio is equal or better** in every period, which is the cleanest way to say the dial reduces risk more than it reduces return.

## Recommendation

- Enable the dial on the plan default (1.0 / 0.7 / 0.4). It is the best drawdown reduction and the best Sharpe. If four transfers a year feels like too much attention, the "cut only in risk-off" setting (1.0 / 1.0 / 0.5) keeps most of the crash protection with fewer than two transfers a year, at a smaller return cost.
- The live dial uses exactly this tested model. The weekly report also shows the fuller regime score with breadth, but until breadth can be backtested it is context, not a trigger.
- Today the tested model reads **70, risk-on**, so the target is 100% of baseline equity and there is no move this week. The breadth-inclusive score reads 58.8, neutral, which is the early warning.
