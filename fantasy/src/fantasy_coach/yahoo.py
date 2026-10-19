"""Read-only Yahoo Fantasy data access for the analysis tools.

Wraps ``yahoo_fantasy_api`` (the same library the vendored spilchen tools use) and converts its
responses into ``fantasy_coach.analysis`` objects. Nothing here writes to Yahoo: the library's
roster-changing methods (add/drop/claim/trade/change_positions) are never called.
"""

from __future__ import annotations

import logging
from typing import Any, Iterable

import yahoo_fantasy_api as yfa

from .analysis import Game, Player, SeasonResult

logger = logging.getLogger(__name__)

SKILL_POSITIONS = ["QB", "RB", "WR", "TE", "K", "DEF"]
GAMES_PER_SEASON = 17


def _int(v: Any, default: int = 0) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


def _float(v: Any, default: float = 0.0) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def league_key_from_renew(renew: str | None) -> str | None:
    """Yahoo stores the previous season's league as '<game_id>_<league_id>'."""
    if not renew or "_" not in str(renew):
        return None
    game_id, league_id = str(renew).split("_", 1)
    return f"{game_id}.l.{league_id}"


def _find(obj: Any, key: str) -> Any:
    """Depth-first search for the first value stored under ``key`` in Yahoo's nested JSON."""
    if isinstance(obj, dict):
        if key in obj:
            return obj[key]
        children = obj.values()
    elif isinstance(obj, list):
        children = obj
    else:
        return None
    for child in children:
        found = _find(child, key)
        if found is not None:
            return found
    return None


def _numbered(container: Any) -> list[Any]:
    """Yahoo encodes lists as {"0": ..., "1": ..., "count": n}."""
    if not isinstance(container, dict):
        return []
    return [container[k] for k in sorted((k for k in container if k.isdigit()), key=int)]


def parse_scoreboard(raw: dict, season: int) -> list[Game]:
    """Completed games from a ``League.matchups(week)`` scoreboard response."""
    games = []
    for entry in _numbered(_find(raw, "matchups")):
        m = entry.get("matchup", {}) if isinstance(entry, dict) else {}
        if m.get("status") != "postevent":
            continue
        teams = []
        for t in _numbered(_find(m, "teams")):
            team_key = _find(t, "team_key")
            points = _find(t, "team_points")
            if team_key and isinstance(points, dict):
                teams.append((team_key, _float(points.get("total"))))
        if len(teams) != 2:
            continue
        games.append(Game(season=season, week=_int(m.get("week")), team_a=teams[0][0], team_b=teams[1][0],
                          points_a=teams[0][1], points_b=teams[1][1],
                          is_playoffs=str(m.get("is_playoffs")) == "1",
                          is_consolation=str(m.get("is_consolation")) == "1"))
    return games


def manager_identity(team: dict) -> tuple[str, str]:
    """(stable id, display name) for a team's primary manager."""
    managers = team.get("managers") or []
    if isinstance(managers, dict):
        managers = [managers]
    for m in managers:
        m = m.get("manager", m) if isinstance(m, dict) else {}
        name = m.get("nickname") or team.get("name", "?")
        if m.get("guid"):
            return m["guid"], name
        if name and name != "--hidden--":
            return f"nickname:{name}", name
    return f"team:{team.get('name', '?')}", team.get("name", "?")


class YahooLeagueData:
    """Cached, read-only accessors for one Yahoo session."""

    def __init__(self, oauth: Any, league_cls=yfa.League, team_cls=yfa.Team):
        self._oauth = oauth
        self._league_cls = league_cls
        self._team_cls = team_cls
        self._leagues: dict[str, Any] = {}
        self._weekly: dict[tuple[str, int, int], float] = {}

    def league(self, league_key: str):
        if league_key not in self._leagues:
            self._leagues[league_key] = self._league_cls(self._oauth, league_key)
        return self._leagues[league_key]

    # -- league basics ------------------------------------------------------------------------

    def settings(self, league_key: str) -> dict:
        return self.league(league_key).settings()

    def slots(self, league_key: str) -> dict[str, int]:
        return {pos: _int(info.get("count")) for pos, info in self.league(league_key).positions().items()}

    def current_week(self, league_key: str) -> int:
        return self.league(league_key).current_week()

    def season(self, league_key: str) -> int:
        return _int(self.settings(league_key).get("season"))

    def my_team_key(self, league_key: str) -> str:
        key = self.league(league_key).team_key()
        if not key:
            raise RuntimeError(f"You don't appear to have a team in league {league_key}.")
        return key

    def teams(self, league_key: str) -> dict[str, dict]:
        return self.league(league_key).teams()

    # -- players ------------------------------------------------------------------------------

    def roster(self, team_key: str, week: int | None = None) -> list[Player]:
        rows = self._team_cls(self._oauth, team_key).roster(week=week)
        return [Player(player_id=_int(r["player_id"]), name=r.get("name", "?"),
                       positions=list(r.get("eligible_positions") or []),
                       nfl_team=r.get("editorial_team_abbr", ""), status=r.get("status") or "",
                       selected_position=r.get("selected_position")) for r in rows]

    def available(self, league_key: str, positions: Iterable[str], per_position: int = 15) -> list[Player]:
        """Most-rostered free agents per position plus everyone on waivers."""
        lg = self.league(league_key)
        seen: dict[int, Player] = {}

        def add(rows: list[dict], on_waivers: bool, limit: int | None) -> None:
            rows = sorted(rows, key=lambda r: _float(r.get("percent_owned")), reverse=True)
            for r in rows[:limit]:
                pid = _int(r["player_id"])
                if pid not in seen:
                    seen[pid] = Player(player_id=pid, name=r.get("name", "?"),
                                       positions=list(r.get("eligible_positions") or []),
                                       nfl_team=r.get("editorial_team_abbr", ""), status=r.get("status") or "",
                                       percent_owned=_float(r.get("percent_owned"), None), on_waivers=on_waivers)

        wanted = set(positions)
        try:
            add([r for r in lg.waivers() if wanted & set(r.get("eligible_positions") or [])], True, None)
        except Exception as e:  # waivers are optional context; free agents still work without them
            logger.warning("Could not load waivers for %s: %s", league_key, e)
        for pos in positions:
            add(lg.free_agents(pos), False, per_position)
        return list(seen.values())

    def enrich(self, league_key: str, players: list[Player], week: int, lookback: int = 6) -> list[Player]:
        """Fill weekly points (this league's scoring), bye weeks and, for thin samples, last season's PPG."""
        if not players:
            return players
        lg = self.league(league_key)
        ids = sorted({p.player_id for p in players})
        weeks = list(range(max(1, week - lookback), week))
        for wk in weeks:
            missing = [i for i in ids if (league_key, wk, i) not in self._weekly]
            if missing:
                for row in lg.player_stats(missing, "week", week=wk):
                    self._weekly[(league_key, wk, _int(row["player_id"]))] = _float(row.get("total_points"))
        for p in players:
            p.weekly_points = {wk: self._weekly.get((league_key, wk, p.player_id), 0.0) for wk in weeks}

        try:
            byes = {_int(d.get("player_id")): _find(d.get("bye_weeks"), "week") for d in lg.player_details(ids)}
            for p in players:
                p.bye_week = _int(byes.get(p.player_id), None)
        except Exception as e:
            logger.warning("Could not load bye weeks: %s", e)

        thin = [p for p in players if sum(1 for v in p.weekly_points.values() if v) < 3]
        if thin:
            try:
                prev = self.season(league_key) - 1
                rows = lg.player_stats([p.player_id for p in thin], "season", season=prev)
                by_id = {_int(r["player_id"]): r for r in rows}
                for p in thin:
                    r = by_id.get(p.player_id)
                    if r:
                        gp = _float(r.get("GP")) or GAMES_PER_SEASON
                        p.prior_ppg = _float(r.get("total_points")) / gp
            except Exception as e:
                logger.warning("Could not load last season's stats: %s", e)
        return players

    # -- history ------------------------------------------------------------------------------

    def season_chain(self, league_key: str, max_seasons: int) -> tuple[list[str], str | None]:
        """This league and its predecessors, newest first, plus a note if the chain stopped early."""
        chain = [league_key]
        note = None
        while len(chain) < max_seasons:
            try:
                prev = league_key_from_renew(self.settings(chain[-1]).get("renew"))
            except Exception as e:
                note = f"Stopped at {chain[-1]}: {e}"
                break
            if not prev:
                break
            chain.append(prev)
        return chain, note

    def season_results(self, league_key: str) -> list[SeasonResult]:
        settings = self.settings(league_key)
        season = _int(settings.get("season"))
        finished = str(settings.get("is_finished", "")) in ("1", "True", "true")
        playoff_teams = _int(settings.get("num_playoff_teams"))
        teams = self.teams(league_key)
        out = []
        for row in self.league(league_key).standings():
            tk = row.get("team_key")
            mid, mname = manager_identity(teams.get(tk, {"name": row.get("name")}))
            totals = row.get("outcome_totals") or {}
            seed = _int(row.get("playoff_seed"), None)
            out.append(SeasonResult(
                season=season, league_key=league_key, team_key=tk, team_name=row.get("name", "?"),
                manager_id=mid, manager_name=mname, rank=_int(row.get("rank"), None),
                wins=_int(totals.get("wins")), losses=_int(totals.get("losses")), ties=_int(totals.get("ties")),
                points_for=_float(row.get("points_for")), points_against=_float(row.get("points_against")),
                finished=finished, made_playoffs=bool(seed and playoff_teams and seed <= playoff_teams),
            ))
        return out

    def season_games(self, league_key: str) -> list[Game]:
        lg = self.league(league_key)
        season = self.season(league_key)
        last = lg.end_week()
        if str(self.settings(league_key).get("is_finished", "")) not in ("1", "True", "true"):
            last = min(last, lg.current_week())
        games = []
        for wk in range(1, last + 1):
            games += parse_scoreboard(lg.matchups(week=wk), season)
        return games
