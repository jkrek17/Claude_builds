"""Weekly TSP dial: compute the tested regime score, update hysteresis state, and emit an instruction.
Usage: python tsp_dial.py OUT_DIR   (reads ../config/tsp.json and ../state/tsp_state.json, writes OUT_DIR/tsp.json)
"""
import sys, json, pathlib, datetime as dt
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from tsp_backtest import load_prices, regime

OUT = sys.argv[1] if len(sys.argv) > 1 else "."
ROOT = pathlib.Path(__file__).resolve().parent.parent
cfg = json.load(open(ROOT / "config" / "tsp.json"))
state_path = ROOT / "state" / "tsp_state.json"
state = json.load(open(state_path))

funds, vix = load_prices()
score, parts = regime(funds, vix)
sc = float(score.iloc[-1]); asof = str(score.index[-1].date())
th = cfg["thresholds"]; ON, OFF, H = th["on"], th["off"], th["hysteresis"]
label = state["label"]; new = label
if label != "risk-on" and sc >= ON: new = "risk-on"
elif label != "risk-off" and sc < OFF: new = "risk-off"
elif label == "risk-on" and sc < ON - H: new = "neutral"
elif label == "risk-off" and sc >= OFF + H: new = "neutral"
state["on_streak"] = state.get("on_streak", 0) + 1 if new == "risk-on" else 0
state["label"] = new

base = cfg["baseline"]; mult = cfg["multipliers"][new]
cur = cfg["current_allocation"]; eq_now = sum(cur.get(k, 0) for k in base)
cur_mult = eq_now / sum(base.values()) if sum(base.values()) else 1.0
target_mult = mult
if target_mult > cur_mult and new == "risk-on" and state["on_streak"] < 2: target_mult = cur_mult
eq_target = target_mult * sum(base.values())
today = dt.date.today()
month_xfers = sum(1 for x in state["transfers"] if x[:7] == today.strftime("%Y-%m"))
days_ok = state["last_transfer"] is None or (today - dt.date.fromisoformat(state["last_transfer"])).days >= cfg["min_days_between"]
move = abs(eq_target - eq_now) >= cfg["min_change"] and days_ok and month_xfers < cfg["max_per_month"]
target = {k: round(target_mult * base[k], 3) for k in base}; target["G"] = round(1 - eq_target, 3); target["F"] = 0.0
instr = None
if move:
    lines = [f"{k}: {cur.get(k, 0)*100:.0f}% → {target[k]*100:.0f}%" for k in ["C", "S", "I", "F", "G"]]
    instr = (f"Interfund transfer: " + ", ".join(lines) + f". Submit on tsp.gov before 12:00 ET Monday to get Monday's close. "
             f"This uses transfer {month_xfers + 1} of {cfg['max_per_month']} for the month.")
out = dict(asof=asof, score=round(sc, 1), parts={k: round(float(parts[k].iloc[-1]), 2) for k in parts}, label=new,
           previous_label=label, equity_now=round(eq_now, 3), equity_target=round(eq_target, 3), target=target,
           move=move, instruction=instr, blocked_by=None if move or abs(eq_target - eq_now) < cfg["min_change"] else
           ("15-day spacing" if not days_ok else "monthly transfer limit"))
state["history"] = (state.get("history", []) + [dict(date=asof, score=round(sc, 1), label=new)])[-104:]
json.dump(state, open(state_path, "w"), indent=1)
json.dump(out, open(f"{OUT}/tsp.json", "w"), indent=1)
print(json.dumps(out, indent=1))
