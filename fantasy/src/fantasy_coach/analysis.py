"""Pure fantasy football analysis: projections, lineups, waivers, trades and league history.

Nothing in this module talks to Yahoo. ``fantasy_coach.yahoo`` turns API responses into the
dataclasses below, and ``fantasy_coach.server`` exposes the results as MCP tools. Keeping the math
here means it can be unit tested with plain fixtures.

Projections are deliberately simple and transparent, because Yahoo's API does not expose its own
projections: a player's expected weekly points are a blend of recent form and season average in
*your league's scoring*, with a fallback to last season early in the year, then discounted for
injury status. Every recommendation carries the numbers it was based on so Claude (and you) can
judge it.
"""

from __future__ import annotations

import itertools
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from statistics import mean
from typing import Callable, Iterable

# Slots that never score.
BENCH_SLOTS = {"BN"}
INJURED_SLOTS = {"IR", "IR+", "IL", "NA"}
NON_STARTING_SLOTS = BENCH_SLOTS | INJURED_SLOTS

# Letters used in Yahoo flex slot names such as "W/R/T" or "Q/W/R/T".
FLEX_CODES = {"Q": "QB", "W": "WR", "R": "RB", "T": "TE"}
# IDP slots that accept several specific positions.
IDP_GROUPS = {
    "D": {"DL", "LB", "DB", "DE", "DT", "CB", "S"},
    "DL": {"DL", "DE", "DT"},
    "DB": {"DB", "CB", "S"},
}

# How much of a player's projection survives each injury designation.
# WEEK applies to "this week's lineup"; ROS (rest of season) applies to waiver and trade value,
# where a player who is out now still has value later.
_HEALTH_WEEK = {"": 1.0, "Q": 0.85, "D": 0.4, "O": 0.0, "IR": 0.0, "PUP": 0.0, "SUSP": 0.0, "NA": 0.0}
_HEALTH_ROS = {"": 1.0, "Q": 0.95, "D": 0.85, "O": 0.75, "IR": 0.35, "PUP": 0.35, "SUSP": 0.5, "NA": 0.0}

RECENT_WEEKS = 3
MIN_GAMES_FOR_FULL_SAMPLE = 3
RECENT_WEIGHT = 0.6
DEPTH_WEIGHT = 0.15


def health_bucket(status: str | None) -> str:
    """Collapse Yahoo's injury codes (IR-R, PUP-P, NFI-R, ...) into the keys above."""
    s = (status or "").upper().strip()
    if not s:
        return ""
    if s.startswith("IR"):
        return "IR"
    if s.startswith(("PUP", "NFI")):
        return "PUP"
    if s.startswith("SUSP"):
        return "SUSP"
    if s in ("O", "OUT"):
        return "O"
    if s in ("D", "Q", "NA"):
        return s
    return ""


@dataclass
class Player:
    player_id: int
    name: str
    positions: list[str]
    nfl_team: str = ""
    status: str = ""
    selected_position: str | None = None
    percent_owned: float | None = None
    bye_week: int | None = None
    # Fantasy points by week for the current season, in this league's scoring.
    weekly_points: dict[int, float] = field(default_factory=dict)
    # Last season's fantasy points per game, used when this season's sample is small.
    prior_ppg: float | None = None
    on_waivers: bool = False

    @property
    def is_starting(self) -> bool:
        return self.selected_position is not None and self.selected_position not in NON_STARTING_SLOTS

    @property
    def in_injured_slot(self) -> bool:
        return self.selected_position in INJURED_SLOTS


@dataclass
class Projection:
    points: float  # expected points in a healthy week
    season_ppg: float | None
    recent_ppg: float | None
    games: int

    def as_dict(self) -> dict:
        return {
            "proj_points": round(self.points, 2),
            "season_ppg": None if self.season_ppg is None else round(self.season_ppg, 2),
            "recent_ppg": None if self.recent_ppg is None else round(self.recent_ppg, 2),
            "games": self.games,
        }


def project(player: Player, before_week: int) -> Projection:
    """Expected healthy-week points from weeks before ``before_week``.

    Weeks with exactly 0 points, or the bye week, are treated as not played. That miscounts a
    player who really scored 0, but Yahoo's weekly stats don't say whether a player was active,
    and a missed game is much more common than a true zero.
    """
    played = sorted(
        (wk, pts)
        for wk, pts in player.weekly_points.items()
        if wk < before_week and wk != player.bye_week and pts != 0
    )
    games = len(played)
    season = mean(p for _, p in played) if played else None
    recent = mean(p for _, p in played[-RECENT_WEEKS:]) if played else None

    if games >= MIN_GAMES_FOR_FULL_SAMPLE:
        pts = RECENT_WEIGHT * recent + (1 - RECENT_WEIGHT) * season
    elif games > 0:
        pts = season
        if player.prior_ppg is not None:
            w = games / MIN_GAMES_FOR_FULL_SAMPLE
            pts = w * season + (1 - w) * player.prior_ppg
    else:
        pts = player.prior_ppg or 0.0
    return Projection(points=max(pts, 0.0), season_ppg=season, recent_ppg=recent, games=games)


def week_value(player: Player, proj: Projection, week: int) -> float:
    if player.bye_week == week:
        return 0.0
    return proj.points * _HEALTH_WEEK[health_bucket(player.status)]


def ros_value(player: Player, proj: Projection) -> float:
    return proj.points * _HEALTH_ROS[health_bucket(player.status)]


# --------------------------------------------------------------------------------------------
# Lineups
# --------------------------------------------------------------------------------------------


def slot_accepts(slot: str) -> set[str]:
    """Positions that may fill ``slot``."""
    if slot in IDP_GROUPS:
        return IDP_GROUPS[slot] | {slot}
    if "/" in slot:
        return {FLEX_CODES.get(code, code) for code in slot.split("/")} | {slot}
    return {slot}


def eligible(player: Player, slot: str) -> bool:
    return bool(slot_accepts(slot) & set(player.positions))


def starting_slots(slots: dict[str, int]) -> list[str]:
    """Expand {"WR": 3, "W/R/T": 1, "BN": 6, ...} into the ordered list of slots to fill.

    Single-position slots go first and flex slots after, narrowest flex first. Filling dedicated
    slots with the best player at that position, then flexes from what's left, is optimal for
    standard Yahoo layouts because every flex accepts a superset of some dedicated slot.
    """
    expanded = [s for s, n in slots.items() if s not in NON_STARTING_SLOTS for _ in range(n)]
    return sorted(expanded, key=lambda s: (len(slot_accepts(s)) > 1, len(slot_accepts(s))))


@dataclass
class Lineup:
    starters: list[tuple[str, Player | None, float]]  # (slot, player or None if empty, value)
    bench: list[tuple[Player, float]]

    @property
    def total(self) -> float:
        return sum(v for _, _, v in self.starters)

    def starter_ids(self) -> set[int]:
        return {p.player_id for _, p, _ in self.starters if p is not None}

    def as_dict(self) -> dict:
        return {
            "total": round(self.total, 2),
            "starters": [
                {"slot": s, "player": p.name if p else None, "player_id": p.player_id if p else None, "value": round(v, 2)}
                for s, p, v in self.starters
            ],
            "bench": [{"player": p.name, "player_id": p.player_id, "value": round(v, 2)} for p, v in self.bench],
        }


def optimal_lineup(players: Iterable[Player], slots: dict[str, int], value: Callable[[Player], float]) -> Lineup:
    pool = [p for p in players if not p.in_injured_slot]
    vals = {p.player_id: value(p) for p in pool}
    pool.sort(key=lambda p: vals[p.player_id], reverse=True)
    used: set[int] = set()
    starters: list[tuple[str, Player | None, float]] = []
    for slot in starting_slots(slots):
        pick = next((p for p in pool if p.player_id not in used and eligible(p, slot)), None)
        if pick is not None:
            used.add(pick.player_id)
        starters.append((slot, pick, vals[pick.player_id] if pick else 0.0))
    bench = [(p, vals[p.player_id]) for p in pool if p.player_id not in used]
    return Lineup(starters=starters, bench=bench)


def team_strength(players: list[Player], slots: dict[str, int], value: Callable[[Player], float]) -> tuple[float, Lineup]:
    """Starting lineup total plus a small credit for bench depth (byes and injuries happen).

    Depth only counts as many bench players as the league has bench slots.
    """
    lineup = optimal_lineup(players, slots, value)
    bench_slots = slots.get("BN", 0)
    depth = sorted((v for _, v in lineup.bench), reverse=True)[:bench_slots]
    return lineup.total + DEPTH_WEIGHT * sum(depth), lineup


# --------------------------------------------------------------------------------------------
# This week's lineup
# --------------------------------------------------------------------------------------------


def lineup_advice(roster: list[Player], slots: dict[str, int], week: int) -> dict:
    projs = {p.player_id: project(p, week) for p in roster}

    def val(p: Player) -> float:
        return week_value(p, projs[p.player_id], week)

    best = optimal_lineup(roster, slots, val)
    current_starters = [p for p in roster if p.is_starting]
    current_total = sum(val(p) for p in current_starters)
    best_ids = best.starter_ids()
    current_ids = {p.player_id for p in current_starters}

    start = [p for p in roster if p.player_id in best_ids - current_ids]
    sit = [p for p in current_starters if p.player_id not in best_ids]

    alerts = []
    for p in current_starters:
        if p.bye_week == week:
            alerts.append(f"{p.name} is starting at {p.selected_position} but is on bye in week {week}.")
        bucket = health_bucket(p.status)
        if bucket in ("O", "IR", "PUP", "SUSP", "NA"):
            alerts.append(f"{p.name} is starting at {p.selected_position} but is listed {p.status}.")
        elif bucket in ("Q", "D"):
            alerts.append(f"{p.name} is starting at {p.selected_position} and is listed {p.status}; check the final injury report.")
    filled = Counter(p.selected_position for p in current_starters)
    for slot, needed in Counter(starting_slots(slots)).items():
        if filled[slot] < needed:
            alerts.append(f"{needed - filled[slot]} empty starting spot(s) at {slot}.")
    ir_capacity = sum(n for s, n in slots.items() if s in INJURED_SLOTS)
    ir_used = sum(1 for p in roster if p.in_injured_slot)
    if ir_used < ir_capacity:
        for p in roster:
            if not p.in_injured_slot and health_bucket(p.status) in ("IR", "PUP", "O"):
                alerts.append(f"{p.name} ({p.status}) could move to an open IR slot to free a bench spot.")

    def describe(p: Player) -> dict:
        return {"player": p.name, "player_id": p.player_id, "positions": p.positions, "status": p.status,
                "bye_week": p.bye_week, "week_value": round(val(p), 2), **projs[p.player_id].as_dict()}

    return {
        "week": week,
        "current_projected_total": round(current_total, 2),
        "optimal_projected_total": round(best.total, 2),
        "projected_gain": round(best.total - current_total, 2),
        "start": [describe(p) for p in start],
        "sit": [describe(p) for p in sit],
        "optimal_lineup": best.as_dict(),
        "alerts": alerts,
    }


# --------------------------------------------------------------------------------------------
# Waivers
# --------------------------------------------------------------------------------------------


def waiver_recommendations(roster: list[Player], candidates: list[Player], slots: dict[str, int],
                           week: int, top_n: int = 10) -> dict:
    """Rank add/drop pairs by how much they raise rest-of-season team strength.

    For every available player, try dropping each droppable roster player (not those parked in
    an IR slot) and keep the drop that leaves the strongest team.
    """
    projs = {p.player_id: project(p, week) for p in [*roster, *candidates]}

    def val(p: Player) -> float:
        return ros_value(p, projs[p.player_id])

    base, base_lineup = team_strength(roster, slots, val)
    rostered = {p.player_id for p in roster}
    droppable = [p for p in roster if not p.in_injured_slot]
    max_active = sum(n for s, n in slots.items() if s not in INJURED_SLOTS)
    # An open roster spot means an add needs no drop.
    drop_options: list[Player | None] = [*droppable, None] if len(droppable) < max_active else list(droppable)

    recs = []
    for add in candidates:
        if add.player_id in rostered or val(add) <= 0:
            continue
        best = None
        for drop in drop_options:
            new_roster = [p for p in roster if drop is None or p.player_id != drop.player_id] + [add]
            strength, lineup = team_strength(new_roster, slots, val)
            if best is None or strength > best[0]:
                best = (strength, drop, lineup)
        if best is None:
            continue
        strength, drop, lineup = best
        gain = strength - base
        if gain <= 0.05:
            continue
        add_slot = next((s for s, p, _ in lineup.starters if p and p.player_id == add.player_id), None)
        drop_info = {"drop": None} if drop is None else {
            "drop": drop.name, "drop_id": drop.player_id, "drop_positions": drop.positions, "drop_status": drop.status,
            **{f"drop_{k}": v for k, v in projs[drop.player_id].as_dict().items()},
        }
        recs.append({
            "add": add.name, "add_id": add.player_id, "add_positions": add.positions, "add_team": add.nfl_team,
            "add_status": add.status, "add_percent_owned": add.percent_owned,
            "source": "waivers" if add.on_waivers else "free agent",
            **{f"add_{k}": v for k, v in projs[add.player_id].as_dict().items()},
            **drop_info,
            "would_start_at": add_slot,
            "lineup_gain": round(lineup.total - base_lineup.total, 2),
            "team_strength_gain": round(gain, 2),
        })
    recs.sort(key=lambda r: r["team_strength_gain"], reverse=True)
    return {
        "week": week,
        "team_strength": round(base, 2),
        "lineup_total": round(base_lineup.total, 2),
        "candidates_considered": len(candidates),
        "recommendations": recs[:top_n],
        "method": ("Value = projected points per healthy week in your league's scoring (60% last 3 games, "
                   "40% season average; blended with last season early on), discounted for injury status. "
                   "Team strength = best starting lineup + 15% of bench depth."),
    }


# --------------------------------------------------------------------------------------------
# Trades
# --------------------------------------------------------------------------------------------


def _trim_roster(players: list[Player], max_size: int, val: Callable[[Player], float]) -> tuple[list[Player], list[Player]]:
    """Drop the lowest-value non-IR players until the roster fits."""
    active = [p for p in players if not p.in_injured_slot]
    over = len(active) - max_size
    if over <= 0:
        return players, []
    drops = sorted(active, key=val)[:over]
    ids = {p.player_id for p in drops}
    return [p for p in players if p.player_id not in ids], drops


def evaluate_trade(my_roster: list[Player], their_roster: list[Player], give: list[Player], get: list[Player],
                   slots: dict[str, int], week: int, projs: dict[int, Projection] | None = None) -> dict:
    if projs is None:
        projs = {p.player_id: project(p, week) for p in [*my_roster, *their_roster]}

    def val(p: Player) -> float:
        return ros_value(p, projs[p.player_id])

    give_ids = {p.player_id for p in give}
    get_ids = {p.player_id for p in get}
    max_active = sum(n for s, n in slots.items() if s not in INJURED_SLOTS)

    def side(roster: list[Player], out_ids: set[int], incoming: list[Player]) -> dict:
        before, before_lineup = team_strength(roster, slots, val)
        after_roster = [p for p in roster if p.player_id not in out_ids] + [
            Player(**{**p.__dict__, "selected_position": "BN"}) for p in incoming
        ]
        after_roster, forced_drops = _trim_roster(after_roster, max_active, val)
        after, after_lineup = team_strength(after_roster, slots, val)
        return {
            "strength_before": round(before, 2), "strength_after": round(after, 2),
            "strength_change": round(after - before, 2),
            "lineup_before": round(before_lineup.total, 2), "lineup_after": round(after_lineup.total, 2),
            "lineup_change": round(after_lineup.total - before_lineup.total, 2),
            "would_need_to_drop": [p.name for p in forced_drops],
            "new_starters": [p.name for s, p, _ in after_lineup.starters if p and p.player_id in {x.player_id for x in incoming}],
        }

    me = side(my_roster, give_ids, get)
    them = side(their_roster, get_ids, give)
    give_value = sum(val(p) for p in give)
    get_value = sum(val(p) for p in get)

    mine, theirs = me["strength_change"], them["strength_change"]
    if mine > 0 and theirs > 0:
        verdict = "win-win: both lineups improve, so this is a realistic offer"
    elif mine > 0:
        verdict = "good for you, worse for them: expect resistance unless they value it differently"
    elif theirs > 0:
        verdict = "good for them, worse for you: decline or ask for more"
    else:
        verdict = "neither team clearly improves"

    def describe(p: Player) -> dict:
        return {"player": p.name, "player_id": p.player_id, "positions": p.positions, "status": p.status,
                "ros_value": round(val(p), 2), **projs[p.player_id].as_dict()}

    return {
        "you_give": [describe(p) for p in give], "you_get": [describe(p) for p in get],
        "raw_value_given": round(give_value, 2), "raw_value_received": round(get_value, 2),
        "you": me, "them": them, "verdict": verdict,
    }


def find_trades(my_roster: list[Player], others: dict[str, tuple[str, list[Player]]], slots: dict[str, int],
                week: int, max_per_side: int = 2, top_n: int = 10, pool_size: int = 10) -> list[dict]:
    """Search 1-for-1 and (optionally) 2-for-1 / 1-for-2 swaps that help both teams.

    ``others`` maps team_key -> (team name, roster). Only each team's ``pool_size`` most valuable
    non-IR players are considered on each side, which keeps the search fast and realistic.
    """
    everyone = [*my_roster, *(p for _, r in others.values() for p in r)]
    projs = {p.player_id: project(p, week) for p in everyone}

    def val(p: Player) -> float:
        return ros_value(p, projs[p.player_id])

    def pool(roster: list[Player]) -> list[Player]:
        return sorted((p for p in roster if not p.in_injured_slot and val(p) > 0), key=val, reverse=True)[:pool_size]

    shapes = [(1, 1)]
    if max_per_side >= 2:
        shapes += [(2, 1), (1, 2)]

    my_pool = pool(my_roster)
    found = []
    for team_key, (team_name, roster) in others.items():
        their_pool = pool(roster)
        for n_give, n_get in shapes:
            for give in itertools.combinations(my_pool, n_give):
                for get in itertools.combinations(their_pool, n_get):
                    r = evaluate_trade(my_roster, roster, list(give), list(get), slots, week, projs)
                    mine, theirs = r["you"]["strength_change"], r["them"]["strength_change"]
                    if mine > 0.5 and theirs > 0:
                        found.append({"team_key": team_key, "team_name": team_name,
                                      "give": [p.name for p in give], "get": [p.name for p in get],
                                      "your_gain": mine, "their_gain": theirs,
                                      "your_lineup_change": r["you"]["lineup_change"],
                                      "their_lineup_change": r["them"]["lineup_change"]})
    # Prefer deals that are good for you *and* attractive enough for them to accept.
    found.sort(key=lambda t: (min(t["your_gain"], 3 * t["their_gain"]), t["your_gain"]), reverse=True)
    return found[:top_n]


# --------------------------------------------------------------------------------------------
# League history
# --------------------------------------------------------------------------------------------


@dataclass
class SeasonResult:
    season: int
    league_key: str
    team_key: str
    team_name: str
    manager_id: str  # Yahoo manager guid when available, else nickname
    manager_name: str
    rank: int | None
    wins: int
    losses: int
    ties: int
    points_for: float
    points_against: float
    finished: bool
    made_playoffs: bool = False


@dataclass
class Game:
    season: int
    week: int
    team_a: str
    team_b: str
    points_a: float
    points_b: float
    is_playoffs: bool = False
    is_consolation: bool = False


def summarize_history(results: list[SeasonResult]) -> dict:
    by_manager: dict[str, list[SeasonResult]] = defaultdict(list)
    for r in results:
        by_manager[r.manager_id].append(r)

    managers = []
    for mid, rows in by_manager.items():
        done = [r for r in rows if r.finished]
        games = sum(r.wins + r.losses + r.ties for r in rows)
        wins = sum(r.wins for r in rows)
        ties = sum(r.ties for r in rows)
        ranks = [r.rank for r in done if r.rank]
        managers.append({
            "manager": rows[-1].manager_name if rows else mid,
            "manager_id": mid,
            "seasons": len(rows),
            "championships": sum(1 for r in done if r.rank == 1),
            "championship_years": sorted(r.season for r in done if r.rank == 1),
            "runner_ups": sum(1 for r in done if r.rank == 2),
            "playoff_appearances": sum(1 for r in done if r.made_playoffs),
            "wins": wins, "losses": sum(r.losses for r in rows), "ties": ties,
            "win_pct": round((wins + 0.5 * ties) / games, 3) if games else None,
            "points_for": round(sum(r.points_for for r in rows), 2),
            "points_against": round(sum(r.points_against for r in rows), 2),
            "avg_points_for_per_game": round(sum(r.points_for for r in rows) / games, 2) if games else None,
            "best_finish": min(ranks) if ranks else None,
            "avg_finish": round(mean(ranks), 2) if ranks else None,
            "team_names": sorted({r.team_name for r in rows}),
        })
    managers.sort(key=lambda m: (-m["championships"], -(m["win_pct"] or 0)))

    seasons = sorted({r.season for r in results}, reverse=True)
    by_season = []
    for s in seasons:
        rows = sorted((r for r in results if r.season == s), key=lambda r: (r.rank or 99))
        champ = next((r for r in rows if r.finished and r.rank == 1), None)
        by_season.append({
            "season": s,
            "finished": all(r.finished for r in rows),
            "champion": f"{champ.team_name} ({champ.manager_name})" if champ else None,
            "standings": [{"rank": r.rank, "team": r.team_name, "manager": r.manager_name,
                           "record": f"{r.wins}-{r.losses}" + (f"-{r.ties}" if r.ties else ""),
                           "points_for": r.points_for, "points_against": r.points_against} for r in rows],
        })

    def _record(label: str, key: Callable[[SeasonResult], float], highest: bool = True) -> dict | None:
        rows = [r for r in results if r.finished]
        if not rows:
            return None
        r = (max if highest else min)(rows, key=key)
        return {"record": label, "season": r.season, "team": r.team_name, "manager": r.manager_name, "value": key(r)}

    records = [x for x in (
        _record("most points for in a season", lambda r: r.points_for),
        _record("fewest points for in a season", lambda r: r.points_for, highest=False),
        _record("most wins in a season", lambda r: r.wins),
        _record("most points against in a season", lambda r: r.points_against),
    ) if x]

    return {"seasons_covered": seasons, "managers": managers, "seasons": by_season, "records": records}


def head_to_head(games: list[Game], manager_of: dict[tuple[int, str], tuple[str, str]],
                 focus_manager_id: str | None = None) -> dict:
    """All-time records between managers.

    ``manager_of`` maps (season, team_key) -> (manager_id, manager_name). With
    ``focus_manager_id`` only that manager's rivalries are returned.
    """
    names: dict[str, str] = {}
    rec: dict[tuple[str, str], dict] = {}
    biggest_wins: list[dict] = []
    for g in games:
        a = manager_of.get((g.season, g.team_a))
        b = manager_of.get((g.season, g.team_b))
        if not a or not b or a[0] == b[0] or g.is_consolation:
            continue
        names[a[0]], names[b[0]] = a[1], b[1]
        for (me, my_pts), (opp, opp_pts) in (((a, g.points_a), (b, g.points_b)), ((b, g.points_b), (a, g.points_a))):
            r = rec.setdefault((me[0], opp[0]), {"wins": 0, "losses": 0, "ties": 0, "points_for": 0.0,
                                                 "points_against": 0.0, "playoff_wins": 0, "playoff_losses": 0})
            r["points_for"] += my_pts
            r["points_against"] += opp_pts
            if my_pts > opp_pts:
                r["wins"] += 1
                r["playoff_wins"] += g.is_playoffs
            elif my_pts < opp_pts:
                r["losses"] += 1
                r["playoff_losses"] += g.is_playoffs
            else:
                r["ties"] += 1
        winner, loser = (a, b) if g.points_a >= g.points_b else (b, a)
        biggest_wins.append({"season": g.season, "week": g.week, "winner": winner[1], "loser": loser[1],
                             "score": f"{max(g.points_a, g.points_b):.2f}-{min(g.points_a, g.points_b):.2f}",
                             "margin": round(abs(g.points_a - g.points_b), 2), "playoffs": g.is_playoffs})

    rows = []
    for (me, opp), r in rec.items():
        if focus_manager_id and me != focus_manager_id:
            continue
        if not focus_manager_id and me > opp:  # each pair once
            continue
        rows.append({"manager": names[me], "opponent": names[opp], **r,
                     "points_for": round(r["points_for"], 2), "points_against": round(r["points_against"], 2)})
    rows.sort(key=lambda r: (r["wins"] - r["losses"]), reverse=True)
    biggest_wins.sort(key=lambda g: g["margin"], reverse=True)
    return {"games_counted": len(games), "rivalries": rows, "biggest_blowouts": biggest_wins[:5],
            "closest_games": sorted(biggest_wins, key=lambda g: g["margin"])[:5]}
