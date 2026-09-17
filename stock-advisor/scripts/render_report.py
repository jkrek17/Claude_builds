"""Render the weekly report markdown from data.json and tsp.json. Adds a Claude-written
"what changed" section when ANTHROPIC_API_KEY is set; otherwise rule-based sentences only.
Usage: python render_report.py DATA_DIR OUT_MD [portfolio tickers...]
"""
import sys, json, os, datetime as dt
D = sys.argv[1]; OUT = sys.argv[2]
d = json.load(open(f"{D}/data.json")); t = json.load(open(f"{D}/tsp.json")) if os.path.exists(f"{D}/tsp.json") else None
pct = lambda x, n=1: "—" if x is None else f"{x*100:+.{n}f}%"
num = lambda x, n=2: "—" if x is None else f"{x:,.{n}f}"
r = d["regime"]; b = d["breadth"]; fr = d["fred"]; idx = d["index"]
posture = {"risk-on": "RISK-ON: full size on new entries; momentum sleeve at 100% of cap.",
           "neutral": "NEUTRAL: pullback entries only at half size; momentum sleeve at 50% of cap; no new speculative positions.",
           "risk-off": "RISK-OFF: no new longs; trail stops to the 50-day; momentum sleeve at 50% of cap or lower."}[r["label"]]
L = []
L.append(f"# Weekly Report — {d['asof']}\n\nPrices through {d['asof_price']}. Generated automatically; fundamentals from Yahoo Finance are approximate.\n")
L.append(f"**Posture: {posture}** Regime score {r['score']} ({r['label']}).\n")
L.append("## 1. Market analysis\n\n### Regime\n\n| Group | Vote | Reading |\n|---|---|---|")
spy = idx["SPY"]
L.append(f"| Trend | {r['votes']['trend']:+.2f} | SPY {'above' if spy['above200'] else 'below'} 200-day ({'rising' if spy['ma200_rising'] else 'falling'}), {'above' if spy['above50'] else 'below'} 50-day; equal-weight RS 3m {pct(idx['RSP']['rs_3m'])} |")
L.append(f"| Breadth | {r['votes']['breadth']:+.2f} | {b['pct_above50']*100:.0f}% of S&P 500 above 50-day, {b['pct_above200']*100:.0f}% above 200-day; {b['near_highs']} near highs vs {b['near_lows']} near lows |")
L.append(f"| Volatility | {r['votes']['vol']:+.2f} | VIX {idx['^VIX']['price']} ({pct(idx['^VIX']['ret_1m'])} 1m); 20-day realized {r['realized_vol']}% |")
hy = fr.get("BAMLH0A0HYM2", {}); cv = fr.get("T10Y3M", {}); ten = fr.get("DGS10", {}); two = fr.get("DGS2", {})
L.append(f"| Credit / macro | {r['votes']['credit']:+.2f} | HY spread {num(hy.get('last'))}% ({hy.get('chg_1m', 0):+.2f} 1m); 10y–3m {num(cv.get('last'))}; 10y {num(ten.get('last'))}% ({ten.get('chg_1m', 0):+.2f} 1m), 2y {num(two.get('last'))}% ({two.get('chg_1m', 0):+.2f} 1m) |")
L.append("\n### Index snapshot\n\n| | Price | 1w | 1m | 3m | 6m | 12m | vs 50d | vs 200d | From high |\n|---|---|---|---|---|---|---|---|---|---|")
for k in ["SPY", "QQQ", "IWM", "RSP", "TLT", "GLD", "USO", "BTC-USD", "UUP"]:
    if k in idx:
        v = idx[k]; L.append(f"| {k} | {v['price']} | {pct(v['ret_1w'])} | {pct(v['ret_1m'])} | {pct(v['ret_3m'])} | {pct(v['ret_6m'])} | {pct(v['ret_12m'])} | {'above' if v['above50'] else 'below'} | {'above' if v['above200'] else 'below'} | {pct(v['pct_from_hi'])} |")
L.append("\n### Sector rotation (3-month relative strength vs SPY)\n\n| Rank | Sector | 1m | 3m | RS 3m | vs 50d | vs 200d | From high |\n|---|---|---|---|---|---|---|---|")
for i, s in enumerate(d["sectors"], 1):
    L.append(f"| {i} | {s['name']} ({s['etf']}) | {pct(s['ret_1m'])} | {pct(s['ret_3m'])} | {pct(s['rs_3m'])} | {'above' if s['above50'] else 'below'} | {'above' if s['above200'] else 'below'} | {pct(s['pct_from_hi'])} |")
L.append("\n### Themes\n\n| Theme | 1m | 3m | RS 3m | vs 200d | From high |\n|---|---|---|---|---|---|")
for s in d["themes"]:
    L.append(f"| {s['name']} ({s['etf']}) | {pct(s['ret_1m'])} | {pct(s['ret_3m'])} | {pct(s['rs_3m'])} | {'above' if s['above200'] else 'below'} | {pct(s['pct_from_hi'])} |")
L.append("\n### What would change the posture\n")
L.append("- Risk-on: breadth above 50% of stocks over their 50-day, SPY back above its 50-day, high-yield spreads under 3.25%.\n- Risk-off: high-yield spreads above 3.5%, SPY closing below its 200-day, or VIX above 25.\n")
# TSP
L.append("## 2. TSP weekly dial (tested model: trend, volatility, credit)\n")
if t:
    L.append(f"Score **{t['score']}** → **{t['label']}** (was {t['previous_label']}). Votes: trend {t['parts']['trend']:+.2f}, vol {t['parts']['vol']:+.2f}, credit {t['parts']['credit']:+.2f}. Equity now {t['equity_now']*100:.0f}% of the account, target {t['equity_target']*100:.0f}%.\n")
    if t["move"]:
        L.append(f"**ACTION — {t['instruction']}**\n")
    elif t.get("blocked_by"):
        L.append(f"Target differs from current allocation but the move is blocked by the {t['blocked_by']} rule. Re-check next week.\n")
    else:
        L.append("No transfer this week: target matches the current allocation within the 10-point rule.\n")
    L.append("The fuller regime score above (with breadth) is context; only this tested model moves the TSP.\n")
# Portfolio
L.append("## 3. Portfolio\n\n| Symbol | Price | 1m | 3m | vs 50d | vs 200d | From high | ATR/day | Trend template | Cluster | Earnings |\n|---|---|---|---|---|---|---|---|---|---|---|")
for k, v in d["portfolio"].items():
    L.append(f"| {k} | {v['price']} | {pct(v['ret_1m'])} | {pct(v['ret_3m'])} | {'above' if v['above50'] else 'below'} ({v['ma50']}) | {'above' if v['above200'] else 'below'} ({v['ma200']}) | {pct(v['pct_from_hi'])} | {pct(v['atr_pct'])} | {'pass' if v['trend_template'] else 'fail'} | {v['cluster']} | {v.get('earnings_date') or '—'} |")
L.append("\nRules of thumb: two closes below the 50-day = sell half (momentum sleeve); close below the 200-day = out (core); up >25% in 3 weeks or >80% above the 200-day = sell half into strength.\n")
# Screens
def qual(x):
    return (x.get("roe") or 0) > 0.15 and (x.get("op_margin") or 0) > 0.10 and (x.get("fcf_yield") or 0) > 0.03 and ((x.get("pe_fwd") or 99) < 25 or (x.get("peg") or 99) < 1.5)
core = [x for x in d["screen_top"] if qual(x)][:10]
L.append(f"## 4. Core targets (trend template + quality + fair price; {d['screen_count']} of {b['n']} pass the trend template)\n\n| Symbol | Price | Fwd P/E | PEG | ROE | FCF yield | Rev growth | From high | Base high | Stop (8%) |\n|---|---|---|---|---|---|---|---|---|---|")
for x in core:
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {num(x.get('pe_fwd'),1)} | {num(x.get('peg'),1)} | {pct(x.get('roe'),0)} | {pct(x.get('fcf_yield'))} | {pct(x.get('rev_growth'),0)} | {pct(x['pct_from_hi'])} | {x['base_high']} | {x['price']*0.92:.2f} |")
mom = [x for x in d["screen_top"] if (x.get("rev_growth") or 0) > 0.25 and (x.get("gross_margin") or 0) > 0.4 and x["pct_from_hi"] > -0.15][:6]
L.append("\n## 5. Momentum / growth targets (rev growth >25%, gross margin >40%, within 15% of high)\n\n| Symbol | Price | Rev growth | Gross margin | RS 3m | From high | Base high | Stop (12%) |\n|---|---|---|---|---|---|---|---|")
for x in mom:
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {pct(x.get('rev_growth'),0)} | {pct(x.get('gross_margin'),0)} | {pct(x['rs_3m'])} | {pct(x['pct_from_hi'])} | {x['base_high']} | {x['price']*0.88:.2f} |")
# ---- Titans, value, long-term, rotation ----
T = d.get("titans", {})
def row(x, cols):
    return "| " + " | ".join(cols(x)) + " |"
L.append(f"\n## 6. Good companies below fair value (quality floor, margin of safety ≥ 20%; {T.get('counts', {}).get('value', 0)} qualify)\n")
L.append("Fair value = average of a two-stage DCF (5 years at the company's revenue growth capped at 15%, then 3%, discounted at 10%) and Graham's rate-adjusted earnings formula (growth capped at 10%, AAA yield " + num(fr.get("AAA", {}).get("last")) + "%). Financials and real estate excluded (different economics). Analyst target for reference only.\n")
L.append("| Symbol | Price | Fair value | Margin of safety | DCF | Graham | Analyst target | ROE | FCF yield | Fwd P/E | vs 200d | From high | Note |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for x in T.get("value", []):
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {num(x['fair'])} | {pct(x['mos'],0)} | {num(x.get('fair_fcf'))} | {num(x.get('fair_graham'))} | {num(x.get('target'))} | {pct(x.get('roe'),0)} | {pct(x.get('fcf_yield'))} | {num(x.get('pe_fwd'),1)} | {'above' if x['above200'] else 'below'} | {pct(x['pct_from_hi'])} | {x.get('note','')} |")
L.append(f"\n## 7. Own for years (quality compounders; a pullback is the entry, not a disqualifier; {T.get('counts', {}).get('longterm', 0)} qualify)\n")
L.append("| Symbol | Price | ROE | Op margin | Gross margin | FCF yield | Rev growth | Fwd P/E | Fair value | MoS | vs 200d | From high | Entry | Note |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for x in T.get("longterm", []):
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {pct(x.get('roe'),0)} | {pct(x.get('opm'),0)} | {pct(x.get('gm'),0)} | {pct(x.get('fcf_yield'))} | {pct(x.get('rev_growth'),0)} | {num(x.get('pe_fwd'),1)} | {num(x.get('fair'))} | {pct(x.get('mos'),0)} | {pct(x.get('dist_200'))} | {pct(x['pct_from_hi'])} | {x['entry']} | {x.get('note','')} |")
L.append("\n## 8. What each titan would like this week\n")
L.append("### Buffett / Munger — wonderful businesses at a fair price\n\n| Symbol | Price | ROE | Op margin | Gross margin | FCF yield | Fwd P/E | Debt/Eq | MoS |\n|---|---|---|---|---|---|---|---|---|")
for x in T.get("buffett", []):
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {pct(x.get('roe'),0)} | {pct(x.get('opm'),0)} | {pct(x.get('gm'),0)} | {pct(x.get('fcf_yield'))} | {num(x.get('pe_fwd'),1)} | {num(x.get('de'),0)} | {pct(x.get('mos'),0)} |")
L.append("\n### Greenblatt — Magic Formula (earnings yield + return on capital, ex-financials)\n\n| Rank | Symbol | Price | Earnings yield | ROA | Fwd P/E | vs 200d |\n|---|---|---|---|---|---|---|")
for i, x in enumerate(T.get("greenblatt", []), 1):
    L.append(f"| {i} | {x['ticker']} {x.get('name') or ''} | {x['price']} | {pct(x.get('earnings_yield'))} | {pct(x.get('roa'))} | {num(x.get('pe_fwd'),1)} | {'above' if x['above200'] else 'below'} |")
L.append("\n### Lynch — growth at a reasonable price (PEG < 1.2, EPS growth 15–60%)\n\n| Symbol | Price | PEG | EPS growth | Rev growth | Fwd P/E | Debt/Eq | vs 200d |\n|---|---|---|---|---|---|---|---|")
for x in T.get("lynch", []):
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {num(x.get('peg'),2)} | {pct(x.get('eps_growth'),0)} | {pct(x.get('rev_growth'),0)} | {num(x.get('pe_fwd'),1)} | {num(x.get('de'),0)} | {'above' if x['above200'] else 'below'} |")
md = T.get("market_direction", "neutral")
md_note = " (O'Neil would not be buying)" if md == "risk-off" else ""
L.append(f"\n### O'Neil — CANSLIM (at least 5 of C, A, N, S, L, I; M = market direction is **{md}**{md_note})\n\n| Symbol | Price | Letters | Qtr EPS growth | Annual EPS growth | RS pct | Inst. | From high |\n|---|---|---|---|---|---|---|---|")
for x in T.get("canslim", []):
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {x['canslim']} | {pct(x.get('eps_q_growth'),0)} | {pct(x.get('eps_growth'),0)} | {x.get('rs_pct')} | {pct(x.get('inst'),0)} | {pct(x['pct_from_hi'])} |")
L.append("\n### Tudor Jones — fresh reclaims of the 200-day (trend turning, not yet a leader)\n\n| Symbol | Price | Above 200d by | RS 1m | RS 3m | From high |\n|---|---|---|---|---|---|")
for x in T.get("tudor_jones", []):
    L.append(f"| {x['ticker']} {x.get('name') or ''} | {x['price']} | {pct(x.get('dist_200'))} | {pct(x.get('rs_1m'))} | {pct(x.get('rs_3m'))} | {pct(x['pct_from_hi'])} |")
L.append("\n### Druckenmiller — top-down, 12–18 months out\n\nSee the sector table in §1 and the rotation table below. His rule is to position for where liquidity and the cycle will be, not where they are; the narrative for this week is written from those tables.\n")
L.append("\n## 9. Ahead of the rotation\n\n### Sectors and themes: washed out and turning?\n\n| Status | Sector / theme | RS 1m | RS 3m | RS 6m | vs 50d | vs 200d | From high |\n|---|---|---|---|---|---|---|---|")
for x in d.get("rotation_sectors", []):
    L.append(f"| {x['status']} | {x['name']} ({x['etf']}) | {pct(x['rs_1m'])} | {pct(x['rs_3m'])} | {pct(x['rs_6m'])} | {'above' if x['above50'] else 'below'} | {'above' if x['above200'] else 'below'} | {pct(x['pct_from_hi'])} |")
L.append("\n### Stocks: bottom-quartile 12-month performers, >25% off their high, with a turn starting (above 50-day, 1-month RS positive)\n\n| Status | Symbol | Price | 12m return | RS 1m | RS 3m | From high | vs 200d | ROE | FCF yield | Fwd P/E | MoS |\n|---|---|---|---|---|---|---|---|---|---|---|---|")
for x in T.get("rotation", []):
    L.append(f"| {x['status']} | {x['ticker']} {x.get('name') or ''} | {x['price']} | {pct(x.get('ret_12m'),0)} | {pct(x.get('rs_1m'))} | {pct(x.get('rs_3m'))} | {pct(x['pct_from_hi'])} | {'above' if x['above200'] else 'below'} | {pct(x.get('roe'),0)} | {pct(x.get('fcf_yield'))} | {num(x.get('pe_fwd'),1)} | {pct(x.get('mos'),0)} |")
# Claude narrative
key = os.environ.get("ANTHROPIC_API_KEY")
if key:
    try:
        import anthropic
        client = anthropic.Anthropic()
        table = json.dumps(dict(regime=r, breadth=b, fred=fr, index={k: idx[k] for k in ["SPY", "QQQ", "IWM", "^VIX", "USO", "BTC-USD"] if k in idx},
                                sectors=d["sectors"][:4] + d["sectors"][-3:], themes=d["themes"][:3] + d["themes"][-3:], tsp=t, portfolio=d["portfolio"]), default=str)
        msg = client.beta.messages.create(
            model="claude-opus-5", max_tokens=1500, betas=["server-side-fallback-2026-07-01"], fallbacks="default",
            system="You write the 'what changed this week' section of a personal weekly market report. Use only numbers present in the JSON table you are given; do not invent any figure. Plain prose, no headers, 4-7 short bullets, then one paragraph on what the numbers imply for the posture. Cite the metric you rely on in each bullet.",
            messages=[{"role": "user", "content": "Signal table (JSON):\n" + table}])
        if msg.stop_reason != "refusal":
            text = "".join(blk.text for blk in msg.content if getattr(blk, "type", "") == "text")
            L.insert(3, "### What changed this week (Claude, from the signal table)\n\n" + text + "\n")
    except Exception as e:
        L.append(f"\n_Narrative unavailable: {e}_\n")
L.append("\n---\n*Output of the rules in TITANS_PLAYBOOK.md and PROJECT_PLAN.md; not personal financial advice. Data: Yahoo Finance, FRED, Wikipedia.*\n")
open(OUT, "w").write("\n".join(L))
print("wrote", OUT)
