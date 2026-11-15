"""Pick the top plays and size them with capped fractional Kelly."""
from __future__ import annotations

import math
from dataclasses import dataclass

from .scan import Play


@dataclass(frozen=True)
class Pick:
    play: Play
    stake: float


def select_and_size(
    plays: list[Play],
    bankroll: float,
    *,
    top_n: int = 3,
    kelly_multiplier: float = 0.25,
    max_bet_frac: float = 0.05,
    daily_cap_frac: float = 0.15,
    staked_today: float = 0.0,
    skip_events: set[str] | None = None,
    min_stake: float = 0.10,
) -> list[Pick]:
    """Take the best play per game, ranked by EV, and size each one.

    Stake = bankroll x kelly_multiplier x full Kelly, capped per bet and by what is left of the daily
    exposure cap. One play per game, because bets on the same game are correlated.
    """
    remaining = max(0.0, bankroll * daily_cap_frac - staked_today)
    seen = set(skip_events or ())
    picks: list[Pick] = []
    for play in sorted(plays, key=lambda p: p.ev, reverse=True):
        if len(picks) >= top_n:
            break
        if play.event_id in seen:
            continue
        stake = min(bankroll * kelly_multiplier * play.kelly, bankroll * max_bet_frac, remaining)
        stake = math.floor(stake * 100 + 1e-9) / 100
        if stake < min_stake:
            continue
        seen.add(play.event_id)
        remaining -= stake
        picks.append(Pick(play, stake))
    return picks
