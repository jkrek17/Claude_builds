"""An in-memory stand-in for yahoo_fantasy_api's League and Team, shaped like its real return values."""

from __future__ import annotations

SLOTS = {"QB": 1, "RB": 2, "WR": 2, "TE": 1, "W/R/T": 1, "K": 1, "DEF": 1, "BN": 4, "IR": 1}
CURRENT = "500.l.1"
PREVIOUS = "400.l.1"
WEEK = 7

# id: (name, positions, nfl team, ppg, status, bye week)
PLAYERS = {
    # my team, t.1
    1: ("Quinn Arrow", ["QB"], "KC", 20.0, "", 10),
    2: ("Rex Bolt", ["RB", "W/R/T"], "SF", 15.0, "", 9),
    3: ("Ray Cole", ["RB", "W/R/T"], "NYG", 5.0, "", 11),
    4: ("Wade Dunn", ["WR", "W/R/T"], "DAL", 14.0, "", 7),
    5: ("Will Eads", ["WR", "W/R/T"], "MIA", 12.0, "", 12),
    6: ("Walt Finch", ["WR", "W/R/T"], "CHI", 4.0, "", 5),
    7: ("Tom Gale", ["TE", "W/R/T"], "BAL", 8.0, "", 13),
    8: ("Kyle Hart", ["K"], "BUF", 8.0, "", 6),
    9: ("Detroit", ["DEF"], "DET", 7.0, "", 8),
    10: ("Rob Ince", ["RB", "W/R/T"], "LV", 3.0, "Q", 10),
    11: ("Ward Jett", ["WR", "W/R/T"], "SEA", 9.0, "", 14),
    # t.2: RB-rich, WR-poor
    20: ("Quentin Bay", ["QB"], "PHI", 18.0, "", 9),
    21: ("Ron Cask", ["RB", "W/R/T"], "ATL", 16.0, "", 11),
    22: ("Reid Dale", ["RB", "W/R/T"], "GB", 14.0, "", 10),
    23: ("Rudy Eve", ["RB", "W/R/T"], "MIN", 12.0, "", 6),
    24: ("Rhett Fox", ["RB", "W/R/T"], "NO", 10.0, "", 12),
    25: ("Wes Gray", ["WR", "W/R/T"], "TB", 6.0, "", 9),
    26: ("Wyatt Hale", ["WR", "W/R/T"], "CLE", 5.0, "", 5),
    27: ("Tate Ives", ["TE", "W/R/T"], "LAR", 7.0, "", 8),
    28: ("Kai Jones", ["K"], "HOU", 7.0, "", 6),
    29: ("Denver", ["DEF"], "DEN", 6.0, "", 14),
    # t.3
    30: ("Quade Kerr", ["QB"], "CIN", 19.0, "", 10),
    31: ("Ryan Lowe", ["RB", "W/R/T"], "DET", 13.0, "", 8),
    32: ("Ross Moon", ["RB", "W/R/T"], "IND", 9.0, "", 14),
    33: ("Wolf Nash", ["WR", "W/R/T"], "MIN", 15.0, "", 6),
    34: ("Webb Orr", ["WR", "W/R/T"], "LAC", 11.0, "", 5),
    35: ("Troy Pike", ["TE", "W/R/T"], "KC", 9.0, "", 10),
    36: ("Kent Quill", ["K"], "BAL", 9.0, "", 13),
    37: ("Pittsburgh", ["DEF"], "PIT", 8.0, "", 5),
    # free agents and waivers
    40: ("Wren Rourke", ["WR", "W/R/T"], "ARI", 11.0, "", 8),
    41: ("Riley Sand", ["RB", "W/R/T"], "CAR", 2.0, "", 11),
    42: ("Tad Tull", ["TE", "W/R/T"], "NE", 6.5, "", 14),
    43: ("Quincy Ute", ["QB"], "NYJ", 12.0, "", 9),
    44: ("Kip Vale", ["K"], "SEA", 9.5, "", 8),
    45: ("Wil Wade", ["WR", "W/R/T"], "JAX", 7.0, "O", 12),
}

ROSTERS = {
    f"{CURRENT}.t.1": [(1, "QB"), (2, "RB"), (3, "RB"), (4, "WR"), (6, "WR"), (7, "TE"), (5, "W/R/T"),
                       (8, "K"), (9, "DEF"), (10, "BN"), (11, "BN")],
    f"{CURRENT}.t.2": [(20, "QB"), (21, "RB"), (22, "RB"), (25, "WR"), (26, "WR"), (27, "TE"), (23, "W/R/T"),
                       (28, "K"), (29, "DEF"), (24, "BN")],
    f"{CURRENT}.t.3": [(30, "QB"), (31, "RB"), (32, "RB"), (33, "WR"), (34, "WR"), (35, "TE"), (36, "K"),
                       (37, "DEF")],
}
FREE_AGENTS = [40, 41, 42, 43, 44]
WAIVERS = [45]

MANAGERS = {"t.1": ("guid-me", "Jordan"), "t.2": ("guid-sam", "Sam"), "t.3": ("guid-alex", "Alex")}
TEAM_NAMES = {"t.1": "Arrowheads", "t.2": "Backfield Barons", "t.3": "Third Down"}

# Final 2025 standings and current 2026 standings: team suffix -> (rank, W, L, PF, PA, seed)
STANDINGS = {
    PREVIOUS: {"t.2": (1, 10, 4, 1600.5, 1400.0, 2), "t.1": (2, 11, 3, 1650.0, 1350.0, 1), "t.3": (3, 4, 10, 1300.0, 1500.0, 3)},
    CURRENT: {"t.1": (1, 5, 1, 700.0, 600.0, 1), "t.3": (2, 3, 3, 640.0, 650.0, 2), "t.2": (3, 1, 5, 590.0, 680.0, 3)},
}
# season league -> week -> list of (home, away, home pts, away pts, playoffs)
SCHEDULE = {
    PREVIOUS: {1: [("t.1", "t.2", 120.0, 100.0, False)], 2: [("t.1", "t.3", 90.0, 110.0, False)],
               3: [("t.2", "t.1", 130.0, 125.0, True)]},
    CURRENT: {1: [("t.1", "t.2", 140.0, 80.0, False)], 2: [("t.2", "t.3", 100.0, 99.0, False)]},
}


def weekly_points(pid: int, week: int) -> float:
    _, _, _, ppg, _, bye = PLAYERS[pid]
    return 0.0 if week == bye else ppg


def _player_row(pid: int) -> dict:
    name, pos, team, ppg, status, _ = PLAYERS[pid]
    return {"player_id": pid, "name": name, "position_type": "O", "eligible_positions": pos,
            "editorial_team_abbr": team, "status": status, "percent_owned": round(ppg * 4, 1)}


def _scoreboard(league_key: str, week: int) -> dict:
    matchups = {}
    for i, (a, b, pa, pb, playoffs) in enumerate(SCHEDULE[league_key].get(week, [])):
        teams = {str(j): {"team": [[{"team_key": f"{league_key}.{t}"}, {"team_id": t[2:]}, {"name": TEAM_NAMES[t]}],
                                   {"team_points": {"coverage_type": "week", "week": str(week), "total": f"{pts:.2f}"}}]}
                 for j, (t, pts) in enumerate(((a, pa), (b, pb)))}
        teams["count"] = 2
        matchups[str(i)] = {"matchup": {"week": str(week), "status": "postevent", "is_playoffs": "1" if playoffs else "0",
                                        "is_consolation": "0", "0": {"teams": teams}}}
    matchups["count"] = len(SCHEDULE[league_key].get(week, []))
    return {"fantasy_content": {"league": [{"league_key": league_key},
                                           {"scoreboard": {"0": {"matchups": matchups}, "week": str(week)}}]}}


class FakeLeague:
    calls: list[tuple] = []

    def __init__(self, oauth, league_key):
        self.league_key = league_key

    def settings(self):
        if self.league_key == CURRENT:
            return {"league_key": CURRENT, "name": "Test League", "season": "2026", "is_finished": 0,
                    "renew": "400_1", "num_playoff_teams": "2", "waiver_type": "R", "uses_faab": "0",
                    "trade_end_date": "2026-11-20"}
        if self.league_key == PREVIOUS:
            return {"league_key": PREVIOUS, "name": "Test League", "season": "2025", "is_finished": 1,
                    "renew": "", "num_playoff_teams": "2"}
        raise RuntimeError(f"no access to {self.league_key}")

    def positions(self):
        return {k: {"count": str(v) if k == "IR" else v} for k, v in SLOTS.items()}

    def current_week(self):
        return WEEK if self.league_key == CURRENT else 17

    def end_week(self):
        return 3 if self.league_key == PREVIOUS else 17

    def team_key(self):
        return f"{self.league_key}.t.1"

    def teams(self):
        return {f"{self.league_key}.{t}": {"team_key": f"{self.league_key}.{t}", "name": TEAM_NAMES[t],
                                           "managers": [{"manager": {"guid": g, "nickname": n}}]}
                for t, (g, n) in MANAGERS.items()}

    def standings(self):
        rows = sorted(STANDINGS[self.league_key].items(), key=lambda kv: kv[1][0])
        return [{"team_key": f"{self.league_key}.{t}", "name": TEAM_NAMES[t], "rank": r, "playoff_seed": str(seed),
                 "outcome_totals": {"wins": str(w), "losses": str(l), "ties": "0"},
                 "points_for": str(pf), "points_against": pa}
                for t, (r, w, l, pf, pa, seed) in rows]

    def free_agents(self, position):
        return [_player_row(p) for p in FREE_AGENTS if position in PLAYERS[p][1]]

    def waivers(self):
        return [_player_row(p) for p in WAIVERS]

    def player_stats(self, ids, req_type, date=None, week=None, season=None):
        FakeLeague.calls.append(("player_stats", req_type, week, season, tuple(ids)))
        if req_type == "week":
            return [{"player_id": i, "name": PLAYERS[i][0], "total_points": f"{weekly_points(i, week):.2f}"} for i in ids]
        if req_type == "season":
            return [{"player_id": i, "name": PLAYERS[i][0], "GP": 17.0, "total_points": str(PLAYERS[i][3] * 17)} for i in ids]
        raise ValueError(req_type)

    def player_details(self, ids):
        return [{"player_id": str(i), "bye_weeks": {"week": str(PLAYERS[i][5])}} for i in ids]

    def matchups(self, week=None):
        return _scoreboard(self.league_key, week)


class FakeTeam:
    def __init__(self, oauth, team_key):
        self.team_key = team_key

    def roster(self, week=None, day=None):
        return [{**_player_row(pid), "selected_position": slot} for pid, slot in ROSTERS[self.team_key]]
