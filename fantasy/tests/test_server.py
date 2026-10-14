import asyncio
import json

import pytest
from mcp.server.mcpserver.exceptions import ToolError

from fantasy_coach import server as srv
from fantasy_coach.yahoo import YahooLeagueData, league_key_from_renew, manager_identity, parse_scoreboard

from . import fakes


@pytest.fixture
def server():
    data = YahooLeagueData(oauth=None, league_cls=fakes.FakeLeague, team_cls=fakes.FakeTeam)
    return srv.create_server(srv.Coach(tools=object(), data=data, default_league=fakes.CURRENT))


def call(server, name, **args):
    result = asyncio.run(server.call_tool(name, args))
    assert not result.is_error, result.content[0].text
    return json.loads(result.content[0].text)


def test_every_tool_is_read_only(server):
    tools = asyncio.run(server.list_tools())
    names = {t.name for t in tools}
    assert {"my_team", "lineup_advice", "waiver_advice", "analyze_trade", "find_trades", "league_history",
            "head_to_head", "get_league_standings", "get_free_agents", "search_players"} <= names
    assert all(t.annotations.read_only_hint for t in tools)
    # None of yahoo_fantasy_api's roster-changing Team methods are exposed.
    assert not names & {"add_player", "drop_player", "add_and_drop_players", "claim_player", "claim_and_drop_players",
                        "propose_trade", "accept_trade", "reject_trade", "change_positions"}


def test_my_team_reports_projections_and_bye_weeks(server):
    out = call(server, "my_team")
    assert out["week"] == fakes.WEEK
    quinn = next(p for p in out["roster"] if p["player"] == "Quinn Arrow")
    assert quinn["proj_points"] == 20.0 and quinn["bye_week"] == 10
    assert out["standing"]["rank"] == 1


def test_lineup_advice_catches_starter_on_bye(server):
    out = call(server, "lineup_advice")
    assert "Wade Dunn" in [p["player"] for p in out["sit"]]  # bye in week 7
    assert "Ward Jett" in [p["player"] for p in out["start"]]
    assert any("Wade Dunn" in a and "bye" in a for a in out["alerts"])


def test_waiver_advice_finds_the_best_free_agent(server):
    out = call(server, "waiver_advice")
    assert out["recommendations"][0]["add"] == "Wren Rourke"
    assert out["league_waiver_rules"]["waiver_type"] == "R"


def test_analyze_trade_resolves_names_and_partner(server):
    out = call(server, "analyze_trade", give=["will eads"], get=["Rudy"])
    assert out["partner"]["team_name"] == "Backfield Barons"
    assert out["you_give"][0]["player"] == "Will Eads"
    assert out["you_get"][0]["player"] == "Rudy Eve"
    assert "verdict" in out


def test_analyze_trade_reports_ambiguous_names(server):
    with pytest.raises(ToolError, match="matches several players"):
        asyncio.run(server.call_tool("analyze_trade", {"give": ["W"], "get": ["Rudy Eve"]}))


def test_find_trades_returns_mutually_beneficial_offers(server):
    out = call(server, "find_trades", partner="Sam")
    assert out["teams_searched"] == 1
    assert all(t["your_gain"] > 0 and t["their_gain"] > 0 for t in out["trades"])


def test_league_history_follows_renewed_seasons(server):
    out = call(server, "league_history")
    assert out["seasons_covered"] == [2026, 2025]
    champ_2025 = next(s for s in out["seasons"] if s["season"] == 2025)["champion"]
    assert champ_2025 == "Backfield Barons (Sam)"
    jordan = next(m for m in out["managers"] if m["manager"] == "Jordan")
    assert jordan["wins"] == 16 and jordan["runner_ups"] == 1


def test_head_to_head_defaults_to_me(server):
    out = call(server, "head_to_head")
    by_opp = {r["opponent"]: r for r in out["rivalries"]}
    assert (by_opp["Sam"]["wins"], by_opp["Sam"]["losses"], by_opp["Sam"]["playoff_losses"]) == (2, 1, 1)
    assert (by_opp["Alex"]["wins"], by_opp["Alex"]["losses"]) == (0, 1)


def test_missing_sign_in_gives_actionable_error(tmp_path, monkeypatch):
    monkeypatch.setenv("YAHOO_OAUTH_FILE", str(tmp_path / "nope.json"))
    s = srv.create_server(srv.Coach(default_league="1.l.1"))
    with pytest.raises(ToolError, match="fantasy-coach auth"):
        asyncio.run(s.call_tool("get_league_standings", {}))


def test_missing_league_gives_actionable_error():
    s = srv.create_server(srv.Coach(tools=object(), data=object(), default_league=""))
    with pytest.raises(ToolError, match="list_my_leagues"):
        asyncio.run(s.call_tool("my_team", {}))


def test_parse_scoreboard_reads_yahoo_shape():
    games = parse_scoreboard(fakes._scoreboard(fakes.PREVIOUS, 3), 2025)
    assert len(games) == 1
    g = games[0]
    assert (g.team_a, g.team_b, g.points_a, g.points_b, g.is_playoffs) == ("400.l.1.t.2", "400.l.1.t.1", 130.0, 125.0, True)


def test_renew_and_manager_helpers():
    assert league_key_from_renew("388_27081") == "388.l.27081"
    assert league_key_from_renew("") is None
    assert manager_identity({"name": "T", "managers": [{"manager": {"guid": "g", "nickname": "N"}}]}) == ("g", "N")
    assert manager_identity({"name": "T", "managers": {"manager": {"nickname": "--hidden--"}}}) == ("team:T", "T")


def test_stats_are_cached_between_tools(server):
    fakes.FakeLeague.calls.clear()
    call(server, "my_team")
    first = len(fakes.FakeLeague.calls)
    call(server, "lineup_advice")
    assert len(fakes.FakeLeague.calls) == first


def test_upstream_tools_pass_through_with_default_league():
    class FakeTools:
        async def get_league_standings(self, league_id):
            return {"league_id": league_id, "standings": [{"rank": 1}]}

    s = srv.create_server(srv.Coach(tools=FakeTools(), data=object(), default_league="461.l.9"))
    out = call(s, "get_league_standings")
    assert out == {"league_id": "461.l.9", "standings": [{"rank": 1}]}
    assert call(s, "get_league_standings", league_id="461.l.3")["league_id"] == "461.l.3"
