"""Pull market, sector, screen, and portfolio data for the weekly report.

Usage: python weekly_report_data.py OUT_DIR
Writes OUT_DIR/data.json and OUT_DIR/tables.md. Free sources only:
Yahoo Finance (via yfinance) for prices, FRED CSV for credit/rates, Wikipedia for the universe.
"""
import json, sys, io, math, time, datetime as dt
import numpy as np, pandas as pd, yfinance as yf, urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "."
PORTFOLIO = ["MSTR", "ASST", "NBIS", "ASTS", "BABA", "HIMS", "TSLA"]
CLUSTERS = {"MSTR": "Bitcoin beta", "ASST": "Bitcoin beta", "NBIS": "AI infrastructure", "ASTS": "Space/telecom",
            "BABA": "China internet", "HIMS": "Telehealth/consumer", "TSLA": "EV/AI/robotics"}
INDEX = ["SPY", "QQQ", "IWM", "RSP", "DIA", "^VIX", "^VIX3M", "HYG", "IEI", "TLT", "GLD", "USO", "BTC-USD", "UUP"]
SECTORS = {"XLK": "Technology", "XLC": "Communication", "XLY": "Consumer Disc.", "XLP": "Consumer Staples",
           "XLV": "Health Care", "XLF": "Financials", "XLI": "Industrials", "XLE": "Energy", "XLB": "Materials",
           "XLU": "Utilities", "XLRE": "Real Estate"}
THEMES = {"SMH": "Semis", "IBIT": "Bitcoin", "ARKK": "Spec. growth", "XBI": "Biotech", "ITA": "Defense",
          "KWEB": "China internet", "URA": "Uranium", "XME": "Metals/mining", "XHB": "Homebuilders", "IGV": "Software"}
FRED = {"BAMLH0A0HYM2": "HY OAS", "T10Y2Y": "10y-2y", "T10Y3M": "10y-3m", "DGS10": "10y", "DGS2": "2y",
        "SAHMREALTIME": "Sahm", "WALCL": "Fed balance sheet", "DFF": "Fed funds"}

def fred(series):
    url = f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={series}"
    df = pd.read_csv(io.StringIO(urllib.request.urlopen(url, timeout=30).read().decode()), na_values=".")
    df.columns = ["date", "v"]
    df["date"] = pd.to_datetime(df["date"]); return df.dropna().set_index("date")["v"]

def universe():
    hdr = {"User-Agent": "Mozilla/5.0"}
    def tables(url):
        html = urllib.request.urlopen(urllib.request.Request(url, headers=hdr), timeout=30).read().decode()
        return pd.read_html(io.StringIO(html))
    sp = tables("https://en.wikipedia.org/wiki/List_of_S%26P_500_companies")[0]
    sp_t = sp["Symbol"].astype(str).str.replace(".", "-", regex=False).tolist()
    sect = dict(zip(sp_t, sp["GICS Sector"]))
    nq_t = []
    try:
        for t in tables("https://en.wikipedia.org/wiki/Nasdaq-100"):
            if "Ticker" in t.columns or "Symbol" in t.columns:
                col = "Ticker" if "Ticker" in t.columns else "Symbol"
                nq_t = t[col].astype(str).str.replace(".", "-", regex=False).tolist(); break
    except Exception as e:
        print("nasdaq100 failed", e)
    u = sorted(set(sp_t) | set(nq_t))
    return u, sect

def download(tickers, period="2y"):
    import pickle, os
    cache = f"{OUT}/px_cache.pkl"
    if os.path.exists(cache) and time.time() - os.path.getmtime(cache) < 6 * 3600:
        return pickle.load(open(cache, "rb"))
    frames = {}
    for i in range(0, len(tickers), 150):
        chunk = tickers[i:i+150]
        d = yf.download(chunk, period=period, interval="1d", auto_adjust=True, progress=False, threads=True, group_by="ticker")
        for t in chunk:
            try:
                df = d[t] if len(chunk) > 1 else d
                df = df.dropna(subset=["Close"])
                if len(df) > 50: frames[t] = df
            except Exception: pass
        time.sleep(1)
    pickle.dump(frames, open(cache, "wb"))
    return frames

def stats(df, spy):
    c = df["Close"]; h = df["High"]; l = df["Low"]; v = df["Volume"]
    last = float(c.iloc[-1])
    def ret(n): return float(c.iloc[-1] / c.iloc[-1-n] - 1) if len(c) > n else np.nan
    def rs(n):
        if len(c) <= n or len(spy) <= n: return np.nan
        return ret(n) - float(spy.iloc[-1] / spy.iloc[-1-n] - 1)
    ma50 = float(c.rolling(50).mean().iloc[-1]); ma150 = float(c.rolling(150).mean().iloc[-1]) if len(c) >= 150 else np.nan
    ma200 = float(c.rolling(200).mean().iloc[-1]) if len(c) >= 200 else np.nan
    ma200_1m = float(c.rolling(200).mean().iloc[-22]) if len(c) >= 222 else np.nan
    ma10w = float(c.rolling(50).mean().iloc[-1])
    hi52 = float(c.iloc[-252:].max()); lo52 = float(c.iloc[-252:].min())
    tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
    atr20 = float(tr.rolling(20).mean().iloc[-1])
    vol50 = float(v.rolling(50).mean().iloc[-1]) if v.notna().any() else np.nan
    tmpl = (not np.isnan(ma200)) and last > ma150 > ma200 and ma200 > ma200_1m and ma50 > ma150 and last >= lo52 * 1.3 and last >= hi52 * 0.75
    base_hi = float(c.iloc[-40:-1].max())
    return dict(price=round(last, 2), ret_1w=ret(5), ret_1m=ret(21), ret_3m=ret(63), ret_6m=ret(126), ret_12m=ret(252),
                rs_1m=rs(21), rs_3m=rs(63), rs_6m=rs(126), ma50=round(ma50, 2), ma200=None if np.isnan(ma200) else round(ma200, 2),
                above50=last > ma50, above200=(not np.isnan(ma200)) and last > ma200, pct_from_hi=last / hi52 - 1, pct_from_lo=last / lo52 - 1,
                atr_pct=atr20 / last, trend_template=bool(tmpl), base_high=round(base_hi, 2), ma200_rising=(not np.isnan(ma200)) and ma200 > ma200_1m,
                dd_from_hi=last / hi52 - 1, vol_ratio=float(v.iloc[-5:].mean() / vol50) if vol50 else np.nan)

def main():
    out = {"asof": str(dt.date.today())}
    uni, sectors = universe(); print("universe", len(uni))
    px = download(sorted(set(uni + PORTFOLIO + INDEX + list(SECTORS) + list(THEMES))))
    spy = px["SPY"]["Close"]
    out["asof_price"] = str(spy.index[-1].date())
    # ---- FRED ----
    fr = {}
    for k, name in FRED.items():
        try:
            s = fred(k); fr[k] = dict(name=name, last=float(s.iloc[-1]), date=str(s.index[-1].date()),
                                     chg_1m=float(s.iloc[-1] - s.iloc[-22]) if len(s) > 22 else None,
                                     chg_3m=float(s.iloc[-1] - s.iloc[-66]) if len(s) > 66 else None)
        except Exception as e: print("fred fail", k, e)
    out["fred"] = fr
    # ---- index/ETF stats ----
    idx = {t: stats(px[t], spy) for t in INDEX + list(SECTORS) + list(THEMES) if t in px}
    out["index"] = idx
    # ---- breadth over universe ----
    ab200 = ab50 = n = nh = nl = 0
    ustats = {}
    for t in uni:
        if t not in px: continue
        st = stats(px[t], spy); ustats[t] = st; n += 1
        ab200 += st["above200"]; ab50 += st["above50"]
        nh += st["pct_from_hi"] > -0.02; nl += st["pct_from_lo"] < 0.02
    out["breadth"] = dict(n=n, pct_above200=ab200 / n, pct_above50=ab50 / n, near_highs=nh, near_lows=nl)
    # ---- regime score (plan §5, sentiment group omitted, weights renormalized) ----
    spx = idx["SPY"]; vix = idx["^VIX"]["price"]; vix3m = idx["^VIX3M"]["price"] if "^VIX3M" in idx else None
    votes = {}
    votes["trend"] = np.mean([1 if spx["above200"] else -1, 1 if spx["above50"] else -1,
                              1 if idx["RSP"]["rs_3m"] > 0 else -1, 1 if idx["IWM"]["above200"] else -1, 1 if idx["QQQ"]["above200"] else -1])
    b = out["breadth"]
    votes["breadth"] = np.mean([1 if b["pct_above200"] > 0.6 else -1 if b["pct_above200"] < 0.4 else 0,
                                1 if b["pct_above50"] > 0.55 else -1 if b["pct_above50"] < 0.45 else 0,
                                1 if b["near_highs"] > b["near_lows"] else -1])
    rv = px["SPY"]["Close"].pct_change().iloc[-20:].std() * math.sqrt(252) * 100
    votes["vol"] = np.mean([1 if vix < 16 else -1 if vix > 25 else 0, (1 if vix < vix3m else -1) if vix3m else 0, 1 if rv < vix else -1])
    hy = fr.get("BAMLH0A0HYM2", {}); curve = fr.get("T10Y3M", {}); sahm = fr.get("SAHMREALTIME", {})
    votes["credit"] = np.mean([1 if hy.get("last", 4) < 3.5 else -1 if hy.get("last", 4) > 4.5 else 0,
                               -1 if (hy.get("chg_1m") or 0) > 0.5 else 1 if (hy.get("chg_1m") or 0) < -0.25 else 0,
                               1 if curve.get("last", 0) > 0 else -1, -1 if sahm.get("last", 0) >= 0.5 else 1,
                               1 if idx["HYG"]["rs_3m"] > idx["IEI"]["rs_3m"] else -1])
    w = {"trend": 0.33, "breadth": 0.22, "vol": 0.22, "credit": 0.23}
    score = 50 + 50 * sum(w[k] * votes[k] for k in w)
    out["regime"] = dict(score=round(score, 1), votes={k: round(float(v), 2) for k, v in votes.items()},
                         label="risk-on" if score >= 65 else "risk-off" if score < 40 else "neutral", realized_vol=round(float(rv), 1))
    # ---- sector / theme ranks ----
    def rank_tbl(d):
        rows = [dict(etf=t, name=nm, **{k: idx[t][k] for k in ["ret_1m", "ret_3m", "rs_1m", "rs_3m", "above200", "above50", "pct_from_hi"]}) for t, nm in d.items() if t in idx]
        rows.sort(key=lambda r: r["rs_3m"], reverse=True); return rows
    out["sectors"] = rank_tbl(SECTORS); out["themes"] = rank_tbl(THEMES)
    # ---- screen: trend template + RS composite ----
    rows = []
    for t, st in ustats.items():
        if not st["trend_template"]: continue
        comp = 0.4 * st["rs_6m"] + 0.4 * st["rs_3m"] + 0.2 * st["rs_1m"]
        rows.append(dict(ticker=t, sector=sectors.get(t, ""), composite=comp, **st))
    rows.sort(key=lambda r: r["composite"], reverse=True)
    out["screen_count"] = len(rows)
    top = rows[:40]
    # fundamentals for the top names (yfinance info; preliminary quality)
    for r in top:
        try:
            info = yf.Ticker(r["ticker"]).info
            r.update(dict(name=info.get("shortName"), mcap=info.get("marketCap"), pe_fwd=info.get("forwardPE"), peg=info.get("pegRatio"),
                          rev_growth=info.get("revenueGrowth"), eps_growth=info.get("earningsGrowth"), roe=info.get("returnOnEquity"),
                          gross_margin=info.get("grossMargins"), op_margin=info.get("operatingMargins"), fcf=info.get("freeCashflow"),
                          ev=info.get("enterpriseValue"), debt_to_equity=info.get("debtToEquity"), earnings_date=str(info.get("earningsTimestamp", ""))))
            r["fcf_yield"] = (r["fcf"] / r["ev"]) if r.get("fcf") and r.get("ev") else None
        except Exception as e:
            r["name"] = None
        time.sleep(0.3)
    out["screen_top"] = top
    # ---- portfolio ----
    port = {}
    for t in PORTFOLIO:
        if t not in px: continue
        st = stats(px[t], spy); st["cluster"] = CLUSTERS[t]
        try:
            tk = yf.Ticker(t); info = tk.info
            st.update(dict(name=info.get("shortName"), mcap=info.get("marketCap"), rev_growth=info.get("revenueGrowth"),
                           gross_margin=info.get("grossMargins"), fcf=info.get("freeCashflow"), cash=info.get("totalCash"), debt=info.get("totalDebt"),
                           shares=info.get("sharesOutstanding"), short_pct=info.get("shortPercentOfFloat"), pe_fwd=info.get("forwardPE"),
                           beta=info.get("beta")))
            cal = tk.calendar
            ed = cal.get("Earnings Date") if isinstance(cal, dict) else None
            st["earnings_date"] = str(ed[0]) if ed else None
        except Exception as e:
            st["name"] = None
        port[t] = st
    out["portfolio"] = port
    # ---- write ----
    def clean(o):
        if isinstance(o, dict): return {k: clean(v) for k, v in o.items()}
        if isinstance(o, list): return [clean(v) for v in o]
        if isinstance(o, (np.floating, float)): return None if (o is None or (isinstance(o, float) and math.isnan(o))) else float(o)
        if isinstance(o, (np.bool_,)): return bool(o)
        if isinstance(o, (np.integer,)): return int(o)
        return o
    json.dump(clean(out), open(f"{OUT}/data.json", "w"), indent=1, default=str)
    print("done")

if __name__ == "__main__":
    main()
