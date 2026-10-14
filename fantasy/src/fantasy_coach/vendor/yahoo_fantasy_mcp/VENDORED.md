# Vendored: spilchen/yahoo_fantasy_mcp

- Upstream: https://github.com/spilchen/yahoo_fantasy_mcp
- Commit: 133a45ab57738dad146dcb6b5557f70f8cf7d4e6 (2026-03-15)
- License: MIT (copied to `LICENSE` in this directory)
- Files taken: `yahoo_fantasy_mcp/tools.py`, unmodified.
- Not taken: `server.py`, `__main__.py`, `stdio_transport.py`. They use the MCP SDK 1.x
  low-level `Server` decorators (`list_resources`, `list_tools`, `call_tool`) that were removed
  in SDK 2.x. `fantasy_coach/server.py` registers the same tools on `MCPServer`.

To update, copy the new `tools.py` over this one, bump the commit above, and run
`pytest` from `fantasy/`.
