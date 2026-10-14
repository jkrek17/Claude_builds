"""Yahoo OAuth sign-in that works without a local browser or an interactive terminal.

Two ways to end up with the token file the server reads (``YAHOO_OAUTH_FILE``):

* ``fantasy-coach auth``: interactive, on your own computer.
* ``fantasy-coach auth-url`` then ``fantasy-coach auth-code <code>``: for cloud sessions. Approve
  the URL on any device, then hand the ``code=`` value back.

In a cloud container the token file disappears with the container, so the server can also rebuild
it from ``YAHOO_CLIENT_ID``, ``YAHOO_CLIENT_SECRET`` and ``YAHOO_REFRESH_TOKEN`` environment
variables (``ensure_oauth_file``).
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from typing import Callable, Mapping
from urllib.parse import parse_qs, urlencode, urlparse

import requests

AUTHORIZE_URL = "https://api.login.yahoo.com/oauth2/request_auth"
TOKEN_URL = "https://api.login.yahoo.com/oauth2/get_token"
# Yahoo no longer accepts "oob" for new apps; https://localhost is the usual choice.
DEFAULT_REDIRECT_URI = "https://localhost:8080"


def redirect_uri(env: Mapping[str, str] = os.environ) -> str:
    return env.get("YAHOO_REDIRECT_URI") or DEFAULT_REDIRECT_URI


def authorize_url(client_id: str, redirect: str) -> str:
    return f"{AUTHORIZE_URL}?{urlencode({'client_id': client_id, 'redirect_uri': redirect, 'response_type': 'code'})}"


def extract_code(text: str) -> str:
    """Accept the bare code or the whole https://localhost:8080/?code=... address."""
    text = text.strip()
    if "code=" in text:
        code = parse_qs(urlparse(text).query).get("code") or parse_qs(text.split("?", 1)[-1]).get("code")
        if code:
            return code[0]
    return text


def exchange_code(client_id: str, client_secret: str, code: str, redirect: str,
                  post: Callable[..., requests.Response] = requests.post) -> dict:
    resp = post(TOKEN_URL, auth=(client_id, client_secret), timeout=30,
                data={"grant_type": "authorization_code", "code": extract_code(code), "redirect_uri": redirect})
    if resp.status_code != 200:
        try:
            detail = resp.json().get("error_description") or resp.json().get("error")
        except ValueError:
            detail = resp.text[:200]
        raise RuntimeError(f"Yahoo rejected the sign-in code ({resp.status_code}): {detail}. "
                           "Codes are single-use and expire after a few minutes; get a fresh one.")
    tokens = resp.json()
    return {"access_token": tokens["access_token"], "refresh_token": tokens["refresh_token"],
            "token_type": tokens.get("token_type", "bearer"), "token_time": time.time()}


def write_private(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(text)
    os.chmod(path, 0o600)


def write_oauth_file(path: Path, client_id: str, client_secret: str, redirect: str, tokens: dict) -> None:
    """Write the file in the format yahoo_oauth reads (and refreshes in place)."""
    write_private(path, json.dumps({"consumer_key": client_id, "consumer_secret": client_secret,
                                    "callback_uri": redirect, **tokens}, indent=2))


def ensure_oauth_file(path: Path, env: Mapping[str, str] = os.environ) -> bool:
    """Create the token file from environment variables if it's missing. Returns whether it exists.

    The access token is written as already expired, so yahoo_oauth refreshes it on first use.
    """
    if path.exists():
        return True
    client_id, secret, refresh = (env.get(k) for k in ("YAHOO_CLIENT_ID", "YAHOO_CLIENT_SECRET", "YAHOO_REFRESH_TOKEN"))
    if not (client_id and secret and refresh):
        return False
    write_oauth_file(path, client_id, secret, redirect_uri(env),
                     {"access_token": "expired", "refresh_token": refresh, "token_type": "bearer", "token_time": 0})
    return True
