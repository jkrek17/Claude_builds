# Stock advisor

Plans: `PROJECT_PLAN.md` (system), `TITANS_PLAYBOOK.md` (idea generation and the weekly report). Reports land in `reports/`.

## Weekly run (what GitHub Actions does every Saturday 08:00 ET)

```bash
pip install -r stock-advisor/requirements.txt
mkdir -p /tmp/wk
python stock-advisor/scripts/weekly_report_data.py /tmp/wk     # prices, FRED, breadth, regime, screens, portfolio
python stock-advisor/scripts/tsp_dial.py /tmp/wk               # tested TSP regime + transfer instruction; updates state/
python stock-advisor/scripts/render_report.py /tmp/wk stock-advisor/reports/$(date +%F)-weekly.md
```

Set `ANTHROPIC_API_KEY` to add the Claude-written "what changed" section. Set `MAIL_USERNAME`, `MAIL_PASSWORD` (a Gmail app password) and `MAIL_TO` as repository secrets to have the report emailed.

## Configure

- `config/tsp.json`: your baseline equity mix and **your current TSP allocation** (the dial compares against it).
- `scripts/weekly_report_data.py`: `PORTFOLIO` and `CLUSTERS` at the top. Move these to a config file once the Schwab CSV import exists.
- Scheduled workflows run from the repository's default branch, so the Saturday job starts once this branch is merged.

## Backtests

`scripts/tsp_backtest.py` runs the weekly TSP dial on ETF proxies (tsp.gov blocks automated downloads). Drop the tsp.gov share-price CSV in `data/tsp_prices.csv` to re-run on real fund prices. `TSP_MULT=1.0,0.85,0.5` overrides the multipliers.
