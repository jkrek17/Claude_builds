"""Generate spy_session_ranges_strategy.pine from spy_session_ranges.pine.
Run after every change to the indicator so the twin stays identical."""
import pathlib
here = pathlib.Path(__file__).parent
src = (here / "spy_session_ranges.pine").read_text()
body = src[src.index("const string TZ"):]
hdr = '''//@version=6
// SPY Session Ranges + Setups — STRATEGY twin (backtest on SPY shares)
// Generated from spy_session_ranges.pine by build_strategy.py: identical logic, plus strategy orders.
// Tests the setups on the underlying so the tester reports win rate and expectancy per setup.
// Options P&L is NOT modeled here; see the spec §8 for the Python options overlay.
strategy("SPY Session Ranges + Setups [strategy]", shorttitle="SPY Sessions STRAT", overlay=true, initial_capital=30000, default_qty_type=strategy.fixed, default_qty_value=1, commission_type=strategy.commission.cash_per_order, commission_value=0, slippage=1, process_orders_on_close=true, calc_on_every_tick=false, max_lines_count=500, max_labels_count=200, max_boxes_count=100)

'''
orders = '''
// ───────────────────────────── Strategy execution ─────────────────────────────
// Entry at the close of the trigger bar (process_orders_on_close). Exits are resting
// limit/stop orders so fills are intrabar like a real bracket. Shares sized so that
// one full stop-out loses riskDollars, which keeps the tester's P&L in R units.
if not na(tr)
    if aLongTrig or aShortTrig or bLongTrig or bShortTrig
        sharesQty = math.max(1, math.floor(riskDollars / tr.rDist))
        oid = tr.dir == 1 ? "L" : "S"
        strategy.entry(oid, tr.dir == 1 ? strategy.long : strategy.short, qty=sharesQty, comment=tr.setup + (tr.dir == 1 ? " long" : " short"))
        strategy.exit(oid + "-T1", oid, qty_percent=50, limit=tr.t1, stop=tr.stop, comment_profit="T1", comment_loss="stop")
        strategy.exit(oid + "-T2", oid, limit=tr.t2, stop=tr.stop, comment_profit="T2", comment_loss="stop")
    if t1Event and tr.status == "T1"
        oid = tr.dir == 1 ? "L" : "S"
        strategy.exit(oid + "-T2", oid, limit=tr.t2, stop=tr.entry, comment_profit="T2", comment_loss="BE")
    if timeEvent and strategy.position_size != 0
        strategy.close_all(comment="time")
'''
marker = "// ───────────────────────────── Alerts ─────────────────────────────"
assert marker in body
body = body.replace(marker, orders + "\n" + marker)
(here / "spy_session_ranges_strategy.pine").write_text(hdr + body)
print("strategy lines:", len((hdr + body).splitlines()))
