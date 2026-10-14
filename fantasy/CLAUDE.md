# fantasy/: Yahoo Fantasy Football MCP server

- **Read-only, always.** Never add a tool or code path that changes a Yahoo league (add/drop/claim,
  trades, lineup changes, settings). `yahoo_fantasy_api.Team` has such methods; don't call them.
  Recommend moves and let the user make them in Yahoo.
- **Never print or commit credentials.** Client secret, access token and refresh token stay in env vars
  or the private `oauth2.json` (gitignored). Write the refresh token to a private file for the user;
  don't show it in chat or logs.
- `src/fantasy_coach/vendor/yahoo_fantasy_mcp/tools.py` is vendored from spilchen/yahoo_fantasy_mcp and
  stays unmodified; change behavior in `server.py`/`yahoo.py` instead (see `VENDORED.md`).
- Keep math in `analysis.py` free of I/O so it stays unit-testable; Yahoo access goes in `yahoo.py`.
- Test with `pip install -e ".[dev]" && python -m pytest` from `fantasy/`. Tests use the fake league
  in `tests/fakes.py` and must not need network or credentials.
- The MCP server's stdout is the protocol stream: log to stderr only, never `print` in server code.
