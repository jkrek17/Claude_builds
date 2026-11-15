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
| `--books` | `draftkings,fanduel,betmgm,williamhill_us,betrivers` | Books you can bet at (`williamhill_us` is Caesars) |
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

## Tests

```
pip install -e ".[dev]"
python -m pytest -q
```
