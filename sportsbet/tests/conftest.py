import pytest


@pytest.fixture(autouse=True)
def _no_espn(monkeypatch):
    """Tests never reach ESPN: a test that wants scoreboard data patches ``schedule._fetch`` itself."""
    from cfbmodel import schedule

    def offline(params):
        raise OSError("ESPN is not reachable from tests")
    monkeypatch.setattr(schedule, "_fetch", offline)
