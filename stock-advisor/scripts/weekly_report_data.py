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
        "SAHMREALTIME": "Sahm", "WALCL": "Fed balance sheet", "DFF": "Fed funds", "AAA": "Moody's AAA yield"}

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

INFO_FIELDS = ["shortName", "sector", "industry", "marketCap", "enterpriseValue", "forwardPE", "trailingPE", "pegRatio",
               "revenueGrowth", "earningsGrowth", "returnOnEquity", "returnOnAssets", "grossMargins", "operatingMargins",
               "freeCashflow", "totalRevenue", "ebitda", "debtToEquity", "totalCash", "totalDebt", "priceToBook",
               "dividendYield", "heldPercentInsiders", "earningsTimestamp", "forwardEps", "trailingEps", "sharesOutstanding",
               "earningsQuarterlyGrowth", "heldPercentInstitutions", "targetMeanPrice", "numberOfAnalystOpinions", "beta"]

def fundamentals(tickers):
    """yfinance .info for the universe, cached 7 days. Slow (~0.4s/name) but free."""
    import pickle, os
    cache = f"{OUT}/info_cache.pkl"
    data = pickle.load(open(cache, "rb")) if os.path.exists(cache) and time.time() - os.path.getmtime(cache) < 7 * 86400 else {}
    todo = [t for t in tickers if t not in data]
    for i, t in enumerate(todo):
        try:
            info = yf.Ticker(t).info
            data[t] = {k: info.get(k) for k in INFO_FIELDS}
        except Exception:
            data[t] = {}
        if i % 50 == 49:
            pickle.dump(data, open(cache, "wb")); time.sleep(1)
    pickle.dump(data, open(cache, "wb"))
    return data

def fair_value(f, price, aaa_yield, sector=""):
    """Two transparent models. Returns (fair, dcf_model, graham_model). None for financials/real estate.
    DCF: 5 years of FCF growth at g1 = revenue growth clamped 0-15%, then 3% terminal, 10% discount rate.
    Graham (1974 revision): EPS * (8.5 + 2g) * 4.4 / AAA yield, g = EPS growth clamped 0-10 (in %), so the
    multiple never exceeds 28.5 before the rate adjustment. Fair = average of the two when both exist."""
    if sector in ("Financials", "Real Estate"): return None, None, None
    sh = f.get("sharesOutstanding"); fcf = f.get("freeCashflow"); eps = f.get("forwardEps") or f.get("trailingEps")
    g_rev = f.get("revenueGrowth"); g_eps = f.get("earningsGrowth")
    dcf = None
    if sh and fcf and fcf > 0:
        g1 = min(max(g_rev if g_rev is not None else 0.04, 0.0), 0.15); r, g2 = 0.10, 0.03
        cf = fcf / sh; pv = 0.0
        for t in range(1, 6):
            cf *= (1 + g1); pv += cf / (1 + r) ** t
        pv += cf * (1 + g2) / (r - g2) / (1 + r) ** 5
        dcf = pv
    gr = None
    if eps and eps > 0:
        g = min(max((g_eps if g_eps is not None else 0.0) * 100, 0), 10)
        gr = eps * (8.5 + 2 * g) * 4.4 / max(aaa_yield, 3.0)
    vals = [v for v in (dcf, gr) if v]
    return (sum(vals) / len(vals) if vals else None), dcf, gr

def titan_lenses(ustats, funda, sectors, aaa_yield=5.5, regime_label="neutral"):
    """Per-titan candidate lists. Each returns rows sorted best-first with the lens's own key metrics."""
    rows = []
    for t, st in ustats.items():
        f = funda.get(t) or {}
        ev = f.get("enterpriseValue"); rev = f.get("totalRevenue"); opm = f.get("operatingMargins")
        ebit = opm * rev if (opm is not None and rev) else None
        r = dict(ticker=t, name=f.get("shortName"), sector=sectors.get(t, ""), price=st["price"], mcap=f.get("marketCap"),
                 pe_fwd=f.get("forwardPE"), peg=f.get("pegRatio"), rev_growth=f.get("revenueGrowth"), eps_growth=f.get("earningsGrowth"),
                 roe=f.get("returnOnEquity"), roa=f.get("returnOnAssets"), gm=f.get("grossMargins"), opm=opm,
                 fcf_yield=(f["freeCashflow"] / ev) if (f.get("freeCashflow") and ev) else None,
                 earnings_yield=(ebit / ev) if (ebit and ev) else None, de=f.get("debtToEquity"), div=f.get("dividendYield"),
                 pct_from_hi=st["pct_from_hi"], above50=st["above50"], above200=st["above200"], ma200_rising=st["ma200_rising"],
                 rs_1m=st["rs_1m"], rs_3m=st["rs_3m"], rs_6m=st["rs_6m"], rs_12m=(st["ret_12m"] or 0) - 0, ma50=st["ma50"], ma200=st["ma200"],
                 trend_template=st["trend_template"], dist_200=(st["price"] / st["ma200"] - 1) if st["ma200"] else None,
                 eps_q_growth=f.get("earningsQuarterlyGrowth"), inst=f.get("heldPercentInstitutions"), target=f.get("targetMeanPrice"),
                 n_analysts=f.get("numberOfAnalystOpinions"), vol_ratio=st.get("vol_ratio"), ret_12m=st["ret_12m"])
        fv, fcf_v, gr_v = fair_value(f, st["price"], aaa_yield, sectors.get(t, ""))
        r.update(fair=fv, fair_fcf=fcf_v, fair_graham=gr_v, mos=(fv / st["price"] - 1) if fv else None,
                 target_upside=(r["target"] / st["price"] - 1) if r.get("target") else None)
        rows.append(r)
    # 12-month RS percentile for CANSLIM "L"
    ranked = sorted([r for r in rows if r.get("rs_12m") is not None], key=lambda r: r["rs_12m"])
    for i, r in enumerate(ranked): r["rs_pct"] = int(100 * i / max(1, len(ranked) - 1))
    fin = {"Financials", "Utilities", "Real Estate"}
    g = lambda r, k, d=0: r[k] if r.get(k) is not None else d
    buffett = [r for r in rows if g(r, "mcap") > 10e9 and g(r, "roe") > 0.15 and g(r, "opm") > 0.15 and g(r, "gm") > 0.40
               and g(r, "de", 999) < 120 and g(r, "fcf_yield") > 0.035 and g(r, "pe_fwd", 99) < 25 and g(r, "eps_growth", 0) > 0]
    buffett.sort(key=lambda r: -(g(r, "fcf_yield") * (1 + g(r, "roe"))))
    mf = [r for r in rows if r["sector"] not in fin and g(r, "mcap") > 2e9 and r.get("earnings_yield") and r.get("roa")]
    n = len(mf)
    ey_rank = {r["ticker"]: i for i, r in enumerate(sorted(mf, key=lambda r: -r["earnings_yield"]))}
    roc_rank = {r["ticker"]: i for i, r in enumerate(sorted(mf, key=lambda r: -r["roa"]))}
    for r in mf: r["mf_rank"] = ey_rank[r["ticker"]] + roc_rank[r["ticker"]]
    greenblatt = sorted(mf, key=lambda r: r["mf_rank"])
    lynch = [r for r in rows if g(r, "peg", 99) < 1.2 and 0.15 <= g(r, "eps_growth") <= 0.60 and g(r, "rev_growth") > 0.08 and g(r, "de", 999) < 100 and g(r, "mcap") > 2e9]
    lynch.sort(key=lambda r: g(r, "peg", 99))
    # Tudor Jones: fresh reclaim of the 200-day (above now, was below 10 trading days ago) -- approximated by above200 and dist_200 < 5%
    ptj = [r for r in rows if r["above200"] and r.get("dist_200") is not None and 0 <= r["dist_200"] < 0.05 and not r["trend_template"] and g(r, "rs_1m") > 0]
    ptj.sort(key=lambda r: -g(r, "rs_1m"))
    # Long-term "own for years": quality first, then valuation, then a pullback entry is a feature
    longterm = [r for r in rows if g(r, "mcap") > 10e9 and g(r, "roe") > 0.18 and g(r, "opm") > 0.18 and g(r, "gm") > 0.45
                and g(r, "de", 999) < 150 and g(r, "fcf_yield") > 0.025 and g(r, "rev_growth") > 0.04 and g(r, "pe_fwd", 99) < 32]
    for r in longterm:
        r["quality"] = g(r, "roe") + g(r, "opm") + g(r, "gm") / 2
        r["note"] = "cyclical" if r["sector"] in ("Energy", "Materials") or g(r, "rev_growth") > 1.0 else ""
        r["entry"] = ("BUY ZONE: at/near 200-day" if r["dist_200"] is not None and -0.05 <= r["dist_200"] <= 0.05 else
                      "BUY ZONE: 15-30% pullback in a quality name" if -0.30 <= r["pct_from_hi"] <= -0.15 else
                      "wait: extended" if r["dist_200"] is not None and r["dist_200"] > 0.15 else "accumulate on weakness")
    longterm.sort(key=lambda r: -(r["quality"] * (1 + g(r, "fcf_yield") * 10)))
    # Rotation-ahead: washed out (12m RS bottom quartile, >25% off high) and turning (1m RS > 0, above 50-day, 50-day rising)
    rs12 = sorted(g(r, "rs_12m") for r in rows); q1 = rs12[len(rs12) // 4] if rs12 else 0
    def turning(r): return r["above50"] and g(r, "rs_1m") > 0
    washed = [r for r in rows if g(r, "rs_12m") <= q1 and r["pct_from_hi"] < -0.25 and g(r, "mcap") > 5e9]
    for r in washed: r["status"] = "TURNING" if turning(r) else "washed out, not yet"
    washed.sort(key=lambda r: (r["status"] != "TURNING", -g(r, "rs_1m")))
    # Good companies below fair value: quality floor, then margin of safety >= 20%
    value = [r for r in rows if r.get("mos") is not None and r.get("fair_fcf") and r.get("fair_graham") and g(r, "mcap") > 5e9
             and g(r, "roe") > 0.12 and g(r, "opm") > 0.10 and g(r, "fcf_yield") > 0.03 and g(r, "de", 999) < 200 and r["mos"] >= 0.20]
    for r in value: r["note"] = "cyclical: check peak margins" if r["sector"] in ("Energy", "Materials") else ""
    value.sort(key=lambda r: -r["mos"])
    # CANSLIM: C quarterly EPS growth >=25%, A annual EPS growth >=25% (yoy proxy), N within 15% of high, S demand (recent volume above 50-day avg),
    # L RS percentile >= 80, I institutional sponsorship 30-95%. M = market direction from the regime (global flag).
    cans = []
    for r in rows:
        checks = dict(C=g(r, "eps_q_growth") >= 0.25, A=g(r, "eps_growth") >= 0.25, N=r["pct_from_hi"] >= -0.15,
                      S=g(r, "vol_ratio") >= 1.0, L=g(r, "rs_pct") >= 80, I=0.30 <= g(r, "inst") <= 0.95)
        n = sum(checks.values())
        if n >= 5 and g(r, "mcap") > 2e9:
            r2 = dict(r); r2["canslim"] = "".join(k for k, v in checks.items() if v); r2["canslim_n"] = n; cans.append(r2)
    cans.sort(key=lambda r: (-r["canslim_n"], -g(r, "rs_pct")))
    return dict(buffett=buffett[:12], greenblatt=greenblatt[:12], lynch=lynch[:12], tudor_jones=ptj[:12], longterm=longterm[:15],
                rotation=washed[:20], value=value[:15], canslim=cans[:15], market_direction=regime_label,
                counts=dict(buffett=len(buffett), greenblatt=len(mf), lynch=len(lynch), value=len(value), canslim=len(cans), longterm=len(longterm)))

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
    # ---- titan lenses, long-term list, rotation-ahead ----
    funda = fundamentals(list(ustats.keys()))
    aaa = fr.get("AAA", {}).get("last") or 5.5
    out["titans"] = titan_lenses(ustats, funda, sectors, aaa_yield=aaa, regime_label=out["regime"]["label"])
    # rotation at sector/theme level: 6m RS bottom half and 1m RS > 0 and above 50-day
    def rot(d):
        rows = []
        for t in d:
            if t not in idx: continue
            v = idx[t]; rows.append(dict(etf=t, name=d[t], rs_1m=v["rs_1m"], rs_3m=v["rs_3m"], rs_6m=v["rs_6m"], above50=v["above50"], above200=v["above200"], pct_from_hi=v["pct_from_hi"],
                                          status="TURNING" if (v["rs_6m"] < 0 and v["rs_1m"] > 0 and v["above50"]) else "washed out, not yet" if v["rs_6m"] < 0 else "leading"))
        return sorted(rows, key=lambda r: (r["status"] == "leading", r["status"] != "TURNING", -r["rs_1m"]))
    out["rotation_sectors"] = rot({**SECTORS, **THEMES})
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
