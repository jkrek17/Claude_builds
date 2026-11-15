# SportsBet tools

Three command line tools live here:

- **sharpbet** finds sportsbook prices that beat the sharp market's fair line and sizes the top 3.
- **nflmodel** predicts the moneyline, spread and total for every NFL game. See [NFL prediction model](#nfl-prediction-model).
- **nflprops** predicts each player's passing, rushing and receiving line as a full range of outcomes. See [NFL player props](#nfl-player-props).

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

- **Margin** (`margin_model.py`, `qb_form.py`): margin-based Elo, opponent-adjusted passing and
  rushing EPA with recency decay, a starting quarterback rating that travels with the quarterback,
  the quarterback's own rolling EPA per dropback, a flag for an in-season backup, home field that
  drifts over time, and rest. A ridge blend is refit each season on earlier seasons only.
- **Total** (`totals_model.py`, `weather.py`): opponent-adjusted points, offensive and defensive EPA
  per play, pace, quarterback, roof, wind and divisional games, again fit only on the past. The wind
  effect is learned from the wind recorded at past outdoor games (about a quarter point less scoring
  per mph). For an upcoming outdoor game the model uses the National Weather Service hourly forecast
  at kickoff, and falls back to the stadium's typical wind for the month when no forecast is
  available (more than a week out, a neutral site, or `--no-weather`).
- **Card** (`card.py`, `scoredist.py`): win probability from a normal distribution around the
  predicted margin (SD 13.3), predicted score from margin and total, and the spread and total side
  the model favors. Spread and total chances come from a distribution re-weighted by how often each
  number has actually come up, so a line on 3 or 7 shows its real push chance.

## How good it is, honestly

Walk-forward test on 4,363 games, 2010 to 2025. The model never saw a result before predicting it.
`python3 research/backtest_v2.py ~/.cache/nflmodel` reproduces these; its output is saved in
`research/backtest_v2_output.txt`.

| | Model | Closing line |
|---|---|---|
| Margin, average miss (points) | 10.27 | 10.05 |
| Total, average miss (points) | 10.59 | 10.47 |
| Straight-up winners picked | 65.2% | 66.4% |
| Home win probability, Brier score (lower is better) | 0.217 | 0.211 |
| Against the spread, every game | 2123-2131 (49.9%) | break-even is 52.4% |
| Over/under, every game | 2214-2104 (51.3%) | break-even is 52.4% |

The model picks winners a little worse than the market, and it does not beat the closing line. No
threshold of disagreement with the line produced a win rate that clears 52.4% beyond what luck
explains, and betting every spread and total at -110 would have lost about 4% of everything staked.
When the model and the line are combined to predict the result, the model gets a weight of about 0.1
and the line 0.9, which is why the "chance" shown on each spread and total pick stays close to 50%.
The same holds for moneylines: the market's win probability is the more accurate one, so a gap
between the model and the market is not an edge.

Use the predictions as an informed opinion on each game. They are not evidence of a betting edge.

### What a review of extra data found

Four reviews tested about 100 candidate features and methods on the same walk-forward basis. Three
had a real effect and are now in the model:

- **Quarterback form.** Lowered the margin miss from 10.30 to 10.27 and improved 13 of 16 seasons.
- **Wind.** With the wind actually observed at each game, which is the best a forecast could do, the
  total miss falls from 10.59 to 10.57 and the over/under record rises to 2233-2085 (51.7%). In
  outdoor games with 15 mph or more of wind the under won 194 of 354 (54.8%, standard error 2.7
  points): 52.8% in 2010 to 2017 and 57.2% in 2018 to 2025. That is suggestive, not proven, and a
  forecast will do somewhat worse than the observed wind used here. The card flags these games.
- **Key numbers.** Favorites lined near 3 win by exactly 3 in 8.6% of games; a normal curve says
  3.1% and the re-weighted distribution 9.0%.

What made no measurable difference: injury report counts, snap-weighted injuries, offensive line
continuity, play-by-play filters (success rate, garbage time, turnover luck, red zone), travel and
time zones, bye weeks, primetime, referees, coaches, and multi-model or perturbed-parameter
ensembles. A 40-member perturbed ensemble moved predictions by under a point and its spread had no
relationship with forecast error.

The earlier research write-ups for each model are in `research/margin` and `research/totals`. They
describe the models before quarterback form and observed wind were added.

## The web page

`site/` is a small static page for a phone: the week's card, the season record and the backtest. It is
built with [Tailwind CSS](https://github.com/tailwindlabs/tailwindcss) and
[daisyUI](https://github.com/saadeghi/daisyui), with [Chart.js](https://github.com/chartjs/Chart.js)
for the one chart and [Barlow Condensed](https://github.com/jpt/barlow) for titles and scores. Team logos are NFL trademarks; they are loaded from ESPN's image server and are not
stored in this repository. The page holds no model code. It reads `data/site.json`, which the model writes:

```
nflmodel --json site/dist/data/site.json     # every week's card, the record and the backtest
cd site && npm ci && npm run build            # writes the page into site/dist
python3 -m http.server -d dist                # open http://localhost:8000
```

The Props tab reads `data/props.json` (written by `nflprops --json site/dist/data/props.json`) the first
time it is opened. Each row carries the model's full distribution for one player and prop, so the page works
out the chance of over, under and push for any line typed in, in the browser. There are no sportsbook prop
lines in the data. If the props file is missing the tab says so.

The `site` and `publish` jobs in `.github/workflows/sportsbet.yml` rerun the model and publish the page
to GitHub Pages on Tuesday, Thursday, Saturday and Sunday mornings US Eastern, and whenever a change to
`sportsbet/` lands on `main`. This needs Pages turned on once: repository Settings, Pages, Source
"GitHub Actions". Wind forecasts are only shown for the run that made them, so a finished game shows
no wind.

## Known limits

- The starting quarterback is taken from nflverse. For games where it is not announced yet, the
  team's last starter is assumed and the card says so. Other injuries are not modeled.
- Lines in nflverse are closing lines for past games and current lines for upcoming games. They may
  differ from your sportsbook's.
- Wind is the only weather input. Temperature and rain are not used. Retractable-roof stadiums are
  always treated as closed. Forecasts come from the US National Weather Service, so games abroad
  use climatology.
- The first run downloads about 250 MB of nflverse files.

An independent audit of the first version reproduced its numbers with its own scoring code and found no look-ahead
leakage: predictions do not change when later games are removed or scrambled, and betting lines have
no influence on them.

# NFL player props

`nflprops` predicts, for every likely player in a week's games, passing yards, passing TDs, pass
attempts, completions, rushing yards, receiving yards, receptions and the chance of a touchdown. It
never sees a sportsbook prop price. Each prediction is a whole distribution, so you can ask for the
chance of clearing any number.

```
pip install -e ".[model]"
nflprops                                   # the next unplayed week
nflprops --week 11 --csv props.csv --json props.json
nflprops --player "nico collins" --prop rec_yds --line 62.5
```

The card shows each player's median and the range the model expects 8 times in 10. The CSV adds the
"even line" (the half-point number the model thinks is a coin flip) and a ladder of chances (60+,
70+, ...). The JSON holds the full distribution for the web page: quantiles `q` at the top-level
`probs` for yardage props, and `pmf` (the chance of exactly 0, 1, 2, ...) for count props.

## What is in the model

- **Expected value** (`props.py`): a ridge regression per prop and position, refit on earlier games
  only. Inputs are the player's own short and long memory of volume and production, his share of the
  team's targets and carries, his snap share last game, the opponent's recent defence, the game's
  spread and total (the posted line, or the `nflmodel` prediction when none is posted), and the
  starting quarterback's level for his receivers.
- **Injuries and trades** (`availability.py`): the weekly roster and the official injury report pick
  the players. Anyone on injured reserve, cut, or listed Out or Doubtful is dropped, a traded player
  appears for his new team with his memory of the old role cut in half, and the targets and carries
  of whoever is missing shift to the teammates who remain ("vacated volume").
- **Spread** (`props.py`): yardage props use how far results have actually landed from expectation
  for similar players; receptions use a negative binomial, passing TDs a binomial, and the chance of
  a touchdown its own logistic model.

Three expert reviews chose these pieces by walk-forward test and rejected many more; see
[research/props/README.md](research/props/README.md).

## How good it is, honestly

Walk-forward test on 2015 to 2026 (fit on earlier seasons only, from 2013).
`python3 research/props/backtest.py` reproduces it; output is in `research/props/backtest_output.txt`.
"Own average" is the player's long-run decayed average with the same spread method.

| Prop | Lines | Average miss | Own average | Seasons better (of 12) | Inside 50% band | Inside 80% band |
|---|---|---|---|---|---|---|
| Passing yards | 6,268 | 58.3 | 60.7 | 12 | 50.0% | 80.0% |
| Passing TDs | 6,268 | 0.89 | 0.92 | 12 | 49.2% | 79.7% |
| Pass attempts | 6,268 | 6.79 | 7.06 | 12 | 50.6% | 81.2% |
| Completions | 6,268 | 4.73 | 4.89 | 12 | 49.5% | 79.7% |
| Rushing yards | 9,278 | 25.2 | 26.7 | 12 | 50.9% | 80.6% |
| Receiving yards | 26,257 | 24.0 | 24.7 | 12 | 50.7% | 80.0% |
| Receptions | 26,257 | 1.70 | 1.75 | 12 | 50.3% | 80.0% |
| Anytime TD (count) | 26,126 | 0.49 | 0.50 | 12 | 50.1% | 80.3% |

When the model says a touchdown is 30 to 40% likely it has happened 36% of the time, and 10 to 20%
has happened 17% of the time (Brier 0.2007 against 0.2132 for the base rate).

What this does and does not show:

- The ranges are honest: results land inside the 80% band 80% of the time. That is the property
  needed to turn a line into a chance.
- The model is only 2 to 5% sharper than a player's own running average. Single games are mostly
  noise: a typical receiver's 80% band runs from about 10 to 100 yards.
- **Nothing here shows an edge against a sportsbook.** No prop prices were available, so the model
  has never been tested against one. Books see injuries, practice reports and matchups this model
  does not, and a gap between the model's even line and a posted line is more likely the book
  knowing something than the model being right.
- The test knows who actually played (the inactives list, out 90 minutes before kickoff). A card
  made earlier in the week knows only the roster and Friday's report, which captured
  roughly half to three quarters of the vacated-volume gain in testing, and late scratches are missed.
- The test uses closing game lines. Quarterback props lean on the line; made before a line is
  posted they lose roughly a third of their advantage over the player's own average.
- Not modelled yet: weather for passing props, how props move together within a game (needed for
  same-game parlays), first touchdown scorer, kickers and defences.

# Tests

```
pip install -e ".[dev]"
python -m pytest -q
```
