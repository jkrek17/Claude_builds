"""Weekly TSP risk-dial backtest on ETF proxies (or tsp.gov CSV if present).

Regime score from trend / volatility / credit (breadth omitted: no constituent history),
weights fixed a priori. Decision every Friday close, executed Monday close (TSP noon rule).
Usage: python tsp_backtest.py OUT_DIR [tsp_prices.csv]
"""
import sys, io, json, math, urllib.request, datetime as dt
import numpy as np, pandas as pd, yfinance as yf

OUT = sys.argv[1] if len(sys.argv) > 1 else "."
BASELINE = {"C": 0.60, "S": 0.20, "I": 0.20}          # equity baseline
import os
_m = [float(x) for x in os.environ.get("TSP_MULT", "1.0,0.7,0.4").split(",")]
MULT = {"risk-on": _m[0], "neutral": _m[1], "risk-off": _m[2]}
MIN_CHANGE, MIN_DAYS, MAX_PER_MONTH = 0.10, 15, 2
ON, OFF, HYST = 65, 40, 5

def fred(series):
    url = f"https://fred.stlouisfed.org/graph/fredgraph.csv?id={series}"
    df = pd.read_csv(io.StringIO(urllib.request.urlopen(url, timeout=30).read().decode()), na_values=".")
    df.columns = ["date", "v"]; df["date"] = pd.to_datetime(df["date"])
    return df.dropna().set_index("date")["v"]

def load_prices():
    px = yf.download(["SPY", "VXF", "EFA", "AGG", "^VIX"], start="2001-01-01", auto_adjust=True, progress=False, group_by="ticker")
    close = pd.DataFrame({t: px[t]["Close"] for t in ["SPY", "VXF", "EFA", "AGG", "^VIX"]}).dropna()
    ten = fred("DGS10").reindex(close.index).ffill()
    g = (1 + ten / 100 / 252).cumprod()             # G fund proxy: accrues the 10-year yield, never falls
    funds = pd.DataFrame({"C": close["SPY"], "S": close["VXF"], "I": close["EFA"], "F": close["AGG"], "G": g}, index=close.index)
    return funds, close["^VIX"]

def regime(funds, vix):
    spy = funds["C"]; vxf = funds["S"]
    ma50, ma200 = spy.rolling(50).mean(), spy.rolling(200).mean()
    trend = pd.concat([np.sign(spy - ma200), np.sign(spy - ma50), np.sign(ma200 - ma200.shift(21)), np.sign(vxf - vxf.rolling(200).mean())], axis=1).mean(axis=1)
    rv = spy.pct_change().rolling(20).std() * math.sqrt(252) * 100
    vol = pd.concat([pd.Series(np.where(vix < 16, 1, np.where(vix > 25, -1, 0)), index=vix.index),
                     np.sign(vix.rolling(60).mean() - vix), np.sign(vix - rv)], axis=1).mean(axis=1)
    hy = fred("BAMLH0A0HYM2").reindex(funds.index).ffill(); curve = fred("T10Y3M").reindex(funds.index).ffill()
    credit = pd.concat([pd.Series(np.where(hy < 3.5, 1, np.where(hy > 4.5, -1, 0)), index=hy.index),
                        pd.Series(np.where(hy - hy.shift(21) > 0.5, -1, np.where(hy - hy.shift(21) < -0.25, 1, 0)), index=hy.index),
                        np.sign(curve)], axis=1).mean(axis=1)
    score = 50 + 50 * (0.4 * trend + 0.3 * vol + 0.3 * credit)
    return score.dropna(), pd.DataFrame({"trend": trend, "vol": vol, "credit": credit})

def run(funds, score, start, end):
    f = funds.loc[start:end]; s = score.reindex(f.index).ffill()
    rets = f.pct_change().fillna(0)
    label = "neutral"; labels = []
    # weekly decisions on Fridays (last trading day of the week)
    week = f.index.to_series().dt.isocalendar()
    key = week["year"].astype(str) + "-" + week["week"].astype(str)
    last_of_week = ~key.duplicated(keep="last")
    weights = {k: v for k, v in BASELINE.items()}; weights["G"] = 1 - sum(BASELINE.values()); weights["F"] = 0.0
    cur_mult = 1.0; pending = None; last_xfer = None; xfers = []; on_streak = 0
    port = [1.0]; wts_hist = []
    for i, d in enumerate(f.index):
        if i > 0:
            r = sum(weights[k] * rets.loc[d, k] for k in weights)
            port.append(port[-1] * (1 + r))
            # drift weights
            tot = sum(weights[k] * (1 + rets.loc[d, k]) for k in weights)
            weights = {k: weights[k] * (1 + rets.loc[d, k]) / tot for k in weights}
        if pending is not None:                      # execute Monday (first trading day after decision)
            weights = pending; pending = None; last_xfer = d; xfers.append(d)
        if last_of_week.loc[d] and not np.isnan(s.loc[d]):
            sc = s.loc[d]
            new = label
            if label != "risk-on" and sc >= ON + HYST: new = "risk-on"
            elif label != "risk-off" and sc < OFF - HYST: new = "risk-off"
            elif label == "risk-on" and sc < ON - HYST: new = "neutral"
            elif label == "risk-off" and sc >= OFF + HYST: new = "neutral"
            if new == "risk-on": on_streak += 1
            else: on_streak = 0
            label = new
            target_mult = MULT[label]
            if target_mult > cur_mult and label == "risk-on" and on_streak < 2: target_mult = cur_mult   # re-risk slowly
            eq_now = sum(weights[k] for k in BASELINE); eq_target = target_mult * sum(BASELINE.values())
            month_xfers = sum(1 for x in xfers if x.year == d.year and x.month == d.month)
            ok_days = last_xfer is None or (d - last_xfer).days >= MIN_DAYS
            if abs(eq_target - eq_now) >= MIN_CHANGE and ok_days and month_xfers < MAX_PER_MONTH:
                pending = {k: target_mult * BASELINE[k] for k in BASELINE}; pending["G"] = 1 - eq_target; pending["F"] = 0.0
                cur_mult = target_mult
        labels.append(label); wts_hist.append(sum(weights[k] for k in BASELINE))
    eq = pd.Series(port, index=f.index)
    return eq, pd.Series(labels, index=f.index), pd.Series(wts_hist, index=f.index), xfers

def metrics(eq):
    yrs = (eq.index[-1] - eq.index[0]).days / 365.25
    cagr = eq.iloc[-1] ** (1 / yrs) - 1
    dd = (eq / eq.cummax() - 1); mdd = dd.min()
    daily = eq.pct_change().dropna(); sharpe = daily.mean() / daily.std() * math.sqrt(252)
    worst_yr = eq.resample("YE").last().pct_change().min()
    return dict(cagr=cagr, max_dd=mdd, sharpe=sharpe, worst_year=worst_yr, total=eq.iloc[-1] - 1)

def buyhold(funds, start, end, w):
    f = funds.loc[start:end]; rets = f.pct_change().fillna(0)
    weights = dict(w); port = [1.0]
    for i, d in enumerate(f.index):
        if i == 0: continue
        r = sum(weights[k] * rets.loc[d, k] for k in weights); port.append(port[-1] * (1 + r))
        tot = sum(weights[k] * (1 + rets.loc[d, k]) for k in weights)
        weights = {k: weights[k] * (1 + rets.loc[d, k]) / tot for k in weights}
        if d.month == 1 and f.index[i - 1].month == 12: weights = dict(w)   # annual rebalance
    return pd.Series(port, index=f.index)

def main():
    funds, vix = load_prices()
    score, parts = regime(funds, vix)
    base_bh = {"C": 0.6, "S": 0.2, "I": 0.2, "G": 0.0, "F": 0.0}
    out = {"asof": str(funds.index[-1].date()), "score_now": float(score.iloc[-1]), "periods": {}}
    for name, (a, b) in {"full 2004-2026": ("2004-01-01", "2026-12-31"), "in-sample 2004-2017": ("2004-01-01", "2017-12-31"),
                         "out-of-sample 2018-2026": ("2018-01-01", "2026-12-31")}.items():
        eq, lab, eqw, xfers = run(funds, score, a, b)
        yrs = (eq.index[-1] - eq.index[0]).days / 365.25
        res = {"dial": metrics(eq), "C fund (SPY)": metrics(buyhold(funds, a, b, {"C": 1, "S": 0, "I": 0, "G": 0, "F": 0})),
               "baseline 60/20/20 buy-and-hold": metrics(buyhold(funds, a, b, base_bh)),
               "transfers_per_year": len(xfers) / yrs, "avg_equity_weight": float(eqw.mean()),
               "time_risk_on": float((lab == "risk-on").mean()), "time_risk_off": float((lab == "risk-off").mean())}
        out["periods"][name] = res
    # episodes
    eq, lab, eqw, xfers = run(funds, score, "2004-01-01", "2026-12-31")
    c = buyhold(funds, "2004-01-01", "2026-12-31", {"C": 1, "S": 0, "I": 0, "G": 0, "F": 0})
    def ep(a, b):
        e = eq.loc[a:b]; cc = c.loc[a:b]
        return dict(dial_return=float(e.iloc[-1] / e.iloc[0] - 1), dial_maxdd=float((e / e.cummax() - 1).min()),
                    c_return=float(cc.iloc[-1] / cc.iloc[0] - 1), c_maxdd=float((cc / cc.cummax() - 1).min()),
                    min_equity_weight=float(eqw.loc[a:b].min()))
    out["episodes"] = {"2008 crisis (Oct07-Mar09)": ep("2007-10-01", "2009-03-31"), "2011 debt ceiling (Jul-Oct11)": ep("2011-07-01", "2011-10-31"),
                       "2015-16 (Aug15-Feb16)": ep("2015-08-01", "2016-02-29"), "2018 Q4": ep("2018-09-20", "2018-12-31"),
                       "2020 COVID (Feb-Jun20)": ep("2020-02-19", "2020-06-30"), "2022 bear": ep("2022-01-03", "2022-12-31"),
                       "2025 tariff shock (Feb-Jun25)": ep("2025-02-19", "2025-06-30"), "2026 YTD": ep("2026-01-01", "2026-12-31")}
    out["recent"] = {"label": lab.iloc[-1], "equity_weight": float(eqw.iloc[-1]), "last_transfers": [str(x.date()) for x in xfers[-6:]]}
    tag = os.environ.get("TSP_MULT", "1.0,0.7,0.4").replace(",", "_")
    json.dump(out, open(f"{OUT}/tsp_backtest_{tag}.json", "w"), indent=1, default=str)
    for per, r in out["periods"].items():
        print(f"{tag:12} {per:26} dial CAGR {r['dial']['cagr']*100:5.1f}% DD {r['dial']['max_dd']*100:6.1f}% Sh {r['dial']['sharpe']:.2f} | C CAGR {r['C fund (SPY)']['cagr']*100:5.1f}% DD {r['C fund (SPY)']['max_dd']*100:6.1f}% Sh {r['C fund (SPY)']['sharpe']:.2f} | 60/20/20 CAGR {r['baseline 60/20/20 buy-and-hold']['cagr']*100:5.1f}% DD {r['baseline 60/20/20 buy-and-hold']['max_dd']*100:6.1f}% | xfers/yr {r['transfers_per_year']:.1f} avgEq {r['avg_equity_weight']:.2f}")

if __name__ == "__main__":
    main()
