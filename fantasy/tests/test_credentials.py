import json
import os
import stat

import pytest

from fantasy_coach import __main__ as cli
from fantasy_coach import credentials as c


class FakeResponse:
    def __init__(self, status, body):
        self.status_code = status
        self._body = body
        self.text = json.dumps(body)

    def json(self):
        return self._body


def test_authorize_url_has_client_and_redirect():
    url = c.authorize_url("abc", "https://localhost:8080")
    assert url.startswith(c.AUTHORIZE_URL + "?")
    assert "client_id=abc" in url and "redirect_uri=https%3A%2F%2Flocalhost%3A8080" in url and "response_type=code" in url


@pytest.mark.parametrize("pasted", ["xyz123", " xyz123\n", "https://localhost:8080/?code=xyz123",
                                    "https://localhost:8080/?state=1&code=xyz123&x=2", "localhost:8080/?code=xyz123"])
def test_extract_code_accepts_code_or_redirect_address(pasted):
    assert c.extract_code(pasted) == "xyz123"


def test_exchange_code_posts_with_basic_auth():
    seen = {}

    def post(url, auth, data, timeout):
        seen.update(url=url, auth=auth, data=data)
        return FakeResponse(200, {"access_token": "AT", "refresh_token": "RT", "token_type": "bearer"})

    tokens = c.exchange_code("id", "secret", "https://localhost:8080/?code=CODE", "https://localhost:8080", post)
    assert seen["url"] == c.TOKEN_URL and seen["auth"] == ("id", "secret")
    assert seen["data"] == {"grant_type": "authorization_code", "code": "CODE", "redirect_uri": "https://localhost:8080"}
    assert tokens["access_token"] == "AT" and tokens["refresh_token"] == "RT" and tokens["token_time"] > 0


def test_exchange_code_explains_rejection():
    post = lambda *a, **k: FakeResponse(400, {"error": "invalid_grant", "error_description": "code expired"})  # noqa: E731
    with pytest.raises(RuntimeError, match="code expired.*fresh one"):
        c.exchange_code("id", "secret", "old", "https://localhost:8080", post)


def test_ensure_oauth_file_builds_expired_token_from_env(tmp_path):
    path = tmp_path / "cfg" / "oauth2.json"
    assert not c.ensure_oauth_file(path, {"YAHOO_CLIENT_ID": "id"})
    env = {"YAHOO_CLIENT_ID": "id", "YAHOO_CLIENT_SECRET": "sec", "YAHOO_REFRESH_TOKEN": "RT"}
    assert c.ensure_oauth_file(path, env)
    data = json.loads(path.read_text())
    assert data == {"consumer_key": "id", "consumer_secret": "sec", "callback_uri": "https://localhost:8080",
                    "access_token": "expired", "refresh_token": "RT", "token_type": "bearer", "token_time": 0}
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    # An existing file (possibly holding a newer token) is never overwritten.
    path.write_text("{}")
    assert c.ensure_oauth_file(path, {**env, "YAHOO_REFRESH_TOKEN": "other"})
    assert path.read_text() == "{}"


def test_auth_code_saves_tokens_without_printing_them(tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("YAHOO_OAUTH_FILE", str(tmp_path / "oauth2.json"))
    monkeypatch.setenv("YAHOO_CLIENT_ID", "id")
    monkeypatch.setenv("YAHOO_CLIENT_SECRET", "sec")
    monkeypatch.setattr(c, "exchange_code", lambda *a: {"access_token": "AT-secret", "refresh_token": "RT-secret",
                                                        "token_type": "bearer", "token_time": 1.0})
    monkeypatch.setattr(cli, "leagues", lambda *a: None)
    rt_file = tmp_path / "refresh.txt"
    monkeypatch.setattr("sys.argv", ["fantasy-coach", "auth-code", "CODE", "--refresh-token-file", str(rt_file)])
    cli.main()
    out = capsys.readouterr().out
    assert "RT-secret" not in out and "AT-secret" not in out
    assert rt_file.read_text().strip() == "RT-secret"
    assert stat.S_IMODE(os.stat(rt_file).st_mode) == 0o600
    assert json.loads((tmp_path / "oauth2.json").read_text())["refresh_token"] == "RT-secret"
