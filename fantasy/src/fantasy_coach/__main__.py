"""Command line: ``fantasy-coach auth | auth-url | auth-code | leagues | serve``."""

from __future__ import annotations

import argparse
import getpass
import os
import sys
import webbrowser
from pathlib import Path

from . import credentials
from .server import create_server, oauth_file_path


def _client(prompt: bool) -> tuple[str, str]:
    client_id = os.environ.get("YAHOO_CLIENT_ID") or (input("Yahoo app Client ID: ").strip() if prompt else "")
    secret = os.environ.get("YAHOO_CLIENT_SECRET") or (getpass.getpass("Yahoo app Client Secret: ").strip() if prompt else "")
    if not client_id or not secret:
        sys.exit("Set YAHOO_CLIENT_ID and YAHOO_CLIENT_SECRET (from https://developer.yahoo.com/apps/).")
    return client_id, secret


def _finish(client_id: str, secret: str, code: str, redirect: str, refresh_token_file: str | None) -> None:
    tokens = credentials.exchange_code(client_id, secret, code, redirect)
    path = oauth_file_path()
    credentials.write_oauth_file(path, client_id, secret, redirect, tokens)
    print(f"Signed in. Tokens saved to {path}")
    if refresh_token_file:
        # Written to a private file, never printed, so it stays out of logs and chat transcripts.
        credentials.write_private(Path(refresh_token_file).expanduser(), tokens["refresh_token"] + "\n")
        print(f"Refresh token saved to {refresh_token_file}. Store it as YAHOO_REFRESH_TOKEN to stay signed in.")
    leagues()


def auth(redirect: str) -> None:
    """Interactive sign-in on your own computer."""
    client_id, secret = _client(prompt=True)
    url = credentials.authorize_url(client_id, redirect)
    print(f"\nOpen this link, sign in and click Agree:\n\n  {url}\n")
    webbrowser.open(url)
    print(f"Yahoo then sends you to {redirect}/?code=... (the page won't load; that's fine).")
    code = input("Paste the code, or that whole address: ")
    _finish(client_id, secret, code, redirect, None)


def leagues(year: int | None = None) -> None:
    from .vendor.yahoo_fantasy_mcp import YahooFantasyTools

    path = oauth_file_path()
    if not credentials.ensure_oauth_file(path):
        sys.exit("Not signed in. Run `fantasy-coach auth` first.")
    result = YahooFantasyTools(oauth2_file=str(path)).get_all_leagues("nfl", year)
    if result.get("error"):
        sys.exit(f"Couldn't list leagues: {result['error']}")
    if not result["leagues"]:
        print(f"No NFL leagues found for {result['year']}.")
        return
    print(f"\nYour NFL leagues for {result['year']}:")
    for lg in result["leagues"]:
        print(f"  {lg['league_id']}  {lg['name']}")
    print("\nSet YAHOO_LEAGUE_ID to one of these (optional if you only have one).")


def main() -> None:
    parser = argparse.ArgumentParser(prog="fantasy-coach", description="Read-only Yahoo Fantasy Football MCP server")
    parser.add_argument("--redirect-uri", default=credentials.redirect_uri(),
                        help=f"must match your Yahoo app's Redirect URI (default: $YAHOO_REDIRECT_URI or "
                             f"{credentials.DEFAULT_REDIRECT_URI})")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("auth", help="sign in to Yahoo interactively (on your computer)")
    sub.add_parser("auth-url", help="print the Yahoo sign-in link (for cloud sessions)")
    p_code = sub.add_parser("auth-code", help="finish sign-in with the code from the auth-url redirect")
    p_code.add_argument("code", help="the code=... value, or the whole redirect address")
    p_code.add_argument("--refresh-token-file", help="also write the refresh token (only) to this private file")
    p_leagues = sub.add_parser("leagues", help="list your NFL leagues and their IDs")
    p_leagues.add_argument("--year", type=int)
    sub.add_parser("serve", help="run the MCP server on stdio (default)")
    args = parser.parse_args()

    if args.cmd == "auth":
        auth(args.redirect_uri)
    elif args.cmd == "auth-url":
        client_id = os.environ.get("YAHOO_CLIENT_ID") or sys.exit("Set YAHOO_CLIENT_ID first.")
        print(credentials.authorize_url(client_id, args.redirect_uri))
    elif args.cmd == "auth-code":
        _finish(*_client(prompt=False), args.code, args.redirect_uri, args.refresh_token_file)
    elif args.cmd == "leagues":
        leagues(args.year)
    else:
        create_server().run("stdio")


if __name__ == "__main__":
    main()
