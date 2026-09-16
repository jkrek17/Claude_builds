# SPY Session-Range Options Strategy — Indicator Spec and Risk Plan

Status: **spec for review** (no Pine written yet)
Companion to: `../stock-advisor/PROJECT_PLAN.md` (this is a standalone day-trading tool; the advisor stays swing/tactical)
Last updated: 2026-09-16

The goal is an indicator on the SPY 5-minute chart that, at any moment, answers five questions in one panel:

```
BIAS       LONG (above London mid, above PDC, ES trend up overnight)
SETUP      A: London-low sweep + reclaim      status: TRIGGERED 09:52
DIRECTION  LONG   entry 648.40   stop 647.55 (−0.85)
TARGETS    T1 649.70 (+1.30, 1.5R)   T2 651.20 (+2.80, 3.3R)
CONTRACT   0DTE 648C (Δ≈0.50)   risk $150 → 3 contracts   exit by 11:30
```

If the numbers don't clear the risk/reward bar, the panel says **NO TRADE** and why. That filter is the most important line in the script.

---

## 1. Principles

1. **Direction comes from structure, not from an oscillator.** Where price opens relative to the overnight ranges and the prior day tells you who is trapped. The setups are about traps resolving.
2. **Every trade is defined on SPY price, then translated to an option.** Entry, stop and targets are underlying levels. The option is a vehicle chosen after the trade is defined.
3. **Risk/reward is computed before entry and is a hard gate.** No setup is taken with T1 below 1.5R measured on the underlying.
4. **Few trades.** One or two setups per morning, done by 11:30 ET most days. Theta and chop kill 0DTE accounts in the afternoon.
5. **Nothing is trusted until the `strategy()` twin shows an edge on SPY shares over at least 6 months of 5-minute data, and the options overlay in Python confirms it survives spreads and theta.**

---

## 2. Levels the indicator draws

All times ET. Defaults are editable inputs.

| Level | Source | Window | Notes |
|---|---|---|---|
| Asia high / low / mid | ES (`CME_MINI:ES1!` via `request.security`), scaled to SPY | 20:00–00:00 | Scale factor = ES close / SPY close at the prior RTH close; recomputed daily |
| London high / low / mid | SPY premarket (native) from 04:00; ES scaled for 02:00–04:00 | 02:00–05:00 | Option to use ES for the whole window |
| Overnight high / low | ES scaled | 18:00–09:30 | The outer bounds for target selection |
| Previous day high / low / close (PDH, PDL, PDC) | SPY RTH | prior session | PDC is the bias line |
| NY opening range (OR) | SPY | 09:30–09:45 | Height drives Setup B targets |
| Session VWAP | SPY | from 09:30 | Bias confirmation and T1 candidate |
| ATR(14) on 5m and 20-day average daily range | SPY | rolling | Volatility filter and stop buffer |

The panel lists every level in price order so you can see what stands between entry and target.

---

## 3. Daily bias (computed at 09:30, refreshed at 09:45)

Score three things, +1 / 0 / −1 each:

- Open vs PDC: above +1, below −1, within 0.1% → 0.
- Open vs London midpoint: same scoring.
- Overnight ES trend: ES 09:25 price vs Asia midpoint, same scoring.

Bias = LONG if score ≥ +2, SHORT if ≤ −2, NEUTRAL otherwise.

Setups may only be taken **with** the bias, except Setup A (sweep-and-reclaim), which may be taken against a NEUTRAL bias but never against a strong opposite bias. This single rule removes most of the losing counter-trend reversals.

---

## 4. The two setups

### Setup A — Sweep and reclaim (reversal at a session level)

The overnight range edges are where stops sit. Price often runs them, then reverses.

- **Trigger:** a 5-minute bar trades through the London high (or low) or the Asia high (or low) by at least 0.05% and then, within the same bar or the next two bars, **closes back inside** the range.
- **Direction:** opposite the sweep. Sweep of a low → LONG.
- **Entry:** close of the reclaim bar (market or a limit at that close, cancel if not filled in one bar).
- **Stop:** the sweep extreme ± 0.25 × ATR(14, 5m). Never tighter than 0.05% of price (0.10% proved too wide on a $750 SPY: it made the London midpoint unreachable at 1.5R).
- **T1:** range midpoint, or VWAP if it is closer and in the way. Take 50% here and move stop to entry.
- **T2:** opposite side of the swept range, or the next level in the list (PDH/PDL, overnight high/low), whichever comes first.
- **Time window:** 09:35–11:00 only. Reversal sweeps after 11:00 have far worse follow-through.

### Setup B — Opening-range break and retest (continuation with bias)

- **Trigger:** after 09:45, a 5-minute close outside the OR in the direction of the bias, then a pullback that touches the OR edge (± 0.15 × ATR) and closes back on the breakout side. That retest bar is the entry bar.
- **Entry:** close of the retest bar.
- **Stop:** OR midpoint (default). The far side of the OR is available as an option but makes the 1.5R gate unreachable when T1 is one OR-height away, so it is off by default.
- **T1:** entry + 1 × OR height, or the first session level in the way if it is at least 1.5R.
- **T2:** entry + 2 × OR height, or the overnight high/low.
- **Time window:** 09:45–11:30. No new entries after 11:30.

Both setups skip automatically when a filter in §6 fails.

---

## 5. Risk/reward plan

### 5.1 Compute R before anything else

```
R          = |entry − stop|                          (SPY dollars)
T1_R       = |T1 − entry| / R                         must be ≥ 1.5
T2_R       = |T2 − entry| / R                         must be ≥ 2.5
```

If T1 is below 1.5R the panel shows `NO TRADE — T1 only 1.1R (level too close)`. This is what stops you buying a 0DTE call into a level 40 cents away.

### 5.2 Position size from dollar risk

```
account_risk   = 0.5% of account per trade (input; hard cap 1%)
option_risk    = R × delta × 100 + slippage_per_contract   (slippage default $8)
contracts      = floor(account_risk / option_risk)          (min 1, max input cap)
```

Delta is the delta of the chosen strike (see §5.4). With a 0.85-point stop and 0.50 delta, one contract risks about $50 plus slippage, so a $30k account risking 0.5% ($150) trades 2–3 contracts. The panel prints this.

### 5.3 Management

| Event | Action |
|---|---|
| T1 hit | Sell 50%. Stop to entry on the rest. |
| T2 hit | Sell remainder. |
| Stop hit (SPY closes beyond stop on a 5m bar, or trades 0.10% through it intrabar) | Exit all. No re-entry on the same level that day. |
| Time stop | Any position still open at 11:30 (A) or 12:00 (B) is closed regardless of P&L. 0DTE theta accelerates after noon. |
| Second consecutive loss | Done for the day. |
| Daily loss reaches 1.5% of account | Done for the day, no exceptions. |

Expected math with these rules, **if the setup has an edge at all**: about 45% of trades reach T1, roughly a third of those reach T2, average winner ≈ 1.6R, average loser ≈ 1.0R. That is a profit factor around 1.3–1.5 before commissions, which is a real but thin edge. The point of the gate and the time stops is to keep the losers at 1R.

### 5.4 Contract selection

| Setting | Default | Why |
|---|---|---|
| Expiry | 0DTE for entries before 10:30, 1DTE after | Theta on 0DTE after mid-morning is brutal; 1DTE costs a little more premium and buys time |
| Strike | ATM to first in-the-money, delta 0.45–0.60 | Higher delta tracks the underlying move you actually defined; far OTM turns a 2R plan into a lottery ticket |
| Alternative | Debit vertical spread when IV is high (VIX > 25) | Caps cost when premiums are inflated; target the short strike at T2 |
| Never | Selling naked, buying < 0.30 delta for these setups | |

The indicator suggests the strike as `round(entry)` for calls and `round(entry)` for puts, and prints the delta assumption used for sizing. Actual delta comes from your broker's chain; adjust contracts if it differs by more than 0.10.

---

## 6. Filters (no trade if any fails)

- **Event days:** no Setup A before 10:00 on CPI, PPI, NFP, and no trades at all 13:30–15:00 on FOMC days. (Input: a manual calendar list; the script can't read the economic calendar.)
- **Range too small:** overnight range < 35% of the 20-day average daily range → skip Setup A (nothing to sweep).
- **Range too large:** overnight range > 150% of the average → halve size (targets are farther, stops wider).
- **OR too wide:** OR height > 60% of the 20-day average daily range → skip Setup B.
- **Chop:** three or more OR breaks in both directions before 10:15 → no Setup B that day.
- **Spread check:** manual. If the option's bid-ask is wider than 10% of the mid, pass.

---

## 7. Panel and alerts

**Panel** (top right, updates every bar): bias and its three votes, the active setup and its status (`ARMED` while conditions are forming, `TRIGGERED` with a timestamp, `NO TRADE` with the reason, `INVALID` once time-stopped), entry, stop, T1, T2, R multiples, contract suggestion, contracts for the configured dollar risk, and the ordered list of levels overhead and underneath.

**Alerts** (`alertcondition`):

- `A_LONG_TRIGGERED`, `A_SHORT_TRIGGERED`, `B_LONG_TRIGGERED`, `B_SHORT_TRIGGERED` with entry/stop/T1/T2 in the message.
- `T1_HIT`, `T2_HIT`, `STOP_HIT`, `TIME_STOP`.
- `ARMED` alerts optional (noisy).

Alert messages are JSON so the advisor project's webhook can log them and score them later:

```json
{"symbol":"SPY","setup":"A","dir":"long","entry":648.40,"stop":647.55,"t1":649.70,"t2":651.20,"r1":1.5,"r2":3.3,"expiry":"0DTE","strike":648,"ts":"2026-09-16T09:52:00-04:00"}
```

---

## 8. Validation before real money

1. **`strategy()` twin on SPY shares**, 5-minute bars, at least 6 months (12 preferred), commission $0, slippage 1 tick each way. Report by setup, by direction, by hour of entry, with and without the bias rule and the R gate. Keep only what survives.
2. **Options overlay in Python** inside the advisor project's backtester: replay each signal against historical 0DTE/1DTE chains (ThetaData or Polygon), with the strike rule from §5.4, real bid/ask fills, and the time stops. This is where theta and spreads show up.
3. **Paper trade two weeks** with the alerts live, logging every fill in the advisor's recommendation log. Compare fills to the backtest assumptions.
4. Go live at half size for a month, then full size only if the live profit factor is above 1.2.

If step 1 shows no edge, the strategy is not rescued by better option selection. Stop there.

---

## 9. Defaults assumed (change any of these)

| Input | Default |
|---|---|
| Asia session | 20:00–00:00 ET |
| London session | 02:00–05:00 ET |
| Opening range | 09:30–09:45 |
| Chart timeframe | 5 minutes |
| Sweep buffer | 0.05% |
| Stop buffer | 0.25 × ATR(14), min 0.05% |
| Setup B stop | OR midpoint |
| T1 / T2 minimum R | 1.5 / 2.5 |
| Risk per trade | 0.5% of account |
| Setup A window | 09:35–11:00 |
| Setup B window | 09:45–11:30 |
| Expiry rule | 0DTE before 10:30, else 1DTE |
| Delta target | 0.45–0.60 |

---

## 10. Improvements over v1 (ranked by expected impact)

Each item says whether it fits in Pine, needs the Python side, or is a testing discipline. Items marked **v1.1** go into the first script; the rest are tested one at a time against the v1 baseline so we know what each one adds.

### 10.1 Size targets to the market's expected move — v1.1, Pine

v1 targets are structural (range edges, OR multiples) with no check against what the market thinks today's range will be. Pull `CBOE:VIX1D` (one-day implied vol) via `request.security`; the expected move for the session is roughly `SPY × VIX1D / 100 / sqrt(252)`. Then:

- Cap T2 at the expected-move boundary from the open. A target beyond it is a hope, not a plan.
- Skip a setup whose T1 is less than 15% of the expected move. That is noise, and the option's spread eats it.
- Show "T2 uses 65% of today's expected move" in the panel.

This is the single biggest fix because it makes targets consistent with what 0DTE premium is actually pricing.

### 10.2 Confluence score for Setup A — v1.1, Pine

A London low that is also yesterday's low and a round-number strike is a far better sweep target than a lone London low. Count levels within 0.10% of the swept level: Asia/London/overnight edges, PDH/PDL/PDC, $5 strike increments, prior-day VWAP, 1-hour swing high/low. Require confluence ≥ 2 for Setup A. Show the count in the panel.

### 10.3 Reclaim-bar quality — v1.1, Pine

v1 accepts any close back inside the range. Require, for a long reclaim: close in the top third of the bar's range, volume ≥ 1.5 × the 20-bar average, and the bar's low is the lowest of the last 6 bars. Fake reclaims (a doji poking back inside on thin volume) are where most Setup A losses come from.

### 10.4 Trend-day detection — v1.1, Pine

A gap larger than 0.5% that holds beyond the OR without a retest by 10:00, with VWAP sloping the same way, is a trend day. On trend days: disable Setup A against the trend entirely, and let Setup B use VWAP pullbacks as entries instead of the OR edge. Fading trend days is the fastest way to lose on this strategy.

### 10.5 Volatility-regime switch — Pine + advisor

Low-vol days favor mean reversion (Setup A); high-vol days favor continuation (Setup B). Rule: if VIX1D is below its 20-day median, weight Setup A; above it, weight Setup B; extreme (VIX > 30 or term structure inverted, which the advisor project already computes), halve size on everything. The advisor's daily regime can be pushed into the script as inputs each morning until Pine can read it directly.

### 10.6 Dealer-positioning levels as target modifiers — manual or advisor

Large open-interest strikes act as magnets and pins near expiry. Pine cannot see open interest, but the advisor can pull the SPY chain each morning and push the top three OI strikes and the zero-gamma level as inputs. Rule: never set a target just past a wall; set it just before, and treat a wall as an extra confluence level for Setup A.

### 10.7 A second option expression for Setup A: credit spread — Python overlay

The reclaim thesis is "the low is in", not "price will rally 3R". A put credit spread with the short strike just below the sweep low expresses exactly that: it profits from time and any non-breach, with defined risk. Lower reward per trade but a much higher win rate, and it is the better vehicle after 10:30 when a long call's theta is punishing. Test both expressions against the same signals in the options overlay and let the numbers pick. Suggested default: long call/put before 10:30, credit spread after.

### 10.8 Better exits — Pine strategy twin, tested

- After T1, trail the remainder by the prior 5-minute swing low/high instead of a fixed T2, and compare.
- Test scale-out splits 50/50 vs 33/67 vs all-at-T1.
- Entry variant: buy stop one tick above the reclaim bar's high (confirmation) instead of at its close. Fewer trades, better quality, test it.

### 10.9 Historical hit rates in the panel — Pine

Pine can keep running tallies. Show, next to each level type, how often it was swept and reclaimed in the last 250 sessions and how often T1 followed. "London low reclaim → T1 hit 58% (n=112)" is real context at the moment of decision, and it will tell you quickly if a level type has stopped working.

### 10.10 Calendar awareness — Pine input list + advisor

Early-close days (13:00) break the time stops; opex Fridays pin; the first day after a holiday behaves differently. The advisor project can generate the year's list (early closes, opex, FOMC, CPI, NFP) and the script takes it as an input string. Skip or halve size accordingly.

### 10.11 Testing discipline (this is where most strategies die)

- Keep the tunable parameters to about six. Every extra one is a way to overfit.
- Sweep each parameter and keep only plateaus, never the single best value.
- Walk-forward: tune on months 1–6, test on 7–12, then roll.
- Report by setup, direction, hour, day of week, and volatility regime. A strategy that only works on Tuesdays in low vol is not a strategy.
- Include one tick of slippage each way on SPY and real bid-ask fills in the options overlay. Most "edges" vanish here.

### 10.12 What to leave alone

Do not add oscillators (RSI, MACD) as filters. They add parameters, lag, and false confidence without changing where stops sit. Do not add more setups until A and B are proven. Do not trade the afternoon until the morning is profitable for three months.

## 11. Next steps

1. Confirm or change the defaults above and the session definitions.
2. Write `spy_session_ranges.pine` (indicator, Pine v6) and `spy_session_ranges_strategy.pine` (strategy twin), including the v1.1 items from §10.
3. Run the TradingView backtest and check in a results summary here.
4. Wire the alert webhook into the advisor's recommendation log.
