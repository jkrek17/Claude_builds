// Build the static page into dist/: compile the CSS, copy the page, its script and Chart.js.
// The data file dist/data/site.json is written separately by `nflmodel --json`.
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";

mkdirSync("dist/data", { recursive: true });
execFileSync("npx", ["@tailwindcss/cli", "-i", "src/input.css", "-o", "dist/app.css", "--minify"], { stdio: "inherit" });
copyFileSync("src/index.html", "dist/index.html");
copyFileSync("src/app.js", "dist/app.js");
copyFileSync("node_modules/chart.js/dist/chart.umd.min.js", "dist/chart.js");
mkdirSync("dist/fonts", { recursive: true });
for (const weight of [600, 700]) {
  copyFileSync(`node_modules/@fontsource/barlow-condensed/files/barlow-condensed-latin-${weight}-normal.woff2`,
    `dist/fonts/barlow-condensed-${weight}.woff2`);
}
