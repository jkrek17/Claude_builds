# TradingView: SPY Session Ranges + Setups

Two Pine Script v6 files that implement `SPY_SESSION_RANGE_STRATEGY.md`:

| File | Purpose |
|---|---|
| `spy_session_ranges.pine` | Indicator. Draws the levels, computes bias, detects setups, gates on risk/reward, prints direction / entry / stop / targets / contract in a panel, fires alerts. |
| `spy_session_ranges_strategy.pine` | Strategy twin. Same logic plus `strategy.entry` / `strategy.exit`, so the Strategy Tester reports win rate, expectancy and drawdown per setup on SPY shares. |

**Status: both scripts compile in TradingView (Pine v6).** The strategy twin is generated from the indicator by `build_strategy.py`; edit the indicator, run the script, paste both.

## Setup

1. Open a **SPY, 5-minute** chart. Extended hours on or off both work; the overnight levels come from ES futures either way. If you turn on "London from chart symbol" you need extended hours on.
2. Pine Editor → New → paste `spy_session_ranges.pine` → Save → Add to chart.
3. The panel appears top right. Levels draw from the 9:30 bar each day. Nothing is drawn before the first regular-hours bar of the day.
4. Repeat with the strategy file on its own chart to see the Strategy Tester tab.

Data notes:

- Overnight levels use `CME_MINI:ES1!` scaled to SPY by the ES/SPY ratio frozen at 9:30. Change the symbol input if your data plan uses a different feed (for example `CME_MINI:ES1!` needs CME data on some plans; `CAPITALCOM:US500` or `SP:SPX` are inferior but free substitutes, and SPX has no overnight session).
- Expected move uses `CBOE:VIX1D`. If your plan doesn't have it, the script falls back to the 20-day average daily range.

## What the panel tells you

```
Bias        LONG (+2)  PDC +1 · Lon +1 · ES 0
Day         gap +0.31%  trend none  chop 1
Exp. move   ±3.10  (645.20 – 651.40)  VIX1D 12.4
Overnight   range 2.85 = 62% of avg DR
Asia        646.10 – 647.90  mid 647.00
London      645.40 – 648.20  mid 646.80
OR / PD     OR 647.30 – 648.90  PDH 649.10  PDL 644.80  PDC 648.30
Setup       TRIGGERED 09:52  A LONG
Entry/Stop  648.40 / 647.55  (R = 0.85)
Targets     T1 649.70 (1.5R)   T2 651.20 (3.3R)
Contract    0DTE 648C  Δ≈0.50  risk $150 → 3 contract(s)
Exit by     1130 ET
Today       1/2 trades  +0.0R  losses in a row 0
A long/short  58% T1, 52% win (n=31)  |  44% T1, 40% win (n=18)
B long/short  ...
```

The **Diag** row counts raw triggers per setup since the start of the loaded history and how many were rejected by each gate (R/EM, confluence, bar quality, bias, range, level). If the strategy shows few trades, this row says which gate is binding.

`NO TRADE — ...` in the Setup row means a raw trigger happened but a gate failed (R too small, confluence, weak reclaim bar, against bias, chop, range too small). `ARMED — ...` means a sweep or OR break is in progress and the script is waiting for the reclaim or retest.

## Alerts

Create one alert on the chart with condition **"Any alert() function call"** and, on a paid plan, a webhook URL. Every event posts JSON:

```json
{"symbol":"SPY","event":"TRIGGERED","setup":"A","dir":"long","entry":648.4,"stop":647.55,"t1":649.7,"t2":651.2,"r1":1.5,"r2":3.3,"expiry":"0DTE","strike":648,"contracts":3,"price":648.4,"ts":"2026-09-16 09:52"}
```

Events: `TRIGGERED`, `T1_HIT`, `T2_HIT`, `STOP_HIT`, `TIME_STOP`. The plain `alertcondition` entries (A long triggered, etc.) are there for app push notifications without a webhook.

## Inputs worth knowing

| Group | Input | Default | Why |
|---|---|---|---|
| Sessions | Asia / London / Overnight / OR | 20:00–00:00 / 02:00–05:00 / 18:00–09:30 / 09:30–09:45 ET | Spec §9 |
| Setup A | Sweep buffer, reclaim bars, min confluence, volume multiple | 0.05%, 2, 2, 1.5× | Spec §4, §10.2, §10.3 |
| Setup B | Retest tolerance, OR width cap, chop count | 0.15 ATR, 60%, 3 | Spec §4, §6 |
| Risk | Min T1/T2 R, risk $, delta, slippage, max contracts, daily limits | 1.5 / 2.5, $150, 0.50, $8, 10, 2 trades, 2 losses, 3R | Spec §5 |
| Filters | Expected-move symbol, min T1 % of EM, overnight range bounds, trend-day gap, no-trade dates | VIX1D, 20%, 35–150%, 0.5%, "" | Spec §6, §10.1, §10.4 |

Dates go in as `2026-09-17,2026-10-29`. Put FOMC, CPI, NFP and early-close days in "No-trade dates" until the calendar feed from the advisor project exists.

## Strategy twin: reading the tester

- Position size is `riskDollars / R` shares, so one full stop-out is one R and net profit reads in R multiples of your risk setting.
- Entries fill at the close of the trigger bar. Exits are resting limit/stop orders, filled intrabar. The T1 exit takes 50% and the remaining exit moves its stop to entry after T1 fills.
- Slippage is 1 tick per fill. Set commission to your broker's per-share rate if you want cash numbers.
- Use **List of Trades** with the comment column to split A vs B and long vs short. Run the parameter sweeps and walk-forward described in spec §8 and §10.11 before believing anything.
- The tester does not model options. A setup that loses on shares will not be rescued by options; a setup that wins on shares still has to survive theta and spreads in the Python overlay.

## Known limitations

- On a chart without extended hours, the London-from-chart option has no data and the script falls back to ES.
- `request.security` on ES returns ES's bar aligned to the chart bar; on holidays where ES trades and SPY doesn't, the levels are simply the last completed sessions.
- Trend-day detection evaluates once at 10:00. A trend that starts later is not flagged.
- The in-panel stats count every trigger since the start of the loaded history and are not walk-forward.
