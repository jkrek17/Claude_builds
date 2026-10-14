"""Command line: ``fantasy-coach auth | leagues | serve``."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys

from .server import create_server, oauth_file_path


def auth(redirect_uri: str) -> None:
    """One-time Yahoo sign-in. Writes tokens to the OAuth file; the server refreshes them after that.

    This has to run in a terminal: Yahoo shows a code in the browser that you paste back here. The MCP
    server itself can't prompt, because its stdin is the MCP connection.
    """
    path = oauth_file_path()
    client_id = os.environ.get("YAHOO_CLIENT_ID") or input("Yahoo app Client ID (Consumer Key): ").strip()
    client_secret = os.environ.get("YAHOO_CLIENT_SECRET") or getpass.getpass("Yahoo app Client Secret: ").strip()
    if not client_id or not client_secret:
        sys.exit("Client ID and secret are required. Create an app at https://developer.yahoo.com/apps/.")

    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({"consumer_key": client_id, "consumer_secret": client_secret, "callback_uri": redirect_uri}, f)

    from yahoo_oauth import OAuth2

    print("\nA browser window will open (or copy the URL below). Approve access, then paste the code shown.")
    if redirect_uri != "oob":
        print(f"Yahoo will redirect to {redirect_uri}?code=...; paste just the value after 'code='.")
    OAuth2(None, None, from_file=str(path))
    os.chmod(path, 0o600)
    print(f"\nSigned in. Tokens saved to {path}")
    leagues()


def leagues(year: int | None = None) -> None:
    from .vendor.yahoo_fantasy_mcp import YahooFantasyTools

    result = YahooFantasyTools(oauth2_file=str(oauth_file_path())).get_all_leagues("nfl", year)
    if result.get("error"):
        sys.exit(f"Couldn't list leagues: {result['error']}")
    if not result["leagues"]:
        print(f"No NFL leagues found for {result['year']}.")
        return
    print(f"\nYour NFL leagues for {result['year']}:")
    for lg in result["leagues"]:
        print(f"  {lg['league_id']}  {lg['name']}")
    print("\nSet YAHOO_LEAGUE_ID to one of these in your MCP config.")


def main() -> None:
    parser = argparse.ArgumentParser(prog="fantasy-coach", description="Read-only Yahoo Fantasy Football MCP server")
    sub = parser.add_subparsers(dest="cmd")
    p_auth = sub.add_parser("auth", help="sign in to Yahoo (one time, in a terminal)")
    p_auth.add_argument("--redirect-uri", default="oob",
                        help="must match your Yahoo app's Redirect URI (default: oob)")
    p_leagues = sub.add_parser("leagues", help="list your NFL leagues and their IDs")
    p_leagues.add_argument("--year", type=int)
    sub.add_parser("serve", help="run the MCP server on stdio (default)")
    args = parser.parse_args()

    if args.cmd == "auth":
        auth(args.redirect_uri)
    elif args.cmd == "leagues":
        leagues(args.year)
    else:
        create_server().run("stdio")


if __name__ == "__main__":
    main()
