// Renders the weekly card, record and backtest from data/site.json (written by `nflmodel --json`).
"use strict";

const TEAMS = {
  ARI: "Cardinals", ATL: "Falcons", BAL: "Ravens", BUF: "Bills", CAR: "Panthers", CHI: "Bears",
  CIN: "Bengals", CLE: "Browns", DAL: "Cowboys", DEN: "Broncos", DET: "Lions", GB: "Packers",
  HOU: "Texans", IND: "Colts", JAX: "Jaguars", KC: "Chiefs", LA: "Rams", LAC: "Chargers",
  LV: "Raiders", MIA: "Dolphins", MIN: "Vikings", NE: "Patriots", NO: "Saints", NYG: "Giants",
  NYJ: "Jets", PHI: "Eagles", PIT: "Steelers", SEA: "Seahawks", SF: "49ers", TB: "Buccaneers",
  TEN: "Titans", WAS: "Commanders",
};

// Each team's main color, used for the dot beside its name and for the win-chance bar.
const COLORS = {
  ARI: "#97233f", ATL: "#a71930", BAL: "#241773", BUF: "#00338d", CAR: "#0085ca", CHI: "#c83803",
  CIN: "#fb4f14", CLE: "#ff3c00", DAL: "#003594", DEN: "#fb4f14", DET: "#0076b6", GB: "#203731",
  HOU: "#a71930", IND: "#002c5f", JAX: "#006778", KC: "#e31837", LA: "#003594", LAC: "#0080c6",
  LV: "#444b52", MIA: "#008e97", MIN: "#4f2683", NE: "#002244", NO: "#b3995d", NYG: "#0b2265",
  NYJ: "#125740", PHI: "#004c54", PIT: "#ffb612", SEA: "#69be28", SF: "#aa0000", TB: "#d50a0a",
  TEN: "#4b92db", WAS: "#5a1414",
};
const color = (abbr) => COLORS[abbr] || "#8a909c";

// Logos are loaded from ESPN's image server, which spells two teams differently. If one fails to
// load, the team's color dot is shown in its place.
const LOGO_NAMES = { LA: "lar", WAS: "wsh" };
function logo(abbr) {
  const dot = `<span class='swatch' style='background:${color(abbr)}'></span>`;
  if (!TEAMS[abbr]) return `<span class="logo">${dot}</span>`;
  const src = `https://a.espncdn.com/i/teamlogos/nfl/500/${LOGO_NAMES[abbr] || abbr.toLowerCase()}.png`;
  return `<span class="logo"><img src="${src}" alt="" width="28" height="28" loading="lazy" onerror="this.outerHTML=&quot;${dot}&quot;"></span>`;
}

const $ = (id) => document.getElementById(id);
const pct = (x, digits = 1) => (x == null ? "n/a" : (100 * x).toFixed(digits) + "%");
const rec = ([wins, n]) => `${wins}-${n - wins}`;
const rate = ([wins, n]) => (n ? wins / n : null);
const css = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function dayLabel(iso) {
  const d = new Date(iso + "T12:00:00Z");
  return d.toLocaleDateString("en-US", { weekday: "long", month: "short", day: "numeric", timeZone: "UTC" });
}

function kickoff(hhmm) {
  if (!hhmm) return "";
  const [h, m] = hhmm.split(":").map(Number);
  return `${((h + 11) % 12) + 1}:${String(m).padStart(2, "0")} ${h < 12 ? "AM" : "PM"} ET`;
}

// A won/lost mark. Carries an icon and a word, so it never relies on color alone.
function mark(hit) {
  if (hit == null) return "";
  return hit
    ? `<span class="pill pill-won">✓ Won</span>`
    : `<span class="pill pill-lost">✕ Lost</span>`;
}

function teamRow(abbr, predicted, actual, picked) {
  const weight = picked ? "font-semibold" : "";
  const final = actual == null ? "" : `<span class="num w-12 text-right font-semibold">${actual}</span>`;
  return `<div class="flex items-center gap-3 py-1">
    ${logo(abbr)}
    <span class="w-11 text-base ${weight}">${abbr}</span>
    <span class="flex-1 truncate text-sm muted">${TEAMS[abbr] || ""}</span>
    <span class="num text-sm ${actual == null ? weight : "faint"}">${predicted.toFixed(1)}</span>${final}
  </div>`;
}

function pickRow(label, pick, prob, push, hit, breakEven) {
  if (!pick) return "";
  const clears = prob > breakEven;
  const pushNote = push >= 0.005 ? ` · push ${pct(push)}` : "";
  return `<div class="flex items-center gap-3 py-2">
    <span class="w-16 text-xs faint">${label}</span>
    <span class="flex-1 text-sm font-medium">${pick}</span>
    ${mark(hit)}
    <span class="num text-right text-xs muted">${clears ? "<strong>" : ""}${pct(prob)}${clears ? "</strong>" : ""}${pushNote}</span>
  </div>`;
}

function gameCard(c, breakEven) {
  const homePicked = c.ml_pick === c.home;
  const market = c.market_home_prob == null ? null : homePicked ? c.market_home_prob : 1 - c.market_home_prob;
  const chips = [];
  if (c.wind_source === "forecast") {
    chips.push(c.windy
      ? `<span class="badge badge-sm badge-warning badge-soft">Windy: ${Math.round(c.wind_mph)} mph</span>`
      : `<span class="badge badge-sm badge-ghost">Wind ${Math.round(c.wind_mph)} mph</span>`);
  }
  if (c.qb_assumed) chips.push(`<span class="badge badge-sm badge-ghost">QB not announced</span>`);
  const head = c.final ? "Final" : kickoff(c.gametime);
  const scoreHead = c.final
    ? `<span>Model</span><span class="w-12 text-right">Final</span>`
    : `<span>Predicted</span>`;
  return `<article class="panel game p-4" style="--team:${color(c.ml_pick)}">
    <div class="flex items-center justify-between eyebrow"><span>${head}</span><span class="flex gap-0">${scoreHead}</span></div>
    <div class="mt-1">
      ${teamRow(c.away, c.pred_away_score, c.away_score, !homePicked)}
      ${teamRow(c.home, c.pred_home_score, c.home_score, homePicked)}
    </div>
    <div class="mt-3">
      <div class="flex items-baseline justify-between text-sm">
        <span><span class="font-medium">${c.ml_pick} to win</span> ${mark(c.ml_hit)}</span>
        <span class="num text-xs muted">model ${pct(c.ml_pick_prob, 0)} · market ${pct(market, 0)}</span>
      </div>
      <div class="mt-1.5 h-1.5 rounded-full bg-base-300" role="img" aria-label="Model win chance ${pct(c.ml_pick_prob, 0)}">
        <div class="h-1.5 rounded-full" style="width:${(100 * c.ml_pick_prob).toFixed(1)}%;background:${color(c.ml_pick)}"></div>
      </div>
    </div>
    <div class="mt-3 divide-y divide-base-300 border-t border-base-300">
      ${pickRow("Spread", c.ats_pick, c.ats_prob, c.ats_push, c.ats_hit, breakEven)}
      ${pickRow("Total", c.ou_pick, c.ou_prob, c.ou_push, c.ou_hit, breakEven)}
    </div>
    ${chips.length ? `<div class="mt-2 flex flex-wrap gap-2">${chips.join("")}</div>` : ""}
  </article>`;
}

function tile(label, pair, accent) {
  return `<div class="panel tile px-3 py-3 text-center" style="--accent:var(${accent})">
    <p class="eyebrow">${label}</p>
    <p class="num mt-1 text-xl font-semibold">${pair[1] ? rec(pair) : "0-0"}</p>
    <p class="num text-xs muted">${pair[1] ? pct(rate(pair)) : "no games yet"}</p>
  </div>`;
}

function renderPicks(data, week) {
  const cards = data.weeks[week] || [];
  const days = [...new Set(cards.map((c) => c.gameday))];
  const noLines = cards.length && cards.every((c) => !c.ats_pick && !c.ou_pick);
  $("picks").innerHTML = `
    <p class="eyebrow mb-2">${data.season} season so far</p>
    <div class="grid grid-cols-3 gap-2">
      ${tile("Winners", data.record.su, "--series-3")}${tile("Spread", data.record.ats, "--series-1")}${tile("Totals", data.record.ou, "--series-2")}
    </div>
    ${noLines ? `<p class="mt-6 text-sm muted">No betting lines are posted for this week yet, so only scores and winners are shown.</p>` : ""}
    ${days.map((day) => `
      <h2 class="mb-2 mt-7 text-sm font-semibold">${dayLabel(day)}</h2>
      <div class="space-y-3">${cards.filter((c) => c.gameday === day).map((c) => gameCard(c, data.break_even)).join("")}</div>`).join("")}
    <p class="mt-6 text-xs faint leading-relaxed">The percentage beside a spread or total pick is the estimated chance it wins if it does not push.
      It is shown in bold only when it clears the ${pct(data.break_even)} needed to break even at -110.</p>`;
}

function recordTable(head, rows) {
  return `<div class="panel overflow-x-auto"><table class="table table-sm num">
    <thead><tr>${head.map((h, i) => `<th class="${i ? "text-right" : ""}">${h}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((v, i) => `<td class="${i ? "text-right" : ""}">${v}</td>`).join("")}</tr>`).join("")}</tbody>
  </table></div>`;
}

function renderRecord(data) {
  const b = data.backtest;
  const both = (pair) => `${rec(pair)} <span class="faint">${pct(rate(pair))}</span>`;
  $("record").innerHTML = `
    <h2 class="text-sm font-semibold">${data.season} by week</h2>
    <p class="mb-2 mt-1 text-xs muted">Picks on every game, graded against the closing line.</p>
    ${recordTable(["Week", "Winners", "Spread", "Totals"],
      data.by_week.map((w) => [w.week, rec(w.su), rec(w.ats), rec(w.ou)])
        .concat([["<strong>Season</strong>", both(data.record.su), both(data.record.ats), both(data.record.ou)]]))}

    <h2 class="mt-9 text-sm font-semibold">Backtest, ${b.first} to ${b.last}</h2>
    <p class="mb-2 mt-1 text-xs muted">${b.games.toLocaleString()} games. Each one was predicted using only earlier games, and never a betting line.</p>
    ${recordTable(["", "Model", "Closing line"], [
      ["Margin, average miss", b.margin_mae.model.toFixed(2) + " pts", b.margin_mae.line.toFixed(2) + " pts"],
      ["Total, average miss", b.total_mae.model.toFixed(2) + " pts", b.total_mae.line.toFixed(2) + " pts"],
      ["Winners picked", pct(b.su_model), pct(b.su_line)],
      ["Against the spread", both(b.ats), `needs ${pct(data.break_even)}`],
      ["Over/under", both(b.ou), `needs ${pct(data.break_even)}`],
    ])}

    <h2 class="mt-9 text-sm font-semibold">Win rate against the line, by season</h2>
    <p class="mt-1 text-xs muted">The dashed line is the ${pct(data.break_even)} needed to break even at -110. Neither series stays above it.</p>
    <div class="panel mt-2 p-3">
      <div class="mb-1 flex gap-4 text-xs muted">
        <span><span class="swatch" style="background:var(--series-1)"></span> Spread picks</span>
        <span><span class="swatch" style="background:var(--series-2)"></span> Total picks</span>
      </div>
      <div class="relative h-56"><canvas id="chart" role="img" aria-label="Model win rate against the spread and on totals for each season"></canvas></div>
    </div>
    <details class="mt-3 text-sm">
      <summary class="cursor-pointer muted">Show the season table</summary>
      <div class="mt-2">${recordTable(["Season", "Winners", "Spread", "Totals"],
        b.seasons.map((s) => [s.season, both(s.su), both(s.ats), both(s.ou)]))}</div>
    </details>`;
}

let chart;
function drawChart(data) {
  if (chart || !window.Chart) return;
  const seasons = data.backtest.seasons;
  const ink = css("--ink-2"), grid = css("--color-base-300");
  const series = (label, key, color) => ({
    label, data: seasons.map((s) => 100 * rate(s[key])), borderColor: color, backgroundColor: color,
    borderWidth: 2, pointRadius: 3, pointHoverRadius: 5, pointBorderColor: "#fff", pointBorderWidth: 1, tension: 0,
  });
  const breakEven = {
    id: "breakEven",
    afterDatasetsDraw(c) {
      const y = c.scales.y.getPixelForValue(100 * data.break_even), { left, right } = c.chartArea, g = c.ctx;
      g.save(); g.setLineDash([4, 4]); g.strokeStyle = ink; g.lineWidth = 1;
      g.beginPath(); g.moveTo(left, y); g.lineTo(right, y); g.stroke(); g.restore();
    },
  };
  chart = new Chart($("chart"), {
    type: "line",
    data: {
      labels: seasons.map((s) => s.season),
      datasets: [series("Spread picks", "ats", css("--series-1")), series("Total picks", "ou", css("--series-2"))],
    },
    options: {
      maintainAspectRatio: false, animation: false,
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: { callbacks: { label: (t) => ` ${t.dataset.label}: ${t.parsed.y.toFixed(1)}%` } },
      },
      scales: {
        x: { grid: { display: false }, ticks: { color: ink, maxRotation: 0, autoSkipPadding: 12 } },
        y: { min: 40, max: 60, grid: { color: grid }, border: { display: false },
             ticks: { color: ink, stepSize: 5, callback: (v) => v + "%" } },
      },
    },
    plugins: [breakEven],
  });
}

function renderAbout(data) {
  const b = data.backtest;
  const item = (title, body) => `<div class="panel p-4"><h3 class="text-sm font-semibold">${title}</h3><p class="mt-1 text-sm leading-relaxed muted">${body}</p></div>`;
  $("about").innerHTML = `<div class="space-y-3">
    ${item("What this is", `A model that predicts every NFL game's score, winner and total from team and quarterback stats. It never sees a betting line when it predicts. The picks come from comparing its numbers with the line afterwards.`)}
    ${item("It has no proven edge", `Over ${b.games.toLocaleString()} games from ${b.first} to ${b.last} it went ${pct(rate(b.ats))} against the spread and ${pct(rate(b.ou))} on totals. You need ${pct(data.break_even)} to break even at -110. The closing line was the more accurate forecast of both margin and total. Betting every pick would have lost about 4% of the money staked.`)}
    ${item("Model and market win chance", `The bar shows the model's chance for the team it picks. The market number is the same chance taken from the moneylines with the bookmaker's cut removed. The market's number has been the more accurate one, so a gap between the two is not an edge.`)}
    ${item("The chance on a spread or total pick", `This is the chance the pick wins if it does not push. It leans mostly on the line and only slightly on the model, which is why it stays close to 50%. Values this close to 50% are not statistically different from a coin flip.`)}
    ${item("Push chance", `Shown when a line sits on a whole number. NFL games land on margins such as 3 and 7 far more often than a smooth curve suggests, and the push chance accounts for that.`)}
    ${item("Wind", `Outdoor games use the National Weather Service forecast at kickoff when one is available. A game is marked windy at ${data.windy_mph} mph or more. In the backtest, unders won 54.8% of those games, which is suggestive but not proven.`)}
    ${item("Limits", `Injuries other than the starting quarterback are not modeled. Lines are the ones published by nflverse and may differ from your sportsbook's. Rain and temperature are not used.`)}
  </div>`;
}

function showTab(name, data) {
  for (const tab of ["picks", "record", "about"]) $(tab).hidden = tab !== name;
  for (const btn of $("tabs").children) {
    const on = btn.dataset.tab === name;
    btn.setAttribute("aria-selected", on);
    btn.className = "rounded-lg py-2 " + (on ? "tab-on bg-base-100 text-primary" : "muted");
  }
  if (name === "record") drawChart(data);
}

function start(data) {
  const weeks = Object.keys(data.weeks).map(Number).sort((a, b) => a - b);
  const select = $("week");
  select.innerHTML = weeks.map((w) => `<option value="${w}">Week ${w}</option>`).join("");
  const show = (week) => {
    select.value = week;
    $("title").textContent = `Week ${week}`;
    renderPicks(data, week);
  };
  select.addEventListener("change", () => show(select.value));
  $("season").textContent = data.season;
  const made = new Date(data.generated_at);
  $("updated").textContent = "Updated " + made.toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  const b = data.backtest;
  $("notice").innerHTML = `<strong class="text-primary">No proven edge.</strong> <span class="muted">In testing on ${b.games.toLocaleString()} games the model won ${pct(rate(b.ats))} against the spread and ${pct(rate(b.ou))} on totals. Breaking even takes ${pct(data.break_even)}. Read these as informed predictions, not winning bets.</span>`;
  renderRecord(data);
  renderAbout(data);
  $("tabs").addEventListener("click", (e) => e.target.dataset.tab && showTab(e.target.dataset.tab, data));
  show(data.week);
  showTab("picks", data);
}

fetch("data/site.json", { cache: "no-store" })
  .then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); })
  .then(start)
  .catch(() => {
    $("title").textContent = "No data yet";
    $("notice").textContent = "The predictions file could not be loaded. It is rebuilt on a schedule, so try again later.";
  });
