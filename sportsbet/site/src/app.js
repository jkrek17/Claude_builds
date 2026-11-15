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

// Shared with props.js, which draws the Props tab.
window.teamLogo = logo;
window.TEAM_NAMES = TEAMS;

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

function side(abbr, picked, align) {
  return `<div class="side ${align} ${picked ? "picked" : ""}">
    ${logo(abbr)}
    <span class="abbr">${abbr}</span>
    <span class="name">${TEAMS[abbr] || ""}</span>
  </div>`;
}

function pickTile(label, pick, prob, push, hit, breakEven) {
  if (!pick) return `<div class="pick empty"><p class="eyebrow">${label}</p><p class="pick-main faint">No line yet</p></div>`;
  const clears = prob > breakEven;
  return `<div class="pick ${clears ? "clears" : ""}">
    <p class="eyebrow flex items-center justify-between"><span>${label}</span>${mark(hit)}</p>
    <p class="pick-main">${pick}</p>
    <p class="num text-xs muted">${pct(prob)} chance${push >= 0.005 ? ` · push ${pct(push)}` : ""}</p>
  </div>`;
}

// One line on why the model leans the way it does. The four parts add up to the predicted margin.
const signed = (v, digits = 1) => (v >= 0 ? "+" : "−") + Math.abs(v).toFixed(digits);
function whyLine(c) {
  if (!c.why) return "";
  const LABELS = { team: "team strength", qb: "quarterback", home: "home field", rest: "rest" };
  const sides = { [c.home]: [], [c.away]: [] };
  for (const key of Object.keys(LABELS)) {
    const v = c.why[key];
    if (v == null || Math.abs(v) < 0.05) continue;
    sides[v > 0 ? c.home : c.away].push(`+${Math.abs(v).toFixed(1)} ${LABELS[key]}`);
  }
  const lead = c.pred_margin >= 0 ? c.home : c.away, other = lead === c.home ? c.away : c.home;
  const text = [lead, other].filter((t) => sides[t].length).map((t) => `<strong>${t}</strong> ${sides[t].join(", ")}`).join(" · ");
  return text ? `<p class="why num"><span class="eyebrow">Why</span> ${text}</p>` : "";
}

function gameCard(c, breakEven, index) {
  const homePicked = c.ml_pick === c.home;
  const awayProb = 1 - c.home_win_prob;
  const market = c.market_home_prob == null ? null : homePicked ? c.market_home_prob : 1 - c.market_home_prob;
  const chips = [];
  if (c.wind_source === "forecast") {
    chips.push(c.windy
      ? `<span class="chip chip-gold">Windy · ${Math.round(c.wind_mph)} mph</span>`
      : `<span class="chip">Wind ${Math.round(c.wind_mph)} mph</span>`);
  }
  if (c.qb_assumed) chips.push(`<span class="chip">QB not announced</span>`);
  const score = (away, home, digits) => `<span class="${homePicked ? "dim" : ""}">${away.toFixed(digits)}</span><span class="dash">–</span><span class="${homePicked ? "" : "dim"}">${home.toFixed(digits)}</span>`;
  const center = c.final
    ? `<p class="big num"><span>${c.away_score}</span><span class="dash">–</span><span>${c.home_score}</span></p>
       <p class="num text-xs muted">model ${c.pred_away_score.toFixed(1)}–${c.pred_home_score.toFixed(1)}</p>`
    : `<p class="big num">${score(c.pred_away_score, c.pred_home_score, 1)}</p>
       <p class="eyebrow">Predicted</p>`;
  const lead = c.pred_margin >= 0 ? c.home : c.away;
  const detail = [
    `Model margin: ${lead} by ${Math.abs(c.pred_margin).toFixed(1)}` + (c.spread_line == null ? "" : `, ${c.ats_gap.toFixed(1)} pts from the line`),
    `Model total: ${c.pred_total.toFixed(1)}` + (c.total_line == null ? "" : `, ${c.ou_gap.toFixed(1)} pts from the line of ${c.total_line}`),
    market == null ? "" : `Market gives ${c.ml_pick} a ${pct(market, 0)} chance; the model says ${pct(c.ml_pick_prob, 0)}`,
  ].filter(Boolean);
  return `<article class="panel game" style="--away:${color(c.away)};--home:${color(c.home)};--i:${index}" tabindex="0">
    <div class="strip"></div>
    <div class="p-4">
      <div class="flex items-center justify-between eyebrow"><span>${c.final ? "Final" : kickoff(c.gametime)}</span><span>${c.ml_pick} to win ${mark(c.ml_hit)}</span></div>
      <div class="matchup">
        ${side(c.away, !homePicked, "left")}
        <div class="center">${center}</div>
        ${side(c.home, homePicked, "right")}
      </div>
      <div class="odds num"><span class="${homePicked ? "muted" : "font-semibold"}">${100 - Math.round(100 * c.home_win_prob)}%</span><span class="eyebrow">Model win chance</span><span class="${homePicked ? "font-semibold" : "muted"}">${pct(c.home_win_prob, 0)}</span></div>
      <div class="split" role="img" aria-label="Model win chance: ${c.away} ${100 - Math.round(100 * c.home_win_prob)}%, ${c.home} ${pct(c.home_win_prob, 0)}">
        <div class="bar" style="width:${(100 * awayProb).toFixed(1)}%;background:var(--away)"></div>
        <div class="bar" style="width:${(100 * c.home_win_prob).toFixed(1)}%;background:var(--home)"></div>
      </div>
      ${whyLine(c)}
      <div class="mt-3 grid grid-cols-2 gap-2">
        ${pickTile("Spread", c.ats_pick, c.ats_prob, c.ats_push, c.ats_hit, breakEven)}
        ${pickTile("Total", c.ou_pick, c.ou_prob, c.ou_push, c.ou_hit, breakEven)}
      </div>
      ${chips.length ? `<div class="mt-3 flex flex-wrap gap-2">${chips.join("")}</div>` : ""}
      <details class="more">
        <summary>The numbers behind it</summary>
        <ul>${detail.map((d) => `<li>${d}</li>`).join("")}</ul>
      </details>
    </div>
  </article>`;
}

function tile(label, pair, accent) {
  return `<div class="panel tile px-3 py-3 text-center" style="--accent:var(${accent})">
    <p class="eyebrow">${label}</p>
    <p class="num display mt-1 text-3xl">${pair[1] ? rec(pair) : "0-0"}</p>
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
      <h2 class="day">${dayLabel(day)}</h2>
      <div class="grid gap-4 md:grid-cols-2">${cards.filter((c) => c.gameday === day).map((c, i) => gameCard(c, data.break_even, i)).join("")}</div>`).join("")}
    <p class="mt-6 text-xs faint leading-relaxed">The percentage beside a spread or total pick is the estimated chance it wins if it does not push.
      It is shown in bold only when it clears the ${pct(data.break_even)} needed to break even at -110.</p>`;
}

function recordTable(head, rows) {
  return `<div class="panel overflow-x-auto"><table class="table table-sm num">
    <thead><tr>${head.map((h, i) => `<th class="${i ? "text-right" : ""}">${h}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((v, i) => `<td class="${i ? "text-right" : ""}">${v}</td>`).join("")}</tr>`).join("")}</tbody>
  </table></div>`;
}

// A win-loss record with a thin bar showing the win rate; the tick marks 50%.
function meter(pair, accent) {
  const r = rate(pair);
  return `<div class="meter">
    <span class="display num">${pair[1] ? rec(pair) : "–"}</span>
    <span class="track"><span class="fill" style="width:${r == null ? 0 : (100 * r).toFixed(1)}%;background:var(${accent})"></span></span>
  </div>`;
}

// Model against the closing line on one measure. `lowerWins` is true for average misses.
function duel(label, note, model, line, format, lowerWins) {
  const modelWins = lowerWins ? model < line : model > line;
  const cell = (name, value, wins) => `<div class="duel-cell ${wins ? "wins" : ""}">
    <p class="eyebrow">${name}</p><p class="display num text-3xl">${format(value)}</p>${wins ? `<p class="tag">Closer</p>` : ""}</div>`;
  return `<div class="panel duel">
    <div class="duel-head"><h3>${label}</h3><p>${note}</p></div>
    <div class="grid grid-cols-2">${cell("Model", model, modelWins)}${cell("Closing line", line, !modelWins)}</div>
  </div>`;
}

// A win rate on a 40% to 60% scale with the break-even point marked.
function gauge(label, pair, breakEven, accent) {
  const r = rate(pair), pos = (x) => Math.max(0, Math.min(100, (x - 0.4) / 0.2 * 100)).toFixed(1);
  return `<div class="panel gauge">
    <div class="flex items-end justify-between">
      <div><p class="eyebrow">${label}</p><p class="display num text-4xl">${pct(r)}</p></div>
      <p class="num text-sm muted">${rec(pair)}</p>
    </div>
    <div class="gauge-track" role="img" aria-label="${label}: ${pct(r)}, break-even ${pct(breakEven)}">
      <span class="fill" style="width:${pos(r)}%;background:var(${accent})"></span>
      <span class="mark" style="left:${pos(breakEven)}%"><i>needs ${pct(breakEven)}</i></span>
    </div>
    <div class="flex justify-between text-xs faint num"><span>40%</span><span>60%</span></div>
  </div>`;
}

function renderRecord(data) {
  const b = data.backtest;
  const both = (pair) => `${rec(pair)} <span class="faint">${pct(rate(pair))}</span>`;
  const weekRow = (label, w, extra = "") => `<div class="week ${extra}">
    <span class="wk display">${label}</span>${meter(w.su, "--series-3")}${meter(w.ats, "--series-1")}${meter(w.ou, "--series-2")}</div>`;
  $("record").innerHTML = `
    <h2 class="day">${data.season} by week</h2>
    <p class="lede">Picks on every game, graded against the closing line. Each bar is the share won, and the tick marks 50%.</p>
    <div class="panel weeks">
      <div class="week head"><span></span><span>Winners</span><span>Spread</span><span>Totals</span></div>
      ${data.by_week.map((w) => weekRow(`Wk ${w.week}`, w)).join("")}
      ${weekRow("Season", data.record, "total")}
    </div>

    <h2 class="day">Backtest, ${b.first} to ${b.last}</h2>
    <p class="lede">${b.games.toLocaleString()} games. Each one was predicted using only earlier games, and never a betting line.</p>
    <div class="grid gap-3 md:grid-cols-2">
      ${gauge("Against the spread", b.ats, data.break_even, "--series-1")}
      ${gauge("Over/under", b.ou, data.break_even, "--series-2")}
    </div>
    <div class="mt-3 grid gap-3 md:grid-cols-3">
      ${duel("Margin", "Average miss in points. Lower is better.", b.margin_mae.model, b.margin_mae.line, (v) => v.toFixed(2), true)}
      ${duel("Total", "Average miss in points. Lower is better.", b.total_mae.model, b.total_mae.line, (v) => v.toFixed(2), true)}
      ${duel("Winners", "Share of games picked correctly.", b.su_model, b.su_line, (v) => pct(v), false)}
    </div>

    <h2 class="day">Win rate by season</h2>
    <p class="lede">The dashed line is the ${pct(data.break_even)} needed to break even at -110. Neither series stays above it.</p>
    <div class="panel p-3">
      <div class="mb-1 flex gap-4 text-xs muted">
        <span><span class="swatch" style="background:var(--series-1)"></span> Spread picks</span>
        <span><span class="swatch" style="background:var(--series-2)"></span> Total picks</span>
      </div>
      <div class="relative h-56"><canvas id="chart" role="img" aria-label="Model win rate against the spread and on totals for each season"></canvas></div>
    </div>
    <details class="more">
      <summary>Show the season table</summary>
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
  let n = 0;
  const item = (title, body, extra = "") => `<article class="panel guide ${extra}">
    <span class="step display num">${String(++n).padStart(2, "0")}</span>
    <div><h3>${title}</h3><p>${body}</p></div></article>`;
  $("about").innerHTML = `
    <div class="panel verdict">
      <p class="eyebrow">The short version</p>
      <p class="display text-4xl">Informed predictions, not winning bets</p>
      <div class="mt-4 grid grid-cols-3 gap-2 num">
        <div><p class="display text-3xl">${pct(rate(b.ats))}</p><p class="cap">against the spread</p></div>
        <div><p class="display text-3xl">${pct(rate(b.ou))}</p><p class="cap">on totals</p></div>
        <div><p class="display text-3xl gold">${pct(data.break_even)}</p><p class="cap">needed to break even</p></div>
      </div>
    </div>
    <h2 class="day">Reading a game card</h2>
    <div class="grid gap-3 md:grid-cols-2">
    ${item("What this is", `A model that predicts every NFL game's score, winner and total from team and quarterback stats. It never sees a betting line when it predicts. The picks come from comparing its numbers with the line afterwards.`)}
    ${item("It has no proven edge", `Over ${b.games.toLocaleString()} games from ${b.first} to ${b.last} it went ${pct(rate(b.ats))} against the spread and ${pct(rate(b.ou))} on totals. You need ${pct(data.break_even)} to break even at -110. The closing line was the more accurate forecast of both margin and total. Betting every pick would have lost about 4% of the money staked.`, "key")}
    ${item("Model win chance", `The split bar shows the model's chance for each team. Open "The numbers behind it" to see the market's chance, taken from the moneylines with the bookmaker's cut removed. The market's number has been the more accurate one, so a gap between the two is not an edge.`)}
    ${item("The chance on a spread or total pick", `This is the chance the pick wins if it does not push. It leans mostly on the line and only slightly on the model, which is why it stays close to 50%. Values this close to 50% are not statistically different from a coin flip. A pick is outlined in gold only when its chance clears ${pct(data.break_even)}.`)}
    ${item("Push chance", `Shown when a line sits on a whole number. NFL games land on margins such as 3 and 7 far more often than a smooth curve suggests, and the push chance accounts for that.`)}
    ${item("Wind", `Outdoor games use the National Weather Service forecast at kickoff when one is available. A game is marked windy at ${data.windy_mph} mph or more. In the backtest, unders won 54.8% of those games, which is suggestive but not proven.`)}
    ${item("Limits", `Injuries other than the starting quarterback are not modeled. Lines are the ones published by nflverse and may differ from your sportsbook's. Rain and temperature are not used.`)}
    </div>`;
}

// Rankings tab: every team's power rating in points against an average team, and what it is made of.
let rankSort = "power", rankOpen = null, rankVersus = false, radar = null;
const RADAR = [["pass_off", "Pass offense"], ["rush_off", "Rush offense"], ["pass_def", "Pass defense"],
               ["rush_def", "Rush defense"], ["qb_form", "QB form"], ["pace", "Pace"]];
const ordinal = (n) => n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : ["th", "st", "nd", "rd"][n % 10] || "th");

// A team's profile on six measures, each as its league rank turned into a 0 to 100 score so that
// further out is always better (1st of 32 is 100).
function teamProfile(t, teams) {
  const next = t.next && teams.find((o) => o.team === t.next.opponent);
  const versus = rankVersus && next;
  const cells = RADAR.map(([key, label]) => `<tr><td>${label}</td><td class="num text-right"><b>${ordinal(t[key + "_rank"])}</b></td>${
    versus ? `<td class="num text-right">${ordinal(next[key + "_rank"])}</td>` : ""}</tr>`).join("");
  return `<div class="rk-profile">
    ${next ? `<label class="rk-versus"><input type="checkbox" data-versus ${rankVersus ? "checked" : ""}>
      <span>Compare with this week's opponent, ${TEAMS[next.team] || next.team}${t.next.home ? " (home)" : " (away)"}</span></label>` : ""}
    <div class="rk-radar"><canvas id="rk-radar" role="img" aria-label="League ranks of ${TEAMS[t.team] || t.team} on six measures, listed in the table below"></canvas></div>
    <table class="rk-ranks"><thead><tr><th>League rank of ${data_teams_count(teams)}</th><th class="text-right">${t.team}</th>${versus ? `<th class="text-right">${next.team}</th>` : ""}</tr></thead><tbody>${cells}</tbody></table>
    <p class="rk-note">Further from the centre is better. Pace is plays run per game, a measure of speed and not of quality.</p>
  </div>`;
}
const data_teams_count = (teams) => teams.length;

function drawRadar(t, teams) {
  if (radar) { radar.destroy(); radar = null; }
  const canvas = document.getElementById("rk-radar");
  if (!canvas || !window.Chart) return;
  const score = (team) => RADAR.map(([key]) => Math.round(100 * (teams.length + 1 - team[key + "_rank"]) / teams.length));
  const navy = css("--series-1"), gold = css("--gold"), ink = css("--ink-2"), grid = css("--color-base-300");
  const next = rankVersus && t.next && teams.find((o) => o.team === t.next.opponent);
  const datasets = [{ label: t.team, data: score(t), borderColor: navy, backgroundColor: "rgb(47 90 160 / 0.22)", borderWidth: 2, pointRadius: 3, pointBackgroundColor: navy }];
  if (next) datasets.push({ label: next.team, data: score(next), borderColor: gold, backgroundColor: "rgb(201 162 39 / 0.14)", borderWidth: 2, borderDash: [5, 4], pointRadius: 3, pointStyle: "rectRot", pointBackgroundColor: gold });
  const narrow = canvas.parentElement.clientWidth < 360;
  radar = new Chart(canvas, {
    type: "radar",
    data: { labels: RADAR.map(([, label]) => (narrow ? label.split(" ") : label)), datasets },
    options: {
      responsive: true, maintainAspectRatio: true, aspectRatio: 1, animation: { duration: matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 400 },
      scales: { r: { min: 0, max: 100, ticks: { display: false, stepSize: 25 }, grid: { color: grid }, angleLines: { color: grid },
                     pointLabels: { color: ink, font: { size: 11, weight: "600" } } } },
      plugins: { legend: { display: !!next, position: "bottom", labels: { usePointStyle: true, boxWidth: 8, color: ink } },
                 tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${ordinal(teams.find((o) => o.team === c.dataset.label)[RADAR[c.dataIndex][0] + "_rank"])} of ${teams.length}` } } },
    },
  });
}

function renderRankings(data) {
  const r = data.rankings;
  if (!r || !r.teams.length) {
    $("rankings").innerHTML = `<p class="lede">Team ratings are not published yet.</p>`;
    return;
  }
  const SORTS = { power: "Power", pass_off: "Pass offense", rush_off: "Rush offense", pass_def: "Pass defense",
                  rush_def: "Rush defense", qb: "Quarterback", results: "Results" };
  const teams = [...r.teams].sort((a, b) => b[rankSort] - a[rankSort]);
  const top = Math.max(...r.teams.map((t) => Math.abs(t[rankSort])));
  const part = (label, v, on) => `<span class="${on ? "on" : ""}">${label} <b class="num">${signed(v)}</b></span>`;
  const row = (t, i) => {
    const [w, l, ties] = t.record, v = t[rankSort], width = (50 * Math.abs(v) / top).toFixed(1);
    const isOpen = rankOpen === t.team;
    return `<li class="rk-item ${isOpen ? "open" : ""}" style="--team:${color(t.team)};--i:${Math.min(i, 12)}">
    <button type="button" class="rk-row" data-team="${t.team}" aria-expanded="${isOpen}">
      <span class="rk-n display num">${i + 1}</span>
      ${logo(t.team)}
      <span class="rk-main">
        <span class="rk-name"><span class="display">${TEAMS[t.team] || t.team}</span><span class="rk-rec num">${w}-${l}${ties ? "-" + ties : ""}</span></span>
        <span class="rk-meta num">${t.qb_name ? `QB ${t.qb_name} · ` : ""}Elo ${Math.round(t.elo)}</span>
        <span class="rk-bar" role="img" aria-label="${SORTS[rankSort]} ${signed(v)} points"><i class="${v >= 0 ? "pos" : "neg"}" style="width:${width}%"></i></span>
        <span class="rk-parts">${part("Pass off", t.pass_off, rankSort === "pass_off")}${part("Rush off", t.rush_off, rankSort === "rush_off")}${part("Pass def", t.pass_def, rankSort === "pass_def")}${part("Rush def", t.rush_def, rankSort === "rush_def")}${part("QB", t.qb, rankSort === "qb")}${part("Results", t.results, rankSort === "results")}</span>
      </span>
      <span class="rk-power display num ${v >= 0 ? "" : "neg"}">${signed(v)}</span>
    </button>${isOpen ? teamProfile(t, r.teams) : ""}</li>`;
  };
  let n = 0;
  const item = (title, body, extra = "") => `<article class="panel guide ${extra}">
    <span class="step display num">${String(++n).padStart(2, "0")}</span>
    <div><h3>${title}</h3><p>${body}</p></div></article>`;
  const hf = r.home_field == null ? "" : ` Home field is currently worth ${r.home_field.toFixed(1)} points.`;
  $("rankings").innerHTML = `
    <p class="lede">Each team's rating in points against an average team on a neutral field, as of the last game played. The gap between two teams, plus home field, is the model's predicted margin. The small numbers on each row add up to its power. Tap a team for its profile chart.</p>
    <div class="pr-chips rk-sorts" role="group" aria-label="Rank by">${Object.entries(SORTS).map(([k, label]) =>
      `<button type="button" class="pr-chip ${k === rankSort ? "on" : ""}" data-sort="${k}" aria-pressed="${k === rankSort}">${label}</button>`).join("")}</div>
    <ol class="panel rk-list">${teams.map(row).join("")}</ol>
    <h2 class="day">What goes into the rating</h2>
    <div class="grid gap-3 md:grid-cols-2">
    ${item("Results", `An Elo rating, the same idea used for chess and by many NFL power rankings. A team gains points for winning, more for winning big and more for beating a strong opponent, and the loser gives up the same amount. Ratings move a third of the way back to average each off-season. 1500 is average.`)}
    ${item("Passing and rushing, offense and defense", `Four separate ratings: how much better than expected the team does passing and running, and how much its defense gives up against each, all adjusted for the opponents faced. They come from play-by-play (expected points added), so they see more than the final score. A plus on defense means a good defense. Tap a button above to rank by any one.`)}
    ${item("Quarterback", `How the team's passing has done in the current starter's games compared with the team's usual level, plus his own recent form per dropback. A backup taking over moves this part straight away.`)}
    ${item("In each game", `Add home field and any difference in rest days to the gap in power and you get the predicted margin. Each game card shows that sum on its "Why" line.${hf}`)}
    ${item("How far to trust it", `These ratings come only from results and stats. Injuries other than the starting quarterback are not included. Over ${data.backtest.games.toLocaleString()} test games the betting line predicted margins better than the model did.`, "key")}
    </div>`;
  $("rankings").querySelector(".rk-sorts").addEventListener("click", (e) => {
    if (!e.target.dataset.sort) return;
    rankSort = e.target.dataset.sort;
    renderRankings(data);
  });
  const list = $("rankings").querySelector(".rk-list");
  list.addEventListener("click", (e) => {
    const btn = e.target.closest(".rk-row");
    if (!btn) return;
    rankOpen = rankOpen === btn.dataset.team ? null : btn.dataset.team;
    const y = scrollY;
    renderRankings(data);
    scrollTo(0, y);
    const now = rankOpen && $("rankings").querySelector(".rk-item.open");
    if (now && now.getBoundingClientRect().top < 0) now.scrollIntoView({ block: "start" });
  });
  list.addEventListener("change", (e) => {
    if (!("versus" in e.target.dataset)) return;
    rankVersus = e.target.checked;
    const y = scrollY;
    renderRankings(data);
    scrollTo(0, y);
  });
  const opened = rankOpen && r.teams.find((t) => t.team === rankOpen);
  if (opened && !$("rankings").hidden) drawRadar(opened, r.teams);
}

function showTab(name, data) {
  for (const tab of ["picks", "props", "rankings", "record", "about"]) $(tab).hidden = tab !== name;
  for (const btn of $("tabs").children) {
    const on = btn.dataset.tab === name;
    btn.setAttribute("aria-selected", on);
    btn.className = on ? "tab-on text-primary" : "muted";
  }
  if (name === "record") drawChart(data);
  if (name === "props") Props.open(data);
  if (name === "rankings") renderRankings(data);
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
  renderRankings(data);
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
