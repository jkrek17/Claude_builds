# cfb.json contract (written by `cfbmodel site --json PATH`, read by site/src/cfb.js)

```json
{
  "generated_at": "2026-11-28T15:00:00Z",
  "season": 2026, "week": 14,
  "weeks": [13, 14],
  "status": {"passed": null, "note": "Research only: the model has not passed its backtest."},
  "games": [{
    "game_id": 401752000, "week": 14, "start": "2026-11-28T17:00:00Z",
    "home": "Ohio State", "away": "Michigan", "home_id": 194, "away_id": 130,
    "home_conf": "Big Ten", "away_conf": "Big Ten", "neutral": false,
    "spread_line": -7.5, "total_line": 44.5, "line_provider": "FanDuel", "line_seen_at": "2026-11-28T14:20:05Z",
    "model_margin": 9.8, "model_total": 41.2,
    "home_cover_prob": 0.56, "over_prob": 0.41,
    "pick_spread": "home", "pick_total": "under",
    "edge_spread": 0.07, "edge_total": 0.12, "bet_spread": false, "bet_total": false,
    "final": false, "home_score": null, "away_score": null, "ats_hit": null, "ou_hit": null
  }],
  "rankings": [{"rank": 1, "team": "Ohio State", "team_id": 194, "conf": "Big Ten",
                "off": 0.21, "def": -0.15, "net": 18.4}],
  "backtest": {"tune_seasons": "2014-2019, 2021-2022", "scored_seasons": "2023-2025", "passed": null,
    "markets": {"spread": {"n": 0, "win_rate": null, "roi": null, "clv_mean": null, "clv_t": null,
                           "by_season": [{"season": 2023, "n": 0, "win_rate": null, "roi": null}]},
                "total": {"n": 0, "win_rate": null, "roi": null, "clv_mean": null, "clv_t": null, "by_season": []}}}
}
```
Spread sign: home perspective, negative = home favored. model_margin = home minus away points.
Team logos: CFBD team ids equal ESPN ids: https://a.espncdn.com/i/teamlogos/ncaa/500/{id}.png
`net` is predicted points margin vs an average FBS team on a neutral field. Any number may be null.
Lines: `spread_line`/`total_line` are the latest pregame line a run saw (ESPN's state "pre"), kept once
the game kicks off; they are the line at run time, not necessarily the close. `line_seen_at` (optional;
may be absent or null) is when that line was last seen, ISO UTC; null for a line taken from the
play-by-play file or with no saved line.
Offseason: from July until the new season's play-by-play exists, `season` is the last season with data
(its final rankings and the model's record) and `status.note` starts "The <season> season has not started."
When there is no data at all `cfbmodel site` exits non-zero and writes nothing.
