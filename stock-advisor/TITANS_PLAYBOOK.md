# Titans Playbook — Finding Swing and Position Trades the Way the Great Investors Do

Status: **design for review**
Companion to: `PROJECT_PLAN.md` (this replaces the "watchlist" idea in §6 with a real idea-generation engine)
Last updated: 2026-09-17

Goal: a weekly process that surfaces 3–10 candidates with a documented reason to own them, an entry level, a stop, and a holding-period expectation, using only what the great investors actually do that can be written down and tested. Cadence: one screen on the weekend, alerts during the week, no screen-watching.

---

## 1. What is actually reproducible from each titan

Most of what makes these people great is temperament, access, and judgment. But each one has a documented, testable core. The table separates the two.

| Investor | The reproducible core | What is not reproducible | Evidence it works when copied |
|---|---|---|---|
| **Warren Buffett / Charlie Munger** | Buy high-quality businesses (high, stable return on capital; low debt; durable margins) at a fair price, hold for years | Insurance float leverage, deal flow, patience through 50% drawdowns | Frazzini, Kabiller & Pedersen, "Buffett's Alpha" (2018): Berkshire's record is largely explained by systematic exposure to cheap, safe, high-quality stocks with ~1.7× leverage. Martin & Puthenpurackal (2008): a portfolio that copied Berkshire's holdings *after* they became public still beat the S&P 500 by roughly 10 points a year, 1976–2006 |
| **Joel Greenblatt** | The Magic Formula: rank the universe on earnings yield (EBIT/EV) and return on capital, buy the top 20–30, hold a year | Nothing; it is fully mechanical | His own 1988–2004 backtest; independent replications find smaller but positive excess returns with multi-year stretches of underperformance |
| **Peter Lynch** | Growth at a reasonable price: PEG under about 1.0–1.5, earnings growing 15–25%, understandable business; invest in what you can observe | Company visits, 1,400 holdings | GARP screens have a long documented record; the "invest in what you know" part is a research prompt, not a factor |
| **Stanley Druckenmiller** | Top-down first: liquidity and central banks drive markets, position 12–18 months ahead of the consensus, concentrate when conviction is high, cut losers immediately, "preservation of capital and home runs" | Reading the macro tape in real time, sizing to 100%+ of the book | The regime model in `PROJECT_PLAN.md` §5 is our version of his liquidity read. His 13F (Duquesne Family Office) is public but lags 45 days and hides his futures and shorts |
| **Paul Tudor Jones** | "Nothing good happens below the 200-day moving average." Trend filter, risk first | Macro trading | The 200-day filter has been shown to cut drawdowns dramatically with little cost to long-run return (Faber, "A Quantitative Approach to Tactical Asset Allocation") |
| **William O'Neil / Mark Minervini / Stan Weinstein** | Buy leaders in confirmed uptrends (stage 2) as they break out of bases on volume; stop 7–8% below entry; sell into strength | Discretionary pattern reading | The Minervini trend template and O'Neil's CAN SLIM are explicit rules; momentum is the most robust factor in the literature |
| **Insiders (not a titan, but the best "titan" signal)** | Cluster open-market buying by multiple officers or directors | — | Cohen, Malloy & Pomorski, "Decoding Inside Information" (2012): opportunistic insider buys predict returns; clusters are stronger than single buys |
| **13F "superinvestor" consensus** | Positions newly bought or added by a curated list of low-turnover managers (Berkshire, Akre, Greenblatt's Gotham, Pabrai, Baupost, Himalaya, etc.) | High-turnover managers (Druckenmiller, Tepper) are useless to clone with a 45-day lag | Martin & Puthenpurackal above; Dataroma-style aggregation is a common practitioner tool |

The honest summary: quality, value, momentum, and trend are the four things that survive when you strip the personalities away. Insider buying and low-turnover 13F flows are two independent confirmations that cost nothing. Everything else is judgment, and that is where Claude helps you read, not decide.

---

## 2. The four lenses, one scorecard

Every stock in the universe gets a 0–100 score from four lenses, each 0–25. A candidate must clear a floor on each lens and rank in the top decile overall. Weights are a starting point for the backtest, not a conclusion.

### Lens 1 — Quality and value (Buffett, Munger, Greenblatt)

| Metric | Preferred | Why |
|---|---|---|
| Return on invested capital, 5-year average | > 15% | The single best proxy for "wonderful business" |
| Gross margin stability (std dev / mean, 5 yr) | low | Pricing power |
| Free-cash-flow yield (FCF / EV) | > 4% | Fair price for the quality |
| EBIT / EV (Greenblatt earnings yield) | top half of universe | Cheapness |
| Net debt / EBITDA | < 2 | Safety |
| Share count, 3-year change | falling | Buybacks signal management thinks it's cheap |
| Earnings consistency (years of positive EPS in last 10) | ≥ 8 | Predictability |

### Lens 2 — Growth at a reasonable price (Lynch)

| Metric | Preferred |
|---|---|
| Revenue growth, 3-year CAGR | > 8% |
| EPS growth, forward and trailing | 12–30% (very high growth is usually priced for perfection) |
| PEG (forward P/E ÷ EPS growth) | < 1.5 |
| Earnings revisions, last 90 days | up |

### Lens 3 — Trend and macro fit (Druckenmiller, Tudor Jones, Minervini)

| Rule | Detail |
|---|---|
| Above the 200-day moving average | hard floor; no exceptions |
| Minervini trend template | price > 150-day > 200-day, 200-day rising ≥ 1 month, price ≥ 30% above 52-week low and within 25% of 52-week high |
| Relative strength vs S&P 500, 6 and 12 months | top 30% |
| Sector relative strength | sector above its own 200-day and outperforming SPY over 3 months |
| Regime fit | regime score (`PROJECT_PLAN.md` §5) risk-on or neutral for new longs; risk-off means no new positions and tighter stops |

### Lens 4 — Smart-money confirmation (13F clones, insiders)

| Signal | Points |
|---|---|
| Held by ≥ 2 curated superinvestors, and at least one added last quarter | up to 12 |
| Newly initiated by a curated superinvestor last quarter | up to 8 |
| Insider cluster buy: ≥ 2 insiders, open-market, > $100k combined, last 90 days | up to 10 |
| CEO or CFO open-market buy > $250k, last 90 days | up to 5 |
| Heavy insider selling beyond 10b5-1 plans | negative |

---

## 3. From score to trade: the swing/position overlay

A high score says "worth owning". It does not say "buy today". Entry timing is where the trend-followers earn their keep, and it is what keeps a job-holder out of dead money.

**Entry (choose one, both are alerts, not screen-watching):**

- **Breakout:** close above a 4–8 week base high on volume ≥ 1.5× the 50-day average. Buy the close or next open.
- **Pullback:** in a confirmed uptrend, price pulls back to the 50-day (or 10-week) moving average and closes back above it on the day. Lower risk, more entries.

**Stop:** below the base low or 8% under entry, whichever is closer (O'Neil's rule). Never widen it.

**Sizing:** risk 1% of the account per position. Position size = 1% ÷ stop distance. With an 8% stop that is a 12.5% position, so 8–12 positions fill the book. Cap any single name at 15%.

**Managing:**

- Raise the stop to breakeven after a +1R move, then trail under the 10-week moving average.
- Sell a third into a +20–25% gain if the stock goes parabolic (O'Neil's sell rule); let the rest run on the trailing stop.
- Hard exit on a close below the 200-day, regardless of thesis. Tudor Jones.
- Re-check the fundamentals every earnings report. A thesis breaks on the numbers, not on the price.
- Regime risk-off: no new entries, trail stops to the 50-day, and let the TSP dial (§7 of the plan) do its job.

**Holding period expectation:** weeks to months for pullback entries, quarters to years for names that keep making the score. A position that keeps qualifying is not sold because time passed.

---

## 4. Weekly and daily cadence (fits around a job)

| When | What | Time |
|---|---|---|
| Saturday morning | The screen runs automatically. Read the top 10, each with a Claude-written one-page brief: what the business does, why it scores, what the bears say, what would break the thesis. Pick up to 3 to arm. | 45–60 min |
| Sunday | Set alerts for the chosen entries (breakout level or 50-day touch) and stops. | 10 min |
| Weekdays | Act only on alerts. Entries at the close or next open; stops honored the same day. | minutes |
| Earnings weeks | The brief flags holdings reporting that week and what to look for. | 10 min |
| Quarterly (45 days after quarter end) | 13F refresh; new superinvestor buys appear in the screen. | automatic |

---

## 5. Data (all free at the start)

| Data | Source | Notes |
|---|---|---|
| Fundamentals (10 years) | SEC XBRL "companyfacts" API | Free, official, every US filer. Needs normalization (companies tag things differently) |
| Prices, MAs, relative strength | Schwab market data or yfinance | Already in the plan |
| 13F holdings | SEC EDGAR structured 13F data | Free; parse once a quarter for a list of ~25 curated managers |
| Insider transactions | SEC EDGAR Form 4 feed | Free; OpenInsider as a cross-check |
| Estimates and revisions | yfinance (limited) or Financial Modeling Prep (~$20/mo) | The one place a paid feed helps |
| Superinvestor list | Hand-maintained config file | Berkshire, Akre, Gotham, Pabrai, Baupost, Himalaya, Giverny, Polen, Tiger Global's long book (careful), Duquesne (for ideas only, not timing) |
| Your Patreon creator's Top 10 | Already ingested (`PROJECT_PLAN.md` §8) | Treated as one more "manager" in Lens 4 |

---

## 6. Backtest before trusting

- Universe: US-listed, market cap > $2B, average dollar volume > $10M/day, excluding financials and REITs from Lens 1 (their ratios don't fit) but scoring them on the other lenses.
- Rebalance monthly on the score; entries and exits per §3 rules; 2010–present with 2019–present out-of-sample.
- Report: annual return, max drawdown, turnover, and hit rate vs. buying SPY and vs. the equal-weight S&P 500. The bar: higher return with a smaller drawdown, or the same return with much less drawdown. Beating SPY by a few points a year over a cycle with meaningfully less drawdown is what a good version of this looks like. It will not double your money in a year, and any backtest that says it will is broken.
- Ablation: run each lens alone, then all four, then all four with the trend overlay. Keep only what adds.

---

## 7. What Claude does here, and doesn't

Does: writes the weekly brief per candidate from the scored data plus the last two earnings-call transcripts and the 10-K business section; lists the bear case; drafts the "what breaks this thesis" line; summarizes what the superinvestors said about the name in their letters. Answers "why is this scoring high" in plain English.

Doesn't: pick the stocks, set the score, or change a stop. Every number in a brief must come from the scored table or a cited filing.

---

## 8. Open questions

1. **Capital and position count.** Roughly how much is in play, and are you comfortable with 8–12 positions, each 8–12% of the book? Fewer means more concentration, Druckenmiller-style; more means index-like results.
2. **Holding period.** Are you happier with weeks-to-months swings on pullbacks, or quarters-to-years positions with fewer trades? The scoring weights shift (more Lens 3 for swings, more Lens 1 for positions).
3. **Account type.** In a taxable account, the one-year holding line matters and the system should prefer trimming over selling before it. In an IRA it doesn't.
4. **Universe.** US-listed only, or include ADRs like the NVO, BABA and BIDU on your watchlist? ADRs have thinner fundamentals data in SEC XBRL.
5. **Which titans matter most to you?** The default weights are equal across the four lenses. If you want to lean Buffett or lean Druckenmiller, say so and the weights start there.
6. **Time budget.** Is an hour on Saturday realistic? The whole design assumes it.

---

## 9. Build order (replaces Phases 2 and 5–6 of `PROJECT_PLAN.md` for the equity side)

1. Fundamentals ingest from SEC XBRL for a fixed universe (~1,500 names) with a normalization layer and tests on 20 hand-checked companies.
2. Lens 1 and Lens 3 scoring and the weekly screen output as a table. Backtest Lens 1 + trend overlay first, since it is the best-documented combination.
3. Lens 4: 13F parser for the curated list and the Form 4 cluster detector.
4. Lens 2 with estimate data.
5. Entry and stop alerts (reuse the Telegram delivery from the plan) and the recommendation log.
6. Claude weekly briefs.
7. TradingView: a small Pine script that draws the trend template, base highs and the 50-day for any symbol, so chart review on Saturday is fast. The day-trading scripts stay in the repo but are parked.
