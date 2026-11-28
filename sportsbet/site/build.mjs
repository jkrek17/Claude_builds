// Build the static page into dist/: compile the CSS, copy the page, its scripts and Chart.js.
// The data files dist/data/site.json, props.json and cfb.json are written separately by
// `nflmodel --json`, `nflprops --json` and `cfbmodel site --json`.
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";

mkdirSync("dist/data", { recursive: true });
execFileSync("npx", ["@tailwindcss/cli", "-i", "src/input.css", "-o", "dist/app.css", "--minify"], { stdio: "inherit" });
copyFileSync("src/app.js", "dist/app.js");
copyFileSync("src/props.js", "dist/props.js");
copyFileSync("src/cfb.js", "dist/cfb.js");
copyFileSync("node_modules/chart.js/dist/chart.umd.min.js", "dist/chart.js");
mkdirSync("dist/fonts", { recursive: true });
for (const weight of [600, 700]) {
  copyFileSync(`node_modules/@fontsource/barlow-condensed/files/barlow-condensed-latin-${weight}-normal.woff2`,
    `dist/fonts/barlow-condensed-${weight}.woff2`);
}

// Each stylesheet and script is linked with a short hash of its contents, so a browser never pairs a
// new page with an old cached file.
const stamp = (file) => createHash("sha256").update(readFileSync(`dist/${file}`)).digest("hex").slice(0, 10);
const stampLinks = (html) =>
  html.replace(/(href|src)="(app\.css|chart\.js|props\.js|app\.js|cfb\.js)"/g, (_, attr, file) => `${attr}="${file}?v=${stamp(file)}"`);
writeFileSync("dist/index.html", stampLinks(readFileSync("src/index.html", "utf8")));
// The college football page (data/cfb.json from `cfbmodel site --json`). The workflow publishes it as
// /cfb/index.html beside a copy of these assets.
writeFileSync("dist/cfb.html", stampLinks(readFileSync("src/cfb.html", "utf8")));
