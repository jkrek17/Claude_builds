"""Vendored data layer from spilchen/yahoo_fantasy_mcp (MIT, see LICENSE).

Only ``tools.py`` is vendored, unmodified. Upstream's ``server.py`` targets the
MCP Python SDK 1.x low-level API, which no longer exists in SDK 2.x, so
``fantasy_coach.server`` re-exposes these tools on ``MCPServer`` instead.
"""

from .tools import YahooFantasyTools

__all__ = ["YahooFantasyTools"]
