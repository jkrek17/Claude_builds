"""MCP server: spilchen's Yahoo data tools plus league history, waiver, trade and lineup analysis.

Every tool is read-only. The server suggests moves; you make them in the Yahoo app.
"""

from __future__ import annotations

import asyncio
import functools
import json
import os
import re
from pathlib import Path
from typing import Any, Callable

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations

from . import analysis
from .analysis import Player
from .vendor.yahoo_fantasy_mcp import YahooFantasyTools
from .yahoo import SKILL_POSITIONS, YahooLeagueData, manager_identity

DEFAULT_OAUTH_FILE = Path.home() / ".config" / "fantasy-coach" / "oauth2.json"
READ_ONLY = ToolAnnotations(readOnlyHint=True, destructiveHint=False, openWorldHint=True)

INSTRUCTIONS = """\
Yahoo Fantasy Football coach. All tools are read-only: recommend moves, never claim to have made them.
Start with my_team to see the roster and projections. Use lineup_advice before each week's games,
waiver_advice for pickups, analyze_trade to judge a specific offer, find_trades to discover offers worth
proposing, and league_history / head_to_head for past seasons and rivalries. The get_* and search_players
tools return raw Yahoo data when you need detail the analysis tools don't include.
Projections are this league's fantasy points per healthy week (recent form blended with season average),
not Yahoo's or experts' projections, so weigh them with news, matchups and injuries you know about.
If league_id is omitted, tools use the YAHOO_LEAGUE_ID environment variable."""


def oauth_file_path() -> Path:
    return Path(os.environ.get("YAHOO_OAUTH_FILE") or DEFAULT_OAUTH_FILE).expanduser()


class Coach:
    """Lazily connects to Yahoo on first use, so the server starts (and lists tools) offline."""

    def __init__(self, tools: YahooFantasyTools | None = None, data: YahooLeagueData | None = None,
                 default_league: str | None = None):
        self._tools = tools
        self._data = data
        self.default_league = default_league if default_league is not None else os.environ.get("YAHOO_LEAGUE_ID")

    @property
    def tools(self) -> YahooFantasyTools:
        if self._tools is None:
            path = oauth_file_path()
            if not path.exists():
                raise ToolError(f"No Yahoo sign-in found at {path}. Run `fantasy-coach auth` first.")
            self._tools = YahooFantasyTools(oauth2_file=str(path))
        return self._tools

    @property
    def data(self) -> YahooLeagueData:
        if self._data is None:
            self._data = YahooLeagueData(self.tools._oauth)
        return self._data

    def league(self, league_id: str | None) -> str:
        key = league_id or self.default_league
        if not key:
            raise ToolError("No league given and YAHOO_LEAGUE_ID is not set. Call list_my_leagues to find one.")
        return key

    # -- shared lookups -----------------------------------------------------------------------

    def my_roster(self, league_key: str, week: int) -> tuple[str, list[Player]]:
        team_key = self.data.my_team_key(league_key)
        return team_key, self.data.enrich(league_key, self.data.roster(team_key, week), week)

    def other_rosters(self, league_key: str, week: int, only: str | None = None,
                      enrich: bool = True) -> dict[str, tuple[str, list[Player]]]:
        mine = self.data.my_team_key(league_key)
        teams = self.data.teams(league_key)
        keys = [k for k in teams if k != mine]
        if only:
            keys = [resolve_team(only, {k: teams[k] for k in keys})]
        rosters = {k: (teams[k].get("name", k), self.data.roster(k, week)) for k in keys}
        if enrich:
            self.data.enrich(league_key, [p for _, r in rosters.values() for p in r], week)
        return rosters


def resolve_team(query: str, teams: dict[str, dict]) -> str:
    """Team key from a team key, team name, or manager nickname (case-insensitive, partial ok)."""
    if query in teams:
        return query
    q = query.lower().strip()
    exact = [k for k, t in teams.items() if t.get("name", "").lower() == q or manager_identity(t)[1].lower() == q]
    if len(exact) == 1:
        return exact[0]
    partial = [k for k, t in teams.items() if q in t.get("name", "").lower() or q in manager_identity(t)[1].lower()]
    if len(partial) == 1:
        return partial[0]
    options = ", ".join(f"{t.get('name')} ({manager_identity(t)[1]})" for t in teams.values())
    raise ToolError(f"Couldn't pick one team for '{query}'. Teams: {options}")


def resolve_player(query: str, players: list[Player]) -> Player:
    """Player from an ID or name (case-insensitive, partial ok) among ``players``."""
    q = str(query).strip()
    if q.isdigit():
        hit = [p for p in players if p.player_id == int(q)]
    else:
        norm = lambda s: re.sub(r"[^a-z0-9 ]", "", s.lower())  # noqa: E731
        hit = [p for p in players if norm(p.name) == norm(q)] or [p for p in players if norm(q) in norm(p.name)]
    if len(hit) == 1:
        return hit[0]
    if not hit:
        raise ToolError(f"No rostered player matches '{query}'.")
    raise ToolError(f"'{query}' matches several players: {', '.join(p.name for p in hit)}. Be more specific.")


def _tool_errors(fn: Callable) -> Callable:
    """Surface failures to the model as readable tool errors instead of a generic crash."""
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except ToolError:
            raise
        except Exception as e:
            raise ToolError(f"{type(e).__name__}: {e}") from e
    return wrapper


def _jsonable(obj: Any) -> Any:
    return json.loads(json.dumps(obj, default=str))


def create_server(coach: Coach | None = None) -> MCPServer:
    coach = coach or Coach()
    server = MCPServer("fantasy-coach", instructions=INSTRUCTIONS)

    def tool(fn: Callable) -> Callable:
        return server.tool(annotations=READ_ONLY)(_tool_errors(fn))

    def upstream(method: str, *args) -> dict:
        # spilchen's tool methods are async but block on HTTP; run them on this worker thread.
        return _jsonable(asyncio.run(getattr(coach.tools, method)(*args)))

    # ---------------------------------------------------------------------------------------
    # Raw Yahoo data (spilchen/yahoo_fantasy_mcp tools, league_id now optional)
    # ---------------------------------------------------------------------------------------

    @tool
    def list_my_leagues(year: int | None = None) -> dict:
        """List your Yahoo NFL leagues for a season (default: this year) with their league IDs."""
        return _jsonable(coach.tools.get_all_leagues(game_code="nfl", year=year))

    @tool
    def get_team_key(league_id: str | None = None) -> dict:
        """Get the team key for your team in a league."""
        return upstream("get_team_key", coach.league(league_id))

    @tool
    def get_current_week(league_id: str | None = None) -> dict:
        """Get the league's current week number."""
        return upstream("get_current_week", coach.league(league_id))

    @tool
    def get_edit_date(league_id: str | None = None) -> dict:
        """Get the next date lineups can be edited (roster deadline)."""
        return upstream("get_edit_date", coach.league(league_id))

    @tool
    def get_end_week(league_id: str | None = None) -> dict:
        """Get the last week of the league's season."""
        return upstream("get_end_week", coach.league(league_id))

    @tool
    def get_matchups(league_id: str | None = None, week: int | None = None) -> dict:
        """Raw scoreboard for a week (default: current week)."""
        return upstream("get_matchups", coach.league(league_id), week)

    @tool
    def get_positions(league_id: str | None = None) -> dict:
        """Roster positions used in the league with their counts."""
        return upstream("get_positions", coach.league(league_id))

    @tool
    def get_settings(league_id: str | None = None) -> dict:
        """League settings: scoring type, playoffs, waivers, trade deadline and more."""
        return upstream("get_settings", coach.league(league_id))

    @tool
    def get_stat_categories(league_id: str | None = None) -> dict:
        """Stat categories tracked in the league."""
        return upstream("get_stat_categories", coach.league(league_id))

    @tool
    def get_teams(league_id: str | None = None) -> dict:
        """All teams with managers, waiver priority, moves and trades made."""
        return upstream("get_teams", coach.league(league_id))

    @tool
    def get_transactions(tran_types: str = "add,drop,trade", league_id: str | None = None,
                         count: str | None = None) -> dict:
        """League transactions. tran_types is comma-separated: add, drop, commish, trade."""
        return upstream("get_transactions", coach.league(league_id), tran_types, count)

    @tool
    def get_league_standings(league_id: str | None = None) -> dict:
        """Current standings."""
        return upstream("get_league_standings", coach.league(league_id))

    @tool
    def get_team_roster(team_key: str, week: int | None = None, day: str | None = None) -> dict:
        """A team's roster for a week (or day, YYYY-MM-DD). Defaults to today."""
        return upstream("get_team_roster", team_key, week, day)

    @tool
    def get_team_details(team_key: str) -> dict:
        """Details about one team."""
        return upstream("get_team_details", team_key)

    @tool
    def get_team_matchup(team_key: str, week: int) -> dict:
        """Opponent team key for a team in a given week."""
        return upstream("get_team_matchup", team_key, week)

    @tool
    def get_team_proposed_trades(team_key: str) -> dict:
        """Pending trades a team has offered or received."""
        return upstream("get_team_proposed_trades", team_key)

    @tool
    def get_matchup_scores(team_key: str, week: int | None = None) -> dict:
        """Scores for a team's matchup (default: current week)."""
        return upstream("get_matchup_scores", team_key, week)

    @tool
    def get_player_stats(player_ids: str, req_type: str = "season", league_id: str | None = None,
                         date: str | None = None, week: int | None = None, season: int | None = None) -> dict:
        """Player stats in league scoring. player_ids is comma-separated. req_type: season,
        average_season, lastweek, lastmonth, date or week."""
        ids = [int(x) for x in player_ids.split(",")]
        return upstream("get_player_stats", coach.league(league_id), ids, req_type, date, week, season)

    @tool
    def search_players(player: str, league_id: str | None = None) -> dict:
        """Find players by name (up to 25 matches) or by comma-separated player IDs."""
        parts = [p.strip() for p in player.split(",")]
        query: Any = [int(p) for p in parts] if all(p.isdigit() for p in parts) else player
        if isinstance(query, list) and len(query) == 1:
            query = query[0]
        return upstream("search_players", coach.league(league_id), query)

    @tool
    def get_free_agents(position: str, league_id: str | None = None) -> dict:
        """Free agents eligible at a position (QB, RB, WR, TE, K, DEF, ...)."""
        return upstream("get_free_agents", coach.league(league_id), position)

    @tool
    def get_waivers(league_id: str | None = None) -> dict:
        """Players currently on waivers."""
        return upstream("get_waivers", coach.league(league_id))

    # ---------------------------------------------------------------------------------------
    # Analysis
    # ---------------------------------------------------------------------------------------

    @tool
    def my_team(league_id: str | None = None, week: int | None = None) -> dict:
        """Your roster with each player's projected points, recent form, bye week and injury status,
        plus your standing in the league."""
        lk = coach.league(league_id)
        wk = week or coach.data.current_week(lk)
        team_key, roster = coach.my_roster(lk, wk)
        standing = next((s for s in coach.data.league(lk).standings() if s.get("team_key") == team_key), None)
        return _jsonable({
            "league_key": lk, "team_key": team_key, "week": wk, "standing": standing,
            "slots": coach.data.slots(lk),
            "roster": [{"player": p.name, "player_id": p.player_id, "positions": p.positions, "nfl_team": p.nfl_team,
                        "slot": p.selected_position, "status": p.status, "bye_week": p.bye_week,
                        **analysis.project(p, wk).as_dict(),
                        "recent_weeks": dict(sorted(p.weekly_points.items()))} for p in roster],
        })

    @tool
    def lineup_advice(league_id: str | None = None, week: int | None = None) -> dict:
        """Best start/sit lineup for a week (default: current) versus your current lineup, with alerts
        for starters who are on bye, injured, or questionable, and empty or open IR slots."""
        lk = coach.league(league_id)
        wk = week or coach.data.current_week(lk)
        _, roster = coach.my_roster(lk, wk)
        return _jsonable(analysis.lineup_advice(roster, coach.data.slots(lk), wk))

    @tool
    def waiver_advice(league_id: str | None = None, positions: list[str] | None = None, top_n: int = 10,
                      per_position: int = 15) -> dict:
        """Best add/drop moves from free agents and waivers, ranked by how much they improve your
        rest-of-season team. positions defaults to QB, RB, WR, TE, K, DEF; per_position limits how many
        of the most-rostered free agents are evaluated at each."""
        lk = coach.league(league_id)
        wk = coach.data.current_week(lk)
        _, roster = coach.my_roster(lk, wk)
        slots = coach.data.slots(lk)
        positions = positions or [p for p in SKILL_POSITIONS if p in slots or any(p in analysis.slot_accepts(s) for s in slots)]
        candidates = coach.data.enrich(lk, coach.data.available(lk, positions, per_position), wk)
        result = analysis.waiver_recommendations(roster, candidates, slots, wk, top_n)
        settings = coach.data.settings(lk)
        result["league_waiver_rules"] = {k: settings.get(k) for k in ("waiver_type", "waiver_rule", "uses_faab", "waiver_time")}
        return _jsonable(result)

    @tool
    def analyze_trade(give: list[str], get: list[str], partner: str | None = None,
                      league_id: str | None = None) -> dict:
        """Evaluate a trade: give and get are player names (partial is fine) or IDs. The partner team is
        found from the players you'd get unless named (team name or manager). Shows how both teams'
        rest-of-season lineups and depth change, and who'd have to be dropped for roster space."""
        if not give or not get:
            raise ToolError("List at least one player to give and one to get.")
        lk = coach.league(league_id)
        wk = coach.data.current_week(lk)
        _, mine = coach.my_roster(lk, wk)
        give_players = [resolve_player(g, mine) for g in give]
        # Find the partner from bare rosters first so only their players need stats.
        others = coach.other_rosters(lk, wk, only=partner, enrich=False)
        if partner:
            (partner_key, (partner_name, theirs)), = others.items()
        else:
            first = resolve_player(get[0], [p for _, r in others.values() for p in r])
            partner_key, (partner_name, theirs) = next(
                (k, v) for k, v in others.items() if any(p.player_id == first.player_id for p in v[1]))
        get_players = [resolve_player(g, theirs) for g in get]
        coach.data.enrich(lk, theirs, wk)
        result = analysis.evaluate_trade(mine, theirs, give_players, get_players, coach.data.slots(lk), wk)
        result["partner"] = {"team_key": partner_key, "team_name": partner_name}
        settings = coach.data.settings(lk)
        result["league_trade_rules"] = {k: settings.get(k) for k in ("trade_end_date", "trade_ratify_type", "trade_reject_time")}
        return _jsonable(result)

    @tool
    def find_trades(partner: str | None = None, max_players_per_side: int = 2, top_n: int = 10,
                    league_id: str | None = None) -> dict:
        """Search for trades that improve your team while also helping the other team (so they're likely
        to be accepted). Checks 1-for-1 swaps, and 2-for-1 / 1-for-2 when max_players_per_side is 2.
        Optionally limit to one partner (team name or manager)."""
        lk = coach.league(league_id)
        wk = coach.data.current_week(lk)
        _, mine = coach.my_roster(lk, wk)
        others = coach.other_rosters(lk, wk, only=partner)
        trades = analysis.find_trades(mine, others, coach.data.slots(lk), wk, max(1, min(max_players_per_side, 2)), top_n)
        return _jsonable({"week": wk, "teams_searched": len(others), "trades": trades,
                          "next_step": "Run analyze_trade on any of these for the full breakdown."})

    @tool
    def league_history(max_seasons: int = 15, league_id: str | None = None) -> dict:
        """All-time league history across renewed seasons: champions, standings by year, each manager's
        titles, record, points and average finish, and single-season records."""
        lk = coach.league(league_id)
        chain, note = coach.data.season_chain(lk, max_seasons)
        results, skipped = [], []
        for key in chain:
            try:
                results += coach.data.season_results(key)
            except Exception as e:
                skipped.append({"league_key": key, "error": str(e)})
        summary = analysis.summarize_history(results)
        summary.update({"league_keys": chain, "skipped_seasons": skipped, "note": note})
        return _jsonable(summary)

    @tool
    def head_to_head(manager: str | None = None, max_seasons: int = 5, league_id: str | None = None) -> dict:
        """All-time head-to-head records between managers, from every completed matchup (one Yahoo call
        per week per season, so this is slower). manager defaults to you; pass "all" for every pairing."""
        lk = coach.league(league_id)
        chain, note = coach.data.season_chain(lk, max_seasons)
        games, manager_of, skipped = [], {}, []
        for key in chain:
            try:
                season = coach.data.season(key)
                for tk, team in coach.data.teams(key).items():
                    manager_of[(season, tk)] = manager_identity(team)
                games += coach.data.season_games(key)
            except Exception as e:
                skipped.append({"league_key": key, "error": str(e)})
        focus = None
        if manager != "all":
            teams = coach.data.teams(lk)
            tk = resolve_team(manager, teams) if manager else coach.data.my_team_key(lk)
            focus = manager_identity(teams[tk])[0]
        result = analysis.head_to_head(games, manager_of, focus)
        result.update({"seasons": len(chain) - len(skipped), "skipped_seasons": skipped, "note": note})
        return _jsonable(result)

    return server
