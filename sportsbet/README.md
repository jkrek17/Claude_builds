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

The `site` and `publish` jobs in `.github/workflows/sportsbet.yml` rerun the model and publish the page
to GitHub Pages on Tuesday, Wednesday, Thursday, Saturday and Sunday mornings US Eastern, and whenever a
change to `sportsbet/` lands on `main`. This needs Pages turned on once: repository Settings, Pages, Source
"GitHub Actions". Wind forecasts are only shown for the run that made them, so a finished game shows
no wind.

## The weekly loop

`nflmodel-loop` saves each week's picks before kickoff, grades them when the week is over, tests a fixed
list of alternative settings, and writes a report. The `loop` job in `.github/workflows/sportsbet.yml`
runs it on every scheduled slot; it does whatever the calendar and the saved state call for, so running
it twice changes nothing.

```
nflmodel-loop run --state-dir STATE       # check, freeze, grade, test settings, report
nflmodel-loop status --state-dir STATE    # what the last run did
nflmodel --json site.json --state-dir STATE   # the page then shows the saved-pick record
```

**What it keeps.** The state lives on the `sportsbet-data` branch, never on `main`:

| File | What it is |
|---|---|
| `picks.csv` | Every pick as it stood when saved. Append-only, and each row carries a hash of the row before it, so a pick changed after the fact is detected and the run fails. |
| `grades.csv` | Each pick graded against the final score and the closing line. Rebuilt from `picks.csv` every run. |
| `REPORT.md`, `reports/` | The weekly report, latest and one per graded week. |
| `challengers/`, `promotions.json` | Each week's test of the alternative settings and the history of proposals. |

**When picks are saved.** Thursday morning's run saves the pick of record for every game of the week,
before the first kickoff. Tuesday and Wednesday save an early look, graded on its own line. A week is
graded only when every game in it has a final score.

**What the report says.** Health checks first, then last week's games, the season record with the range
luck alone would produce, error against the closing line, whether the line moved toward the picks after
they were saved (the fastest honest sign of skill, about 80 times quicker than a win rate), and paper
bets of one unit at -110. One week of results says nothing about a model, and the report says so.

**How the model is allowed to change.** The ratings and the blend already learn from every result. The
loop only retunes settings, and only like this:

- The candidates are fixed in `src/nflmodel/challengers.json`: the current settings with one value moved
  one step. The same 36 are tested every week, so repeated looks add no new guesses.
- A candidate is proposed when its squared error beats the current model's on every game since 2010
  with a season-clustered one-sided p of 0.0025 or less, by at least 0.1%, in at least 65% of seasons, in
  both halves of the window and in the latest three seasons, four weekly looks in a row.
- A proposal is a draft pull request with the evidence. Nothing merges by itself. At most one change per
  model per season, and a merged change is proposed for reverting if its advantage fades.
- Records against the spread, profit, closing-line results, and any single week, month or season never
  trigger a change. The weights the card gives the model against the line are never tuned.

Replayed over 2014 to 2025 with no real effect present, this rule made a false change in about 4% of
seasons; "switch to whichever setting is ahead" did so every season. On real history it would have made
one change in twelve years, worth 0.006 points of error. Expect the loop to change the model rarely and
never to create a betting edge. Its value is the honest record, and that it stops the model being
changed for bad reasons.

**One-time setup.** For the loop to open its own pull requests, turn on repository Settings, Actions,
General, "Allow GitHub Actions to create and approve pull requests". Without it the proposal is written
to an issue with a link that opens the pull request.

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

## Tests

```
pip install -e ".[dev]"
python -m pytest -q
```
