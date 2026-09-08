# Recommended improvements for future versions

1. **Field model with ownership.** The `POOL_WIN` objective assumes other entries pick at a flat win probability;
   use the entered pick shares (and later a popularity feed) so the field's survival is correlated with yours
   when you are on the chalk.
2. **Ownership feed.** Import survivor pick-percentage data (several public sites publish it weekly) instead of
   manual entry, with a timestamp like the other sources.
3. **Injury / QB feed.** ESPN publishes team injury reports and depth charts; map "QB out/questionable" to an
   automatic points adjustment with a source label, keeping the manual override.
4. **Line movement tracking.** Store the open and close of each line so the app can show movement since the last
   refresh and flag games where the market moved against the recommendation.
5. **Weather.** Pull kickoff forecasts for outdoor stadiums (wind and precipitation) for the current week.
6. **Calibrated blend weights and noise.** Once `LineHistory` has a few weeks of snapshots, feed the fitted
   `tauBase`/`tauPerWeek` into `RobustPlanner` and `PolicySimulator`, and fit the market/FPI blend and per-week
   discount with the closed-loop simulator instead of the current defaults.
7. **Multi-entry support.** Several entries with different used-team sets and strike counts, optimized jointly
   to diversify.
8. **Scheduled background refresh** (WorkManager) with a notification when the recommended pick changes.
9. **Export / share.** Share the weekly recommendation and route as an image or text; CSV export of rankings.
10. **Pool-rule variants.** Single elimination, three strikes, and "must pick a different team than last week"
    style rules, driven from Settings.
11. **Home-screen widget** showing the current recommendation and strikes.
12. **Historical backtest tool** to replay past seasons through the model using archived closing lines.
13. **Correlated team risk** in the simulators: a team-level strength shock so one injury moves all of a team's
    future games together, which makes spreading future value across teams matter more.
14. **Safety Score audit** with the closed-loop simulator: keep only the matchup penalties that improve results.
