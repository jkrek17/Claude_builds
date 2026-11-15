"""Test alternative settings against the current model, and propose a change only on strong evidence.

The candidates are fixed in advance (challengers.json): each is the current settings with one value moved
one step along a ladder. Because the list never changes from week to week, looking every week adds no new
guesses, only repeated looks at the same ones.

A candidate is proposed only when, on every game since 2010 predicted walk-forward, its squared error beats
the current model's with a season-clustered one-sided p of 0.0025 or less, the gain is at least 0.1% of the
current error, it is better in at least 65% of full seasons, in both halves of the window and over the
latest three seasons, and all of that held at the three previous weekly looks too. Replayed over 2014 to
2025 with no real effect present, this rule made a false change in about 4% of seasons; "whichever is
ahead" did so in every season. Records against the spread, profit and single weeks play no part.

A proposal is a pull request for a person to read. Nothing here changes the model by itself.
"""
from __future__ import annotations

import datetime as dt
import json
import math
from importlib import resources
from pathlib import Path

import numpy as np
import pandas as pd

from . import margin_model, params, qb_form, totals_model

FIRST_SEASON = 2010      # settings were tuned on earlier seasons
FULL_SEASON = 100        # a season counts as full once it has this many graded games
ALPHA = 0.0025           # one-sided p needed at each look
MIN_REL = 0.001          # smallest gain worth a change, as a share of the current squared error
MIN_SEASON_SHARE = 0.65  # share of full seasons in which the candidate must be better
LOOKS = 4                # consecutive weekly looks that must all pass
ROLLBACK_T = 2.0         # a promoted setting is reverted when its t against the old one falls below this
WEIGHT_CAPS = {"margin_weight": 0.10, "total_weight": 0.08}  # the loop never changes or raises these
TARGET = {"margin": ("pred_margin", "result"), "totals": ("pred_total", "total")}


def load_ladders(path: str | None = None) -> dict:
    if path:
        return json.loads(Path(path).read_text())
    return json.loads(resources.files("nflmodel").joinpath("challengers.json").read_text())


def _get(settings: dict, dotted: str):
    for key in dotted.split("."):
        settings = settings[key]
    return settings


def neighbours(settings: dict, ladder: dict) -> list[tuple[str, float]]:
    """(dotted setting, value) one rung either side of each laddered setting's current value."""
    out = []
    for name, rungs in ladder.items():
        now = _get(settings, name)
        i = min(range(len(rungs)), key=lambda j: abs(rungs[j] - now))
        out += [(name, rungs[j]) for j in (i - 1, i + 1) if 0 <= j < len(rungs)]
    return out


# ---------------------------------------------------------------- statistics

def _t_tail(c: float, df: int) -> float:
    """P(T > c) for Student's t, by Simpson's rule (no scipy in the runtime)."""
    const = math.exp(math.lgamma((df + 1) / 2) - math.lgamma(df / 2)) / math.sqrt(df * math.pi)
    xs = np.linspace(0.0, c, 4001)
    ys = const * (1 + xs ** 2 / df) ** (-(df + 1) / 2)
    h = xs[1] - xs[0]
    area = h / 3 * (ys[0] + ys[-1] + 4 * ys[1:-1:2].sum() + 2 * ys[2:-1:2].sum())
    return 0.5 - float(area)


def t_crit(df: int, alpha: float = ALPHA) -> float:
    lo, hi = 0.0, 100.0
    for _ in range(60):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if _t_tail(mid, df) > alpha else (lo, mid)
    return hi


def look(diff: np.ndarray, season: np.ndarray, champ_sq: np.ndarray) -> dict:
    """The promotion statistics for one look. diff = current squared error minus candidate's, per game."""
    seasons = np.unique(season)
    s_sum = np.array([diff[season == s].sum() for s in seasons])
    n_s = np.array([(season == s).sum() for s in seasons])
    n, g = n_s.sum(), len(seasons)
    mean = s_sum.sum() / n
    se = math.sqrt(g / (g - 1) * ((s_sum - mean * n_s) ** 2).sum()) / n if g > 1 else float("nan")
    t = mean / se if se and se > 0 else 0.0
    full = n_s >= FULL_SEASON
    gf = int(full.sum())
    full_sums = s_sum[full]
    half = gf // 2
    out = {
        "t": t, "mean": mean, "rel": mean / champ_sq.mean(), "full_seasons": gf,
        "crit": t_crit(gf - 1) if gf >= 2 else float("inf"),
        "season_share": float((full_sums > 0).mean()) if gf else 0.0,
        "first_half": float(full_sums[:half].sum()), "second_half": float(full_sums[half:].sum()),
        "recent": float(s_sum[-3:].sum()),
    }
    out["pass"] = bool(out["t"] >= out["crit"] and out["rel"] >= MIN_REL
                       and out["season_share"] >= MIN_SEASON_SHARE - 1e-9
                       and out["first_half"] > 0 and out["second_half"] > 0 and out["recent"] > 0)
    return out


# ---------------------------------------------------------------- running the models

class Runner:
    """Predicts with any settings, on the graded games of the evaluation window."""

    def __init__(self, games: pd.DataFrame, data_dir: str):
        self.games = games
        self.team = margin_model.load_team_stats(data_dir)
        self.players = qb_form.load_player_stats(data_dir)
        self.team_totals = totals_model.load_team_stats(data_dir)
        done = games[(games["season"] >= FIRST_SEASON) & games["result"].notna()]
        self.window = done.sort_values(["gameday", "game_id"])[["game_id", "season", "week", "result", "total"]]

    def errors(self, model: str, settings: dict) -> np.ndarray:
        """Squared error per window game."""
        if model == "margin":
            pred = margin_model.predict_margins(self.games, self.team, self.players, settings)
        else:
            pred = totals_model.predict_totals(self.games, self.team_totals, params=settings)
        col, actual = TARGET[model]
        merged = self.window.merge(pred[["game_id", col]], on="game_id", how="left")
        return ((merged[actual] - merged[col]) ** 2).to_numpy(float)


def shrink_estimates(preds: pd.DataFrame) -> dict:
    """How much of the model-versus-line gap has survived, for the report. The loop never acts on it."""
    done = preds[(preds["season"] >= FIRST_SEASON) & preds["result"].notna()]
    out = {}
    for name, pred, line, actual in (("margin_weight", "pred_margin", "spread_line", "result"),
                                     ("total_weight", "pred_total", "total_line", "total")):
        rows = done.dropna(subset=[pred, line])
        for label, part in (("all", rows), ("since_2018", rows[rows["season"] >= 2018])):
            gap = part[pred] - part[line]
            out[f"{name}_{label}"] = float((gap * (part[actual] - part[line])).sum() / (gap ** 2).sum()) if len(part) else None
    return out


def evaluate(runner: Runner, settings: dict, ladders: dict) -> dict:
    """Every candidate's statistics at the latest look, and how many consecutive looks it has passed."""
    win = runner.window
    season = win["season"].to_numpy()
    keys = list(dict.fromkeys(zip(win["season"], win["week"])))   # looks, in time order
    order = {k: i for i, k in enumerate(keys)}
    idx = np.array([order[k] for k in zip(win["season"], win["week"])])
    last = len(keys) - 1
    out = {"season": int(keys[-1][0]), "week": int(keys[-1][1]), "games": len(win), "candidates": []}
    for model, ladder in ladders.items():
        champ = runner.errors(model, settings)
        keep = ~np.isnan(champ)
        for name, value in neighbours(settings, ladder):
            cand = runner.errors(model, params.override(settings, {name: value}))
            ok = keep & ~np.isnan(cand)
            diff = champ - cand
            streak, stats = 0, None
            for back in range(LOOKS):
                use = ok & (idx <= last - back)
                st = look(diff[use], season[use], champ[use])
                stats = stats or st
                if not st["pass"]:
                    break
                streak += 1
            out["candidates"].append({"model": model, "setting": name, "from": _get(settings, name), "to": value,
                                      "streak": streak, "eligible": streak >= LOOKS,
                                      "rmse_gain": float(math.sqrt(champ[ok].mean()) - math.sqrt(cand[ok].mean())),
                                      **stats})
    return out


# ---------------------------------------------------------------- deciding

def _history(state_dir: Path) -> dict:
    path = Path(state_dir) / "promotions.json"
    return json.loads(path.read_text()) if path.exists() else {"history": [], "frozen": {}}


def decide(state_dir: Path, evaluation: dict, settings: dict, runner: Runner | None = None) -> dict | None:
    """Pick at most one change to propose (a rollback first, else the strongest eligible candidate).

    Updates promotions.json and returns the proposal, or None."""
    state_dir = Path(state_dir)
    book = _history(state_dir)
    season = evaluation["season"]
    proposal = None
    for entry in book["history"]:
        live = _get(settings, entry["setting"]) == entry["to"]
        if entry["status"] == "proposed" and live:
            entry["status"] = "merged"
        if entry["status"] == "rolled_back" and _get(settings, entry["setting"]) == entry["from"]:
            entry["status"] = "reverted"
        if entry["status"] == "merged" and runner is not None and live:
            old = runner.errors(entry["model"], params.override(settings, {entry["setting"]: entry["from"]}))
            new = runner.errors(entry["model"], settings)
            ok = ~np.isnan(old) & ~np.isnan(new)
            st = look((old - new)[ok], runner.window["season"].to_numpy()[ok], old[ok])
            entry["t_now"] = st["t"]
            if st["t"] < ROLLBACK_T:
                entry["status"] = "rolled_back"
                book["frozen"][entry["setting"]] = season + 1
                proposal = {"kind": "rollback", "model": entry["model"], "setting": entry["setting"],
                            "from": entry["to"], "to": entry["from"], "t": st["t"]}
    pending = any(e["status"] == "rolled_back" for e in book["history"])
    if proposal is None and not pending:
        used = {e["model"] for e in book["history"] if e["season"] == season and e["status"] != "withdrawn"}
        open_now = {(e["model"], e["setting"], e["to"]) for e in book["history"] if e["status"] == "proposed"}
        best = None
        for c in evaluation["candidates"]:
            key = (c["model"], c["setting"], c["to"])
            if not c["eligible"] or book["frozen"].get(c["setting"], 0) >= season:
                continue
            if c["model"] in used and key not in open_now:
                continue
            if best is None or c["t"] > best["t"]:
                best = c
        if best is not None:
            proposal = {"kind": "promotion", **best}
            if (best["model"], best["setting"], best["to"]) not in open_now:
                book["history"].append({"model": best["model"], "setting": best["setting"], "from": best["from"],
                                        "to": best["to"], "season": season, "week": evaluation["week"],
                                        "t": best["t"], "status": "proposed"})
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / "promotions.json").write_text(json.dumps(book, indent=1, sort_keys=True) + "\n")
    return proposal


def pr_body(proposal: dict, evaluation: dict) -> str:
    name, old, new = proposal["setting"], proposal["from"], proposal["to"]
    if proposal["kind"] == "rollback":
        return "\n".join([
            f"## Revert `{name}` from {old:g} back to {new:g}", "",
            "The weekly loop proposed this setting earlier and it was merged. On the games played since, its "
            f"advantage over the old value has faded: the test statistic is now {proposal['t']:.2f}, below the "
            f"{ROLLBACK_T:g} needed to keep it. This setting will not be proposed again until after next season.", "",
            "Merging is your decision; nothing changes until you merge.", ""])
    passed = sum(c["eligible"] for c in evaluation["candidates"])
    return "\n".join([
        f"## Change `{name}` from {old:g} to {new:g}", "",
        "Proposed by the weekly loop. It changes one setting of the "
        f"{'margin' if proposal['model'] == 'margin' else 'totals'} model in `src/nflmodel/params.json`.", "",
        "### Evidence", "",
        f"- Games compared: {evaluation['games']} (every game since {FIRST_SEASON}, each predicted using only earlier games)",
        f"- Test statistic: {proposal['t']:.2f} (needed {proposal['crit']:.2f}, a one-sided p of {ALPHA})",
        f"- Squared error: {proposal['rel']:.3%} lower (needed {MIN_REL:.1%}); about {proposal['rmse_gain']:.3f} points of typical error",
        f"- Better in {proposal['season_share']:.0%} of full seasons (needed {MIN_SEASON_SHARE:.0%}), in both halves of "
        "the window, and over the latest three seasons",
        f"- Passed all of the above at {LOOKS} weekly looks in a row",
        f"- Candidates tested this week: {len(evaluation['candidates'])}; passing: {passed}", "",
        "### What this does not mean", "",
        "This is a small gain in forecast accuracy. It is not evidence of a betting edge: the model's record "
        "against the spread played no part in the test, and the closing line remains the better forecast. "
        "With no real effect present, this rule still proposes a change in roughly 4% of seasons.", "",
        "### After merging", "",
        f"The loop keeps checking the new value against the old one and will propose a revert if the test "
        f"statistic falls below {ROLLBACK_T:g}. Merging is your decision; nothing changes until you merge.", ""])


def run(state_dir: Path, games: pd.DataFrame, preds: pd.DataFrame, data_dir: str, now: dt.datetime,
        settings: dict | None = None, ladders: dict | None = None) -> dict:
    """Evaluate once per graded week. Writes challengers/, promotions.json and, on a proposal, proposals/current/."""
    state_dir = Path(state_dir)
    settings = settings or params.DEFAULT
    runner = Runner(games, data_dir)
    if runner.window.empty:
        return {"promote": False, "skipped": "no graded games"}
    last = runner.window.iloc[-1]
    out_path = state_dir / "challengers" / f"{int(last['season'])}_w{int(last['week']):02d}_{params.digest(settings)}.json"
    current = state_dir / "proposals" / "current"
    if out_path.exists():   # this week was already evaluated with these settings
        return {"promote": (current / "params.json").exists() and
                json.loads((current / "params.json").read_text()) != json.loads(params.dumps(settings)),
                "skipped": "already evaluated", **json.loads(out_path.read_text())}
    evaluation = evaluate(runner, settings, ladders or load_ladders())
    evaluation["evaluated_at"] = now.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    evaluation["shrink"] = shrink_estimates(preds)
    evaluation["weights"] = dict(settings["card"])
    proposal = decide(state_dir, evaluation, settings, runner)
    evaluation["proposal"] = proposal
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(evaluation, indent=1, sort_keys=True) + "\n")
    if proposal:
        current.mkdir(parents=True, exist_ok=True)
        (current / "params.json").write_text(params.dumps(params.override(settings, {proposal["setting"]: proposal["to"]})))
        (current / "PR_BODY.md").write_text(pr_body(proposal, evaluation))
    elif current.exists():
        for f in current.iterdir():
            f.unlink()
        current.rmdir()
    return {"promote": proposal is not None, **evaluation}


def summary_line(result: dict) -> str:
    """One plain sentence for the report."""
    if "candidates" not in result:
        return "No alternative settings were tested this run."
    n = len(result["candidates"])
    ahead = sum(c["mean"] > 0 for c in result["candidates"])
    passing = sum(c["pass"] for c in result["candidates"])
    prop = result.get("proposal")
    text = (f"{n} alternative settings were tested against the current model on {result['games']} games. "
            f"{ahead} were ahead by any amount, which is what chance alone produces; {passing} passed the "
            "strict test at this look.")
    if prop and prop["kind"] == "promotion":
        text += (f" One has passed {LOOKS} weeks running, so a pull request proposes changing `{prop['setting']}` "
                 f"from {prop['from']:g} to {prop['to']:g}. It is a small accuracy gain, not a betting edge, and "
                 "nothing changes unless the pull request is merged.")
    elif prop:
        text += f" A pull request proposes reverting `{prop['setting']}` to {prop['to']:g} because its advantage faded."
    else:
        text += " No change is proposed."
    return text
