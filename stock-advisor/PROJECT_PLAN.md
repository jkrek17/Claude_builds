# Personal Stock Market Advisor — Project Plan

Status: **draft for review** (no code written yet)
Owner: jkrek17
Last updated: 2026-09-16

This is a plan for a personal, advisory-only system that:

1. Knows your positions (entered by hand, imported from a Schwab CSV export, or pulled from the Schwab Trader API).
2. Reads overall market sentiment and structure every day and classifies the market into a **regime** (risk-on / neutral / risk-off).
3. Tells you, per position, when the rules say **buy more, hold, trim, or sell**, and why.
4. Tells you when to **dial risk down or back up in your TSP** (Thrift Savings Plan), within the TSP's own transfer rules.
5. Keeps a log of every recommendation and scores itself against what actually happened.

It never places orders. You act; it advises.

---

## 1. Goals and non-goals

### Goals

- A morning brief you can read in two minutes: regime, what changed, what to do today, and a one-line reason for each action.
- Rules-based signals as the source of truth, with a language model used only to explain, summarize news, and answer "why".
- Every recommendation is reproducible: same data in, same signal out, with the inputs stored.
- Backtested before it is trusted. You should be able to see how the TSP risk dial would have behaved in 2008, 2020, 2022 and April 2025 before you let it move your money.
- Cheap to run: free data where possible, one small box or a cron job, modest LLM spend.

### Non-goals (at least for v1)

- Automated order execution at Schwab or anywhere else.
- Intraday or high-frequency signals. Daily bars are the finest resolution.
- Options, futures, crypto, or short selling.
- Picking new stocks from the whole market. v1 advises on what you own plus a watchlist you maintain.
- Any claim of predicting returns. The system manages exposure and risk; it does not forecast prices.

---

## 2. How you would use it (user stories)

| # | As the owner, I want to... | So that... |
|---|---|---|
| U1 | Enter or import my Schwab positions with cost basis and lot dates | The advisor knows what I hold, how big each position is, and whether a sale is short- or long-term |
| U2 | Get a morning brief by 7:30 ET (email or Telegram) | I can act before the open or before the TSP noon deadline |
| U3 | See a market regime score with the signals behind it | I understand why it says risk-on or risk-off instead of trusting a black box |
| U4 | Get a per-position action (buy / hold / trim / sell) with the triggering rule | I know what to do and why, and I can override |
| U5 | Get a TSP recommendation phrased as a concrete interfund transfer (e.g. "move 20% from C to G") | I can execute it on tsp.gov in one step |
| U6 | Be warned about TSP constraints (2 transfers per month used, noon ET cutoff) | I don't waste a transfer or miss the same-day price |
| U7 | See a scorecard of past recommendations vs. what happened | I can tell whether the advisor is actually helping |
| U8 | Ask "why is the regime neutral today?" in plain English | I get an explanation grounded in the actual numbers |
| U9 | Get an out-of-cycle alert when something breaks (VIX spike, stop hit, credit spreads jump) | I'm not surprised by a big move between briefs |

---

## 3. Hard constraints and realities to design around

### Schwab

- **Schwab Trader API** (developer.schwab.com, "Trader API – Individual") exposes accounts, positions, transactions, quotes, and price history. Registering an app requires approval, which typically takes a few business days.
- Auth is OAuth 2.0. **Access tokens last 30 minutes; refresh tokens last 7 days.** After 7 days you must log in again through a browser. The system must handle a lapsed token gracefully and nag you to re-authorize, not crash.
- The same API can place orders. The advisor will never import or call the order endpoints, and a code-level guard will enforce that.
- **CSV export from schwab.com** (Positions → Export) is the zero-approval fallback and is good enough for a weekly refresh. It gives symbol, quantity, price, market value, cost basis and gain/loss. Lot-level detail comes from the separate cost-basis export.
- Community Python clients exist (`schwab-py`, `schwabdev`); the plan assumes one of them rather than hand-rolling OAuth.

### TSP

- Funds: G (Treasury, never loses value), F (US bond index), C (S&P 500), S (US small/mid completion index), I (international, MSCI ACWI IMI ex USA ex China ex Hong Kong since 2024), and the L lifecycle funds.
- **No API.** The advisor produces instructions; you execute at tsp.gov.
- **Interfund transfer limit: two per calendar month.** After that, you can only move money *into* the G Fund. Contribution-allocation changes for future payroll are unlimited and separate.
- Transfers requested **before 12:00 noon ET** execute at that day's closing share price. After noon, next business day.
- Daily share prices for every fund are published on tsp.gov with a downloadable history back to 2003. That is the backtest dataset, so no ETF proxies are needed for the TSP module.
- Consequence for the design: TSP signals must be **slow and hysteretic**. A regime model that flips three times a month is useless here, so the TSP module deliberately lags and debounces the daily regime score.

### Data

- Free daily prices via `yfinance` are fine for prototyping but break without notice. The plan treats the price source as pluggable and uses the Schwab market data endpoint once the API is connected.
- Macro and credit series come from FRED (free API key): high-yield spreads, yield curve, VIX close, unemployment, Sahm rule.
- Breadth (percent of S&P 500 above the 200-day average, new highs minus new lows) needs constituent-level prices. That is ~500 tickers a day, which is feasible with a bulk download and within the Schwab rate limit.
- Survey sentiment (AAII, NAAIM, Investors Intelligence) is weekly and web-scraped, so it is a low-weight input and can be missing without breaking the run.

---

## 4. Architecture

```
                 ┌────────────────────────────────────────────────────┐
                 │                    Scheduler                        │
                 │   (cron / APScheduler: 6:30 ET daily + intraday)    │
                 └───────────────┬────────────────────────────────────┘
                                 ▼
┌──────────────┐   ┌──────────────────────┐   ┌────────────────────────┐
│  Portfolio   │   │   Market data ingest  │   │   Macro / sentiment    │
│  ingest      │   │  prices, VIX, breadth │   │   FRED, Cboe P/C, AAII │
│ manual/CSV/  │   │  (Schwab / yfinance)  │   │   NAAIM, news headlines│
│ Schwab API   │   └──────────┬───────────┘   └───────────┬────────────┘
└──────┬───────┘              │                           │
       ▼                      ▼                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Store  (SQLite/DuckDB + parquet)                  │
│   positions · lots · prices · indicators · regime history · recs      │
└──────────────────────────────┬───────────────────────────────────────┘
                               ▼
        ┌──────────────────────────────────────────────────┐
        │                  Signal engine                    │
        │  ┌────────────────┐   ┌────────────────────────┐  │
        │  │ Regime model   │   │ Position rules         │  │
        │  │ trend/breadth/ │──▶│ trend, RS, stops,      │  │
        │  │ vol/credit/    │   │ concentration, tax lots│  │
        │  │ sentiment      │   └────────────────────────┘  │
        │  │                │   ┌────────────────────────┐  │
        │  │                │──▶│ TSP risk dial          │  │
        │  └────────────────┘   │ debounced, IFT-aware   │  │
        │                       └────────────────────────┘  │
        └──────────────────────────┬───────────────────────┘
                                   ▼
        ┌──────────────────────────────────────────────────┐
        │   Recommendation builder + LLM narrative layer    │
        │   (numbers from rules; Claude writes the "why")   │
        └──────────────────────────┬───────────────────────┘
                                   ▼
        ┌──────────────┐  ┌────────────────┐  ┌───────────────────────┐
        │ Morning brief│  │ Alerts         │  │ Dashboard + rec log   │
        │ email/Telegram│ │ Telegram/push  │  │ Streamlit, scorecard  │
        └──────────────┘  └────────────────┘  └───────────────────────┘
```

### Components

| Component | Responsibility | Notes |
|---|---|---|
| Portfolio ingest | Load positions and tax lots from manual entry, Schwab CSV, or Schwab API | Normalizes to one schema: account, symbol, qty, cost, lot date, account type (taxable/IRA/TSP) |
| Market data ingest | Daily OHLCV for holdings, watchlist, index ETFs, S&P 500 constituents | Pluggable provider interface; cache to parquet; idempotent re-runs |
| Macro/sentiment ingest | FRED series, Cboe put/call, VIX term structure, AAII/NAAIM, headline feed | Each source is optional; missing data lowers confidence, never crashes |
| Store | Single-file SQLite (or DuckDB) plus parquet for price history | No server to run; easy to back up; easy to inspect with SQL |
| Regime model | Composite 0–100 score and a discrete regime with hysteresis | See §5 |
| Position rules | Buy / hold / trim / sell per holding, with triggering rule and tax-lot awareness | See §6 |
| TSP risk dial | Target equity-exposure multiplier and a concrete IFT instruction | See §7 |
| Narrative layer | Claude turns the structured signals into a readable brief and answers questions | Claude Opus 5 (`claude-opus-5`) for the daily brief and Q&A; Claude Haiku 4.5 for bulk headline tagging. The model never invents numbers: it only sees the computed signal table |
| Delivery | Email (SMTP) and/or Telegram bot; Streamlit dashboard | Telegram is easiest for push alerts |
| Recommendation log | Every rec with inputs, timestamp, and later outcome | Feeds the scorecard in §9 |
| Backtester | Replays the regime model and TSP dial over history | Uses tsp.gov share prices and SPY/VXF/IEFA-style proxies for validation |

---

## 5. Market regime model (sentiment and structure)

A single daily **Regime Score** from 0 (extreme risk-off) to 100 (extreme risk-on), built from five groups. Each indicator is normalized to a −1..+1 vote, averaged within its group, then weighted.

| Group | Weight | Indicators (initial set) | Source |
|---|---|---|---|
| Trend | 30% | S&P 500 vs 200-day and 50-day MA; 50/200 cross; equal-weight vs cap-weight (RSP/SPY) slope; Nasdaq and Russell 2000 vs their 200-day | Prices |
| Breadth | 20% | % of S&P 500 above 200-day MA; % above 50-day; new 52-week highs minus lows; cumulative advance/decline slope | Constituent prices |
| Volatility | 20% | VIX level bands; VIX term structure (VIX vs VIX3M: backwardation is a strong risk-off vote); 20-day realized vol vs 1-year | Cboe via Yahoo, prices |
| Credit and macro | 20% | High-yield OAS level and 1-month change (FRED `BAMLH0A0HYM2`); 10y–3m curve (`T10Y3M`); Sahm rule (`SAHMREALTIME`); HYG/IEI ratio trend | FRED, prices |
| Sentiment (contrarian) | 10% | AAII bull–bear spread; NAAIM exposure; Cboe total put/call 10-day average. Extreme optimism votes risk-off, extreme fear votes risk-on | Weekly scrapes, Cboe |

**Discrete regime with hysteresis:**

- Risk-on: score ≥ 65
- Neutral: 40–65
- Risk-off: score < 40
- To change regime the score must cross the boundary by at least 5 points **and** stay there 3 consecutive trading days. This is what stops whipsaw.

**Circuit breakers** that override the score immediately (these fire the out-of-cycle alerts in U9):

- VIX closes above 30 and the term structure inverts.
- S&P 500 drops more than 4% in a day, or more than 8% over 5 days.
- High-yield spread widens more than 100 bp in 20 trading days.

All weights and thresholds live in one config file and are what the backtester tunes. They are starting points, not conclusions.

---

## 6. Position-level rules

Runs on every holding and watchlist name, using the regime as context.

**Sell / trim triggers**

- Trailing stop: close below the highest close since entry minus 3×ATR(20) (per-position override allowed).
- Trend break: close below the 200-day MA after being above it for 60+ days, confirmed by 3 closes.
- Relative weakness: 3-month return trails SPY by more than 15 points while regime is neutral or risk-off.
- Concentration: a single position above 15% of the account (or your chosen cap) gets a trim recommendation to the cap.
- Regime risk-off: recommend trimming the highest-beta holdings first to bring account beta toward the risk-off target.

**Buy / add triggers**

- Only when regime is risk-on or neutral.
- Watchlist name in an uptrend (above 50- and 200-day) pulling back to the 50-day with RSI(14) < 45.
- Add to a winner on a breakout to a 52-week high with rising relative strength, capped by the concentration rule.

**Tax-lot awareness (taxable accounts)**

- Prefer selling lots that are long-term; flag if a sell would realize a large short-term gain.
- Flag wash-sale risk if a buy is recommended within 30 days of a loss sale in the same symbol.
- Never applies to IRA or TSP.

**Output for each position**

```
NVDA   HOLD     regime=risk-on  trend=up  RS=+12  stop=$xxx (−9%)  size=11%
AAPL   TRIM     concentration 18% > 15% cap → sell ~3% of account; long-term lots available
XYZ    SELL     trailing stop hit (close $x < stop $y), 3 confirming closes
```

---

## 7. TSP risk dial

The TSP module does not pick funds. It moves an **equity-exposure multiplier** on top of a baseline allocation you define.

1. You set a baseline (for example 60% C / 20% S / 20% I) and a "floor" allocation for full risk-off (for example 30% C / 10% S / 10% I / 50% G).
2. The daily regime score is smoothed with a 10-day average and then mapped to a target multiplier: risk-on 100%, neutral 70%, risk-off 40% of baseline equity. The remainder goes to G (or a G/F split you choose).
3. A recommendation is issued **only if all of these hold**:
   - The target differs from the current TSP allocation by at least 10 points of equity.
   - At least 15 calendar days have passed since the last recommended transfer, unless a circuit breaker fired.
   - You have an unrestricted interfund transfer left this month (the tool tracks the count; you confirm executed transfers).
4. The output is a concrete instruction and a deadline:

   ```
   TSP: reduce equity 70% → 40% of baseline.
   Interfund transfer: C 42%→24%, S 14%→8%, I 14%→8%, G 30%→60%.
   Submit before 12:00 ET today to get today's close. This uses IFT 1 of 2 for September.
   ```

5. Re-risking is deliberately slower than de-risking (require the regime to hold risk-on 10 days before recommending a move back up). Missing a few days of recovery costs less than being whipsawed through two transfers.

Backtest target before this module is enabled: over 2003–present using tsp.gov daily share prices, the dial should show a materially smaller max drawdown than buy-and-hold C Fund with no more than ~6 transfers per year and a total return that is not badly behind. If it can't clear that bar out-of-sample, the thresholds are wrong and it stays off.

---

## 8. Data sources and costs

| Data | Source | Cost | Cadence | Fallback |
|---|---|---|---|---|
| Positions, lots | Manual entry / Schwab CSV / Schwab Trader API | Free | Weekly (CSV) or daily (API) | Manual |
| Daily prices, ETFs, constituents | Schwab market data API; `yfinance` for prototyping | Free | Daily after close | Tiingo or Polygon (~$30/mo) if yfinance breaks |
| VIX, VIX3M, VIX9D | Yahoo (`^VIX`, `^VIX3M`) and FRED `VIXCLS` | Free | Daily | Cboe site |
| HY spreads, yield curve, Sahm, rates | FRED API | Free (API key) | Daily / monthly | none needed |
| Put/call ratios | Cboe daily market statistics | Free | Daily | drop indicator |
| AAII, NAAIM surveys | Web pages (weekly) | Free | Weekly | drop indicator |
| S&P 500 constituent list | Wikipedia table or a maintained CSV | Free | Monthly | pinned list in repo |
| TSP share prices | tsp.gov share price history CSV | Free | Daily | none needed |
| News headlines | RSS (Reuters/AP/CNBC feeds) | Free | Daily | skip narrative news section |
| Narrative and Q&A | Claude API (`claude-opus-5`; Haiku 4.5 for headline tagging) | Roughly $5–15/month at one brief per day plus questions | Daily | Template-only brief |

Everything runs on free data in v1. The paid fallback is a switch, not a rewrite.

---

## 9. Evaluation: how we know it works

- **Backtests** (regime model and TSP dial): CAGR, max drawdown, Sortino, number of trades, time in equities, versus buy-and-hold C Fund and the closest L Fund. Walk-forward with parameters fit on 2003–2017 and tested on 2018–present, so 2020, 2022 and April 2025 are all out-of-sample.
- **Live scorecard** (from day one of running): every recommendation is logged with the inputs. After 20 and 60 trading days the log records what the position or index did. The dashboard shows hit rate, average outcome of "sell" recs vs. holding, and drawdown avoided or return forgone on TSP moves.
- **Whipsaw counter**: number of regime flips per year in backtest and live. More than ~6 means the hysteresis is too loose.
- **Data health**: each run records which sources loaded. A brief produced with a missing group shows a lower confidence badge instead of silently pretending.

---

## 10. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | Python 3.12, `uv` for env and deps | Best ecosystem for this (pandas, backtesting, data clients) |
| Data frames | pandas (polars optional later) | Familiar, plenty fast at daily resolution |
| Storage | SQLite via SQLAlchemy + parquet files | Zero ops; one directory to back up |
| Scheduling | cron or APScheduler on the host | Simple; GitHub Actions cron is an option for the CSV-only phase |
| Brokerage | `schwab-py` (or `schwabdev`) | Handles Schwab OAuth and token refresh |
| Macro | `fredapi` | Thin wrapper over FRED |
| Indicators | `pandas-ta` or hand-written (ATR, RSI, MAs are short) | Avoid heavy TA-Lib build |
| Backtesting | Custom vectorized loop (regime → allocation → daily returns) | Simpler and more transparent than a general framework for allocation-level tests |
| LLM | `anthropic` SDK, `claude-opus-5` with adaptive thinking, structured signal table in the prompt | Narrative quality; never used for the numeric decision |
| Delivery | `python-telegram-bot` and/or SMTP via `smtplib` | Push to phone |
| Dashboard | Streamlit | Fastest path to charts, tables, and a "why" chat box |
| Quality | `pytest`, `ruff`, `mypy` (light), pre-commit | Matches the rigor of the rest of this repo |
| Secrets | `.env` locally, never committed; OS keychain optional | Schwab tokens and API keys |

Hosting recommendation: an always-on box you control (mini PC, Raspberry Pi 5, or your desktop with a scheduled task). The Schwab 7-day re-login needs a browser you can reach, which makes a headless cloud runner awkward. If you want it in the cloud later, a $5/month VPS works and the re-auth can be done from your laptop and the token synced.

Repo layout (proposed, inside this repo as a sibling of the Android project):

```
stock-advisor/
  PROJECT_PLAN.md
  README.md
  pyproject.toml
  config/
    settings.example.toml      # weights, thresholds, baseline TSP allocation, caps
    watchlist.csv
  src/advisor/
    ingest/        portfolio.py  schwab_csv.py  schwab_api.py  prices.py  fred.py  sentiment.py  tsp_prices.py
    store/         db.py  models.py
    signals/       indicators.py  regime.py  positions.py  tsp.py
    narrative/     brief.py  prompts.py  qa.py
    delivery/      telegram.py  email.py
    backtest/      engine.py  reports.py
    cli.py
  dashboard/       app.py
  tests/
  data/            (gitignored: parquet, sqlite)
```

---

## 11. Phased roadmap

Estimates assume part-time work with Claude Code doing most of the typing. "Done when" is the acceptance test for each phase.

### Phase 0 — Decisions and skeleton (week 1)

- Answer the open questions in §13.
- Register the Schwab developer app now so approval runs in the background.
- Get a FRED API key. Create a Telegram bot if that is the delivery channel.
- Scaffold the repo layout, config schema, SQLite models, CLI, CI job (ruff + pytest).

Done when: `advisor run --dry` executes end to end with stub data and CI is green.

### Phase 1 — Portfolio + market regime MVP (weeks 2–3)

- Manual and Schwab-CSV portfolio import with cost basis.
- Price ingest for holdings, watchlist, index ETFs; FRED and VIX ingest.
- Regime model with the Trend, Volatility, and Credit groups (breadth and surveys come later).
- Plain-text morning brief (no LLM yet) sent by Telegram or email.

Done when: a real brief lands on your phone each morning with your positions and a regime score, and the run is idempotent (re-running the same day changes nothing).

### Phase 2 — Position rules and recommendation log (weeks 4–5)

- Trailing stops, trend break, relative strength, concentration cap, tax-lot flags.
- Buy/add rules for watchlist names.
- Recommendation log with inputs; scorecard job that fills in 20/60-day outcomes.
- Streamlit dashboard v1: positions table with actions, regime history chart, rec log.

Done when: each holding shows an action and the rule that fired, and yesterday's recs are visible in the dashboard with their inputs.

### Phase 3 — Backtester and calibration (weeks 5–7)

- Download tsp.gov share-price history and ETF history.
- Replay the regime model; tune weights, thresholds and hysteresis on 2003–2017; report 2018–present out-of-sample.
- Produce a backtest report (drawdown, CAGR, flips per year) checked into `docs/`.

Done when: the out-of-sample report meets the §7 bar or the plan is revised with what we learned.

### Phase 4 — TSP risk dial (week 8)

- Baseline/floor allocation config, multiplier mapping, debounce rules, IFT counter, noon ET deadline logic.
- TSP section in the brief, TSP tab in the dashboard, "I executed this transfer" confirmation command.

Done when: a simulated risk-off day produces a correct, executable interfund transfer instruction and the IFT counter updates when confirmed.

### Phase 5 — Schwab API and breadth (weeks 9–10)

- OAuth flow with `schwab-py`, daily position and transaction sync, token-expiry nag.
- Read-only guard: order endpoints excluded at import time and covered by a test.
- Constituent price download and the Breadth group in the regime model.
- Sentiment survey scrapers (optional inputs).

Done when: positions sync without manual export for 7 consecutive days and the brief shows breadth indicators.

### Phase 6 — LLM narrative, news and Q&A (week 11)

- Claude-written brief from the structured signal table, with a strict rule that every number in the prose comes from the table.
- Headline ingest via RSS; Haiku 4.5 tags headlines by sector and tone; Opus 5 summarizes what matters for your holdings.
- "Why?" chat box in the dashboard grounded in today's signal table and rec log.

Done when: the brief reads well, a spot-check of 10 briefs finds zero numbers not present in the signal table, and monthly LLM cost is under budget.

### Phase 7 — Hardening (week 12)

- Alerting for failed runs and stale data; retries and backoff for every source.
- Backups of the SQLite file; restore test.
- Docs: setup guide, runbook for the Schwab re-login, how to tune thresholds.

Done when: you can rebuild the machine from the README and be back in service in under an hour.

---

## 12. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Whipsaw: regime flips often and the TSP burns its two monthly transfers | High | Hysteresis, minimum days between moves, asymmetric re-risking, flips-per-year metric |
| Overfitting the backtest | High | Walk-forward split, few parameters, prefer round-number thresholds, report out-of-sample only |
| yfinance or a scraper breaks | Medium | Provider interface, cached last-good data, brief shows confidence badge, paid fallback switch |
| Schwab refresh token lapses every 7 days | Medium | Detect early, nag via Telegram, CSV import always works |
| LLM invents a number or a reason | Medium | Model only sees the signal table; automated check that every number in the brief exists in the table |
| Acting on the advisor without judgment | High | Brief always shows the rule and inputs; the scorecard makes the track record visible; TSP module stays off until it clears the backtest bar |
| Tax surprises from sell recs in a taxable account | Medium | Lot-aware flags, short-term gain and wash-sale warnings |
| Secrets leak (tokens in the repo) | High | `.env` and `data/` gitignored, pre-commit secret scan, no order-endpoint code |
| Scope creep into a trading bot | Medium | Non-goals in §1; execution is explicitly out of v1 |

---

## 13. Decisions needed from you (open questions)

These change the plan materially, so please answer before Phase 0 starts. Each has a default I will assume if you don't say otherwise.

1. **Time horizon and style.** Tactical (moves every few weeks, mostly about exposure) or active swing trading on individual stocks (signals most days)? *Default: tactical; position rules run daily but are tuned to fire a few times a month.*
2. **Accounts at Schwab.** Taxable, IRA, or both? Roughly how many positions, and are they mostly single stocks or ETFs? *Default: one taxable and one IRA, 10–25 positions, mixed.*
3. **Delivery channel.** Telegram, email, a web dashboard on your home network, or an Android app (given this repo already ships one)? *Default: Telegram for the brief and alerts, Streamlit dashboard on the host.*
4. **Where it runs.** Your desktop, a home server, or a cloud VPS? *Default: an always-on home machine.*
5. **Schwab connection.** Register for the Trader API now (approval wait, weekly browser re-login) or start with CSV export and add the API in Phase 5? *Default: both; CSV first, API when approved.*
6. **TSP specifics.** Your current allocation, the baseline you want to hold in normal times, and what "reduce risk" means to you (all to G, or a G/F mix; max shift in one move)? *Default: baseline 60/20/20 C/S/I, risk-off floor 50% G, max 30 points of equity per move.*
7. **Budget.** Free data only, or is ~$30/month for a paid price feed acceptable if the free one breaks? LLM spend ceiling? *Default: free data, $15/month LLM cap.*
8. **Language.** Python is the recommendation. Kotlin would be possible for an Android client later but not for the analytics core. *Default: Python.*

---

## 14. First two weeks, concretely

1. You: answer §13; register at developer.schwab.com; request a FRED key.
2. Scaffold `stock-advisor/` with `uv`, `pyproject.toml`, config schema, SQLite models, CLI, ruff + pytest in CI.
3. Implement manual and CSV portfolio import with a fixture built from a real (redacted) Schwab export.
4. Implement price ingest (yfinance behind a provider interface) and FRED ingest; cache to parquet.
5. Implement Trend, Volatility and Credit indicators and the composite score with hysteresis; unit-test the hysteresis with synthetic series.
6. Plain-text brief and Telegram delivery; schedule it at 6:30 ET.
7. First real brief on your phone. Review it together and adjust before Phase 2.
