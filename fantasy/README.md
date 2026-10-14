# Fantasy Coach: Yahoo Fantasy Football for Claude

An MCP server that lets Claude read your Yahoo Fantasy Football league and give you advice on:

- **League history**: champions, standings by year, every manager's all-time record and titles,
  single-season records, and head-to-head rivalries.
- **Waivers**: which free agents or waiver players to add and who to drop, ranked by how much they
  improve your team for the rest of the season.
- **Trades**: a full breakdown of any offer, plus a search for trades that help both teams, so the
  other manager is likely to accept.
- **Lineups**: who to start and sit this week, with alerts for starters who are on bye, injured or
  questionable.

It is **read-only**. Nothing in it can add, drop, trade or change your lineup; it suggests moves and
you make them in the Yahoo app.

## Built on spilchen/yahoo_fantasy_mcp

The Yahoo data layer is [spilchen/yahoo_fantasy_mcp](https://github.com/spilchen/yahoo_fantasy_mcp)
(MIT), written by the author of the `yahoo_fantasy_api` library. Its `tools.py` is vendored unmodified
in [`src/fantasy_coach/vendor/yahoo_fantasy_mcp`](src/fantasy_coach/vendor/yahoo_fantasy_mcp/VENDORED.md),
and all 21 of its tools (standings, rosters, matchups, free agents, transactions and more) are exposed
here, with `league_id` made optional.

Upstream's server file targets MCP Python SDK 1.x and doesn't run on SDK 2.x, so this project registers
the same tools on the 2.x `MCPServer` and adds the analysis tools on top.

## Setup

### 1. Create a Yahoo developer app (one time)

1. Go to <https://developer.yahoo.com/apps/create/> and sign in with the Yahoo account that's in your league.
2. Fill in:
   - **Application Name**: anything, e.g. `fantasy-coach`
   - **Application Type**: Installed Application
   - **Redirect URI(s)**: `oob`. If Yahoo rejects that, use `https://localhost:8080` and see step 3.
   - **API Permissions**: Fantasy Sports, **Read**
3. Create the app and copy the **Client ID** and **Client Secret**.

### 2. Install

Requires Python 3.10+.

```bash
cd fantasy
python -m venv .venv
source .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -e .
```

### 3. Sign in to Yahoo (one time, in a terminal)

```bash
fantasy-coach auth
# If your app's redirect URI is https://localhost:8080:
fantasy-coach auth --redirect-uri https://localhost:8080
```

Paste the Client ID and Secret when asked. A browser opens; approve access and paste the code Yahoo shows.
With an `https://localhost` redirect, the browser lands on a page that fails to load. That's expected:
copy the value after `code=` from its address bar.

Tokens are saved to `~/.config/fantasy-coach/oauth2.json` with owner-only permissions; set
`YAHOO_OAUTH_FILE` to put them elsewhere. Don't commit that file. The server refreshes the token by itself
after this, so you only do this step once.

The command finishes by listing your leagues:

```
Your NFL leagues for 2026:
  461.l.123456  The League
```

You can run `fantasy-coach leagues` (or `fantasy-coach leagues --year 2025`) again at any time.

### 4. Connect it to Claude

**Claude Code**:

```bash
claude mcp add fantasy-coach -e YAHOO_LEAGUE_ID=461.l.123456 -- /full/path/to/fantasy/.venv/bin/fantasy-coach serve
```

**Claude Desktop**: Settings → Developer → Edit Config, then add:

```json
{
  "mcpServers": {
    "fantasy-coach": {
      "command": "/full/path/to/fantasy/.venv/bin/fantasy-coach",
      "args": ["serve"],
      "env": { "YAHOO_LEAGUE_ID": "461.l.123456" }
    }
  }
}
```

On Windows the command is `C:\\full\\path\\to\\fantasy\\.venv\\Scripts\\fantasy-coach.exe`. Restart
Claude Desktop afterwards.

`YAHOO_LEAGUE_ID` is optional. Without it, Claude passes `league_id` to each tool and can find your leagues
with `list_my_leagues`.

## Things to ask

- "Who should I start this week?"
- "Any waiver pickups worth making? I'm thin at RB."
- "Should I trade Garrett Wilson for Bijan Robinson?"
- "Find me a trade with Sam's team that helps both of us."
- "Who's won the most titles in our league? What's my record against Alex?"

## Tools

| Tool | What it does |
|---|---|
| `my_team` | Your roster with projected points, recent form, bye weeks, injury status and current standing |
| `lineup_advice` | Best lineup for a week compared with your current one, with bye and injury alerts and open-IR hints |
| `waiver_advice` | Ranked add/drop pairs from free agents and waivers, with your league's waiver rules |
| `analyze_trade` | Before and after for both teams' lineups and depth, forced drops, and a verdict |
| `find_trades` | Searches 1-for-1, 2-for-1 and 1-for-2 deals that improve both teams |
| `league_history` | Champions, yearly standings, per-manager all-time stats, season records |
| `head_to_head` | All-time manager vs. manager records, blowouts and closest games |
| `list_my_leagues` + 20 `get_*`/`search_players` tools | Raw Yahoo data from spilchen's server |

## How the numbers work

Yahoo's API doesn't provide its projections, so this server builds its own simple, transparent ones
from your league's actual scoring:

- **Projected points** (healthy week) = 60% average of the last 3 games played + 40% season average,
  over the last 6 weeks. With fewer than 3 games, it blends in last season's points per game.
  A week with 0 points, or the bye week, counts as not played.
- **Injury discount**: for this week's lineup, Q counts 85%, D counts 40%, and O, IR, PUP and SUSP
  count 0. For rest-of-season value, Q is 95%, D 85%, O 75%, IR and PUP 35%, SUSP 50%.
- **Team strength** = best possible starting lineup + 15% of bench depth (up to your bench size).
  Waiver and trade advice is the change in team strength.
- **Lineups** fill single-position slots first, then flex slots (`W/R/T`, `Q/W/R/T`, IDP `D`/`DL`/`DB`).

The numbers don't account for matchups, depth-chart changes or news. Claude sees every input, so ask it
to weigh in what it knows.

## Development

```bash
pip install -e ".[dev]"
python -m pytest
```

The tests run against an in-memory fake of `yahoo_fantasy_api` (`tests/fakes.py`), so they need no
Yahoo account or network.

| Path | Purpose |
|---|---|
| `src/fantasy_coach/analysis.py` | Projections, lineup optimizer, waiver, trade and history math (no I/O) |
| `src/fantasy_coach/yahoo.py` | Read-only Yahoo access via `yahoo_fantasy_api`, with caching |
| `src/fantasy_coach/server.py` | MCP tools |
| `src/fantasy_coach/__main__.py` | `fantasy-coach auth / leagues / serve` |
| `src/fantasy_coach/vendor/yahoo_fantasy_mcp/` | spilchen's tools, vendored (MIT) |
