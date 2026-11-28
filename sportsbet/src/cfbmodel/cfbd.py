"""CollegeFootballData (CFBD) API client with a permanent on-disk JSON cache.

The API key is read from the CFBD_API_KEY environment variable on each network call and is
never written to disk, logged or put in an error message. The free tier allows 1,000 calls a
month, so every response is cached under ``data_dir/cfbd/<endpoint>/<sorted params>.json`` and
reused forever; pass ``live=True`` for the current season to refetch a copy older than 6 hours.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_URL = "https://api.collegefootballdata.com"
KEY_ENV = "CFBD_API_KEY"
DEFAULT_DATA_DIR = Path("~/.cache/cfbmodel").expanduser()
LIVE_MAX_AGE_S = 6 * 3600
RETRY_WAITS_S = (2, 4, 8, 16)  # waits before retries 1-4 after HTTP 429 or 5xx
TIMEOUT_S = 60

_calls = 0


class CFBDError(Exception):
    """A CFBD request failed. The message is one line and never contains the key."""


class MissingKey(CFBDError):
    """CFBD_API_KEY is unset, empty, or rejected by the API."""


def calls_made() -> int:
    """Number of network requests made to CFBD by this process (retries included)."""
    return _calls


def cache_path(endpoint: str, params: dict, data_dir: Path) -> Path:
    endpoint = endpoint.strip("/")
    name = "_".join(f"{k}={params[k]}" for k in sorted(params)) or "all"
    return Path(data_dir) / "cfbd" / endpoint / f"{name}.json"


def _key() -> str:
    key = os.environ.get(KEY_ENV, "").strip()
    if not key:
        raise MissingKey(f"{KEY_ENV} is not set: export your CollegeFootballData API key as {KEY_ENV}")
    return key


def _fetch(endpoint: str, params: dict):
    global _calls
    key = _key()
    query = urllib.parse.urlencode(sorted((k, str(v)) for k, v in params.items()))
    url = f"{BASE_URL}/{endpoint.strip('/')}" + (f"?{query}" if query else "")
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {key}", "Accept": "application/json"})
    for attempt in range(len(RETRY_WAITS_S) + 1):
        _calls += 1
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise MissingKey(f"CFBD rejected the key in {KEY_ENV} (HTTP {e.code}); check that it is correct") from None
            if (e.code == 429 or e.code >= 500) and attempt < len(RETRY_WAITS_S):
                time.sleep(RETRY_WAITS_S[attempt])
                continue
            raise CFBDError(f"CFBD request for {endpoint} failed with HTTP {e.code} "
                            f"after {attempt + 1} attempt(s)") from None
        except urllib.error.URLError as e:
            raise CFBDError(f"CFBD request for {endpoint} failed: {e.reason}") from None
    raise AssertionError("unreachable")


def get(endpoint: str, params: dict, data_dir: Path, *, live: bool = False) -> list[dict]:
    """Return the JSON for ``endpoint`` with ``params``, from the cache when possible.

    Without ``live`` a cached copy is used however old it is. With ``live=True`` the cache is
    refetched when older than 6 hours. A failed request leaves any existing cache untouched.
    """
    path = cache_path(endpoint, params, data_dir)
    if path.exists() and not (live and time.time() - path.stat().st_mtime > LIVE_MAX_AGE_S):
        return json.loads(path.read_text())
    data = _fetch(endpoint, params)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".part")
    tmp.write_text(json.dumps(data))
    tmp.replace(path)
    return data
