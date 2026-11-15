# SportsBet tools

Two command line tools live here:

- **sharpbet** finds sportsbook prices that beat the sharp market's fair line and sizes the top 3.
- **nflmodel** predicts the moneyline, spread and total for every NFL game. See [NFL prediction model](#nfl-prediction-model).

# sharpbet

A command line tool that finds sports bets priced better than the sharp market says they should be,
ranks them by edge, and picks the top 3 with a stake sized for your bankroll.

It is a price-shopping tool, not a prediction model. Nothing here guarantees a win. Bet only what you
can afford to lose, check that sports betting is legal where you are, and if gambling stops being fun
call 1-800-GAMBLER.

## How it works

1. **Get the sharp line.** Pinnacle takes large bets from professional bettors, so its prices are the
   best public estimate of the true odds. The tool pulls Pinnacle and your sportsbooks from
   [The Odds API](https://the-odds-api.com) for moneylines, spreads and totals.
2. **Remove the vig.** Pinnacle's prices include its margin. The tool strips it two ways
   (multiplicative and power) and keeps the lower win probability for each side, so an edge has to
   survive both.
3. **Find the edge.** For each of your books, at the same line as Pinnacle,
   `edge = fair win probability x decimal price - 1`. Plays need at least a 2% edge.
4. **Throw out bad data.** Quotes older than 20 minutes, games already started, odds longer than +500
   and edges above 15% (almost always a stale or mistaken line) are ignored.
5. **Rank and pick.** The best price per game is kept (bets on one game are correlated), games are
   ranked by edge, and the top 3 are chosen. Games you already have an open bet on are skipped.
6. **Size the bet.** Stake is quarter Kelly: `bankroll x 0.25 x edge / (price - 1)`, capped at 5% of
   bankroll per bet and 15% of bankroll staked per day.

On a $100 bankroll a typical 2 to 4% edge gives a stake of about $0.50 to $1.50. That is what
disciplined sizing looks like at this bankroll. `--kelly 0.5` doubles it, with bigger swings.

## Setup

Needs Python 3.10 or newer, and no other dependencies.

```
cd sportsbet
pip install -e .
sharpbet scan --sample          # made-up odds, no key needed
```

For live odds, get a free key at https://the-odds-api.com (500 requests a month) and set it:

```
export ODDS_API_KEY=your_key
sharpbet scan
```

A default scan covers NFL, college football, NBA, college basketball and NHL and costs 15 requests,
so the free tier allows about one scan a day. Use `--sports basketball_nba` to scan less.

## Daily use

```
sharpbet scan                   # see the top 3
sharpbet scan --log             # same, and record them as bets you placed
sharpbet settle 1 win           # win, loss or push, by bet number
sharpbet report                 # bankroll, record, open bets
```

Bets are kept in `picks.csv` in the current folder (change with `--ledger`). The bankroll used for
sizing is the starting bankroll (`--bankroll`, default 100) plus settled profit and loss, so stakes
shrink after losses and grow after wins.

Useful `scan` options, all shown by `sharpbet scan --help`:

| Option | Default | Meaning |
|---|---|---|
| `--books` | `fanduel,fanatics` | Books you can bet at, by Odds API key. As of November 2026 the API returned no Fanatics odds, so in practice this is FanDuel only |
| `--sports` | NFL, NCAAF, NBA, NCAAB, NHL | Odds API sport keys |
| `--min-ev` | 2 | Minimum edge, percent |
| `--kelly` | 0.25 | Kelly multiplier |
| `--max-bet` | 5 | Cap per bet, percent of bankroll |
| `--daily-cap` | 15 | Cap on stakes per day, percent of bankroll |
| `--top` | 3 | Number of picks |

## Limits to know about

- The odds can move between the scan and your bet. If the price is worse than shown, skip it.
- An edge is an average over many bets. Expect losing streaks; a 48% bet loses five in a row about 4%
  of the time.
- Books limit accounts that win this way.
- Line movement signals and closing line value are not tracked yet. Both need odds history.

# NFL prediction model

`nflmodel` predicts every NFL game's winner, margin and total points without ever looking at a
betting line, then compares its numbers with the line to give a moneyline, spread and total pick.

```
pip install -e ".[model]"
nflmodel                       # the next unplayed week
nflmodel --season 2026 --week 11 --csv week11.csv
```

It downloads game results and team stats from [nflverse](https://github.com/nflverse) into
`~/.cache/nflmodel` and refreshes them when they are more than three hours old.

## What is in the model

- **Margin** (`margin_model.py`): margin-based Elo, opponent-adjusted passing and rushing EPA with
  recency decay, a starting quarterback rating that travels with the quarterback, home field that
  drifts over time, and rest. A ridge blend is refit each season on earlier seasons only.
- **Total** (`totals_model.py`): opponent-adjusted points, offensive and defensive EPA per play, pace,
  quarterback, roof, stadium wind climatology and divisional games, again fit only on the past.
- **Card** (`card.py`): win probability from a normal distribution around the predicted margin
  (SD 13.3), predicted score from margin and total, and the spread and total side the model favors.

## How good it is, honestly

Walk-forward test on 4,363 games, 2010 to 2025. The model never saw a result before predicting it.

| | Model | Closing line |
|---|---|---|
| Margin, average miss (points) | 10.30 | 10.05 |
| Total, average miss (points) | 10.59 | 10.47 |
| Straight-up winners picked | 64.9% | 66.4% |
| Home win probability, Brier score (lower is better) | 0.2174 | 0.2111 |
| Against the spread, every game | 2100-2154 (49.4%) | break-even is 52.4% |
| Over/under, every game | 2219-2099 (51.4%) | break-even is 52.4% |

The model picks winners a little worse than the market, and it does not beat the closing line. No
threshold of disagreement with the line produced a win rate that clears 52.4% beyond what luck
explains, and betting every spread and total at -110 would have lost about 4% of everything staked. When the model and
the line are combined to predict the result, the model gets a weight of about 0.1 and the line 0.9,
which is why the "chance" shown on each spread and total pick stays close to 50%. The same holds for
moneylines: the market's win probability is the more accurate one, so a gap between the model and the
market is not an edge.

Use the predictions as an informed opinion on each game. They are not evidence of a betting edge.

The research write-ups and the scripts that reproduce every number are in `research/margin` and
`research/totals`. Run them from their folder as `python3 backtest.py ~/.cache/nflmodel/`.

## Known limits

- The starting quarterback is taken from nflverse. For games where it is not announced yet, the
  team's last starter is assumed and the card says so. Other injuries are not modeled.
- Lines in nflverse are closing lines for past games and current lines for upcoming games. They may
  differ from your sportsbook's.
- Weather on game day is not used, only each stadium's typical wind by month. Retractable-roof
  stadiums are always treated as closed.

An independent audit reproduced every number above with its own scoring code and found no look-ahead
leakage: predictions do not change when later games are removed or scrambled, and betting lines have
no influence on them.

## Tests

```
pip install -e ".[dev]"
python -m pytest -q
```
