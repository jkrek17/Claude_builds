// Renders the college football page from data/cfb.json (written by `cfbmodel site --json`).
// The contract is in docs/superpowers/plans/cfb-site-json.md. Any number in it may be null.
"use strict";

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const num = (x) => typeof x === "number" && isFinite(x);
const pct = (x, digits = 1) => (num(x) ? (100 * x).toFixed(digits) + "%" : "n/a");
const fixed = (x, digits = 1) => (num(x) ? x.toFixed(digits) : "n/a");
const signed = (x, digits = 1) => (num(x) ? (x > 0 ? "+" : x < 0 ? "−" : "") + Math.abs(x).toFixed(digits) : "n/a");
const BREAK_EVEN = 110 / 210;  // win rate needed at -110

// Logos come from ESPN, whose college ids match CollegeFootballData's. A missing logo shows a dot.
function logo(id, size = 28) {
  const dot = `<span class='swatch' style='background:var(--navy)'></span>`;
  if (id == null) return `<span class="logo">${dot}</span>`;
  return `<span class="logo"><img src="https://a.espncdn.com/i/teamlogos/ncaa/500/${encodeURIComponent(id)}.png" alt="" width="${size}" height="${size}" loading="lazy" onerror="this.outerHTML=&quot;${dot}&quot;"></span>`;
}

// A hit/miss mark with an icon and a word, so it never relies on color alone.
function mark(hit) {
  if (hit == null) return "";
  return hit ? `<span class="pill pill-won">✓ Hit</span>` : `<span class="pill pill-lost">✕ Miss</span>`;
}

const localDay = (d) => d.toLocaleDateString("en-US", { weekday: "long", month: "short", day: "numeric" });
const dayKey = (d) => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
function kickoff(g) {
  const d = g.start ? new Date(g.start) : null;
  if (!d || isNaN(d)) return "Time TBA";
  return d.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", timeZoneName: "short" });
}

// A spread from the home side (negative = home favored) written the way a sportsbook shows it.
function lineText(homeSpread, g) {
  if (!num(homeSpread)) return null;
  if (Math.abs(homeSpread) < 0.05) return "Pick'em";
  return homeSpread < 0 ? `${esc(g.home)} −${Math.abs(homeSpread).toFixed(1)}` : `${esc(g.away)} −${homeSpread.toFixed(1)}`;
}

// One team's spread from its own side: "−7.5", "+4.0" or "PK".
const sideLine = (x) => (!num(x) ? null : Math.abs(x) < 0.05 ? "PK" : signed(x));

let RANK = {};  // model rank by team id, for the small number beside a team's name

function side(g, which, picked) {
  const id = g[which + "_id"], rank = RANK[id];
  return `<div class="side ${which === "away" ? "left" : "right"} ${picked ? "picked" : ""}">
    ${logo(id, 48)}
    <span class="abbr cf-team">${rank ? `<span class="cf-rank num">${rank}</span>` : ""}${esc(g[which])}</span>
    <span class="name">${esc(g[which + "_conf"] || "")}</span>
  </div>`;
}

// One market tile: the line, the model's number, the pick and its chance, and a BET badge when the
// model's edge clears the bar.
function marketTile(label, opts) {
  const { line, model, pick, prob, bet, hit, final } = opts;
  if (!line) {
    return `<div class="pick tossup cf-tile">
      <p class="eyebrow">${label}</p>
      <p class="pick-main faint">${final ? "No line" : "No line yet"}</p>
      <p class="num text-xs muted">Model ${model || "n/a"}</p>
      <p class="pick-tag">No pick</p>
    </div>`;
  }
  return `<div class="pick cf-tile ${bet ? "clears" : "tossup"}">
    <p class="eyebrow flex items-center justify-between gap-2"><span>${label}</span>${final ? mark(hit) : bet ? `<span class="tag cf-bet">Bet</span>` : ""}</p>
    <p class="pick-main">${pick || "No pick"}</p>
    <dl class="cf-lines num">
      <div><dt>Line</dt><dd>${line}</dd></div>
      <div><dt>Model</dt><dd>${model || "n/a"}</dd></div>
    </dl>
    <p class="num text-xs muted">${num(prob) ? `${pct(prob)} chance` : "Chance n/a"}</p>
    <p class="pick-tag">${bet ? (final ? "Was a bet" : "Clears the bar") : "Lean only"}</p>
  </div>`;
}

function gameCard(g, index) {
  const margin = g.model_margin, total = g.model_total;
  const homeLean = num(margin) ? margin >= 0 : g.pick_spread === "home";
  const predHome = num(margin) && num(total) ? (total + margin) / 2 : null;
  const predAway = num(margin) && num(total) ? (total - margin) / 2 : null;
  let center;
  if (g.final && num(g.home_score) && num(g.away_score)) {
    center = `<p class="big num"><span>${g.away_score}</span><span class="dash">–</span><span>${g.home_score}</span></p>
      <p class="eyebrow num">${num(predHome) ? `Model ${predAway.toFixed(1)}–${predHome.toFixed(1)}` : "Final"}</p>`;
  } else if (num(predHome)) {
    center = `<p class="big num"><span class="${homeLean ? "dim" : ""}">${predAway.toFixed(1)}</span><span class="dash">–</span><span class="${homeLean ? "" : "dim"}">${predHome.toFixed(1)}</span></p>
      <p class="eyebrow">Predicted</p>`;
  } else {
    center = `<p class="big num dim">${g.neutral ? "vs" : "@"}</p><p class="eyebrow">No projection</p>`;
  }

  // Spread pick: the side the model takes and its chance to cover.
  const spreadPick = g.pick_spread === "home" ? g.home : g.pick_spread === "away" ? g.away : null;
  const spreadProb = !num(g.home_cover_prob) ? null : g.pick_spread === "away" ? 1 - g.home_cover_prob : g.home_cover_prob;
  const pickLine = !num(g.spread_line) ? "" : Math.abs(g.spread_line) < 0.05 ? " PK"
    : " " + signed(g.pick_spread === "home" ? g.spread_line : -g.spread_line);
  const spreadPickText = spreadPick == null ? null : esc(spreadPick) + pickLine;
  const totalPick = g.pick_total === "over" ? "Over" : g.pick_total === "under" ? "Under" : null;
  const totalProb = !num(g.over_prob) ? null : g.pick_total === "under" ? 1 - g.over_prob : g.over_prob;

  const spread = marketTile("Spread", {
    // With a pick, the line and the model's number are both written from the picked team's side, so
    // the two read side by side ("+4.0" vs "+1.6"). Without one, the favorite is named.
    ...(spreadPick == null
      ? { line: lineText(g.spread_line, g), model: num(margin) ? lineText(-margin, g) : null }
      : { line: sideLine(g.pick_spread === "home" ? g.spread_line : -g.spread_line),
          model: num(margin) ? sideLine(g.pick_spread === "home" ? -margin : margin) : null }),
    pick: spreadPickText,
    prob: spreadProb, bet: g.bet_spread === true, hit: g.ats_hit, final: g.final,
  });
  const totals = marketTile("Total", {
    line: num(g.total_line) ? g.total_line.toFixed(1) : null, model: num(total) ? total.toFixed(1) : null,
    pick: totalPick && num(g.total_line) ? `${totalPick} ${g.total_line.toFixed(1)}` : totalPick,
    prob: totalProb, bet: g.bet_total === true, hit: g.ou_hit, final: g.final,
  });

  const chips = [];
  if (g.neutral) chips.push(`<span class="chip">Neutral site</span>`);
  const bet = g.bet_spread === true || g.bet_total === true;

  return `<article class="panel game cf-game ${g.final ? "final" : ""} ${bet ? "cf-has-bet" : ""}" id="g-${esc(g.game_id)}" style="--i:${Math.min(index, 12)}">
    <div class="strip"></div>
    <div class="p-4">
      <div class="flex items-center justify-between eyebrow"><span>${g.final ? "Final" : kickoff(g)}</span>${bet && !g.final ? `<span class="tag cf-bet m-0">Bet</span>` : ""}</div>
      <div class="matchup">
        ${side(g, "away", !homeLean)}
        <div class="center">${center}</div>
        ${side(g, "home", homeLean)}
      </div>
      <div class="mt-3 grid grid-cols-2 gap-2">${spread}${totals}</div>
      ${chips.length ? `<div class="mt-3 flex flex-wrap gap-2">${chips.join("")}</div>` : ""}
    </div>
  </article>`;
}

function providers(games) {
  const names = [...new Set(games.filter((g) => num(g.spread_line) || num(g.total_line)).map((g) => g.line_provider).filter(Boolean))];
  return names.length ? `Lines from ${names.map(esc).join(", ")}. ` : "";
}

let filter = "all", query = "";
function renderPicks(data, week) {
  const all = (data.games || []).filter((g) => g.week == null || Number(g.week) === Number(week));
  all.sort((a, b) => (a.start || "").localeCompare(b.start || ""));
  const q = query.trim().toLowerCase();
  const games = all.filter((g) => (filter !== "bets" || g.bet_spread === true || g.bet_total === true)
    && (filter !== "ranked" || RANK[g.home_id] <= 25 || RANK[g.away_id] <= 25)
    && (!q || `${g.home} ${g.away} ${g.home_conf} ${g.away_conf}`.toLowerCase().includes(q)));
  const bets = all.filter((g) => g.bet_spread === true || g.bet_total === true).length;
  const groups = [];
  for (const g of games) {
    const d = g.start ? new Date(g.start) : null;
    const key = d && !isNaN(d) ? dayKey(d) : "tba";
    let grp = groups.find((x) => x.key === key);
    if (!grp) groups.push(grp = { key, label: key === "tba" ? "Date to be announced" : localDay(d), games: [] });
    grp.games.push(g);
  }
  const chip = (k, label) => `<button type="button" class="pr-chip ${filter === k ? "on" : ""}" data-filter="${k}" aria-pressed="${filter === k}">${label}</button>`;
  const tools = all.length > 1 ? `<div class="cf-tools">
      <label><span class="sr-only">Find a team</span><input type="search" class="pr-search" id="cf-search" placeholder="Find a team or conference" value="${esc(query)}" autocomplete="off"></label>
      <div class="cf-chips" role="group" aria-label="Show">${chip("all", `All ${all.length}`)}${chip("bets", `Bets ${bets}`)}${chip("ranked", "Top 25")}</div>
    </div>` : "";
  let body;
  if (!all.length) body = `<div class="panel cf-empty"><p class="display">No games this week</p><p class="muted text-sm">Pick another week above, or check back once the schedule is out.</p></div>`;
  else if (!games.length) body = `<div class="panel cf-empty"><p class="display">No games match</p><p class="muted text-sm">Clear the search or pick All.</p></div>`;
  else body = groups.map((grp) => `<h2 class="day">${grp.label}</h2>
      <div class="grid gap-4 md:grid-cols-2">${grp.games.map(gameCard).join("")}</div>`).join("");
  $("picks").innerHTML = `
    ${tools}
    <div id="cf-list">${body}</div>
    ${all.length ? `<p class="mt-6 text-xs faint leading-relaxed">${providers(all)}The line is the sportsbook's; the model's number is its own predicted margin or total, written as a line.
      The chance is the estimated chance the pick wins. A BET badge appears only when the model's edge clears its preset bar; until the backtest passes, read it as research, not a bet.
      Kickoff times are in your time zone.</p>` : ""}`;
}

function renderRankings(data) {
  const rows = (data.rankings || []).slice().sort((a, b) => (a.rank ?? 1e9) - (b.rank ?? 1e9));
  if (!rows.length) { $("rankings").innerHTML = `<div class="panel cf-empty"><p class="display">No rankings yet</p><p class="muted text-sm">Team ratings are not published yet.</p></div>`; return; }
  const top = Math.max(...rows.map((r) => (num(r.net) ? Math.abs(r.net) : 0)), 1);
  $("rankings").innerHTML = `
    <p class="lede">The model's ratings, not a poll. Net is the predicted margin in points against an average FBS team on a neutral field. Off and Def are opponent-adjusted success rate (the share of plays that keep an offense on schedule) above or below the FBS average, in percentage points; on defense, below zero is better.</p>
    <div class="panel overflow-x-auto"><table class="table table-sm num cf-rank-table">
      <thead><tr><th scope="col" class="w-10">#</th><th scope="col">Team</th><th scope="col" class="text-right">Off</th><th scope="col" class="text-right">Def</th><th scope="col" class="text-right">Net</th></tr></thead>
      <tbody>${rows.map((r) => `<tr>
        <td class="display cf-n">${r.rank ?? ""}</td>
        <td><div class="flex items-center gap-2 min-w-0">${logo(r.team_id)}<div class="min-w-0"><p class="display cf-name">${esc(r.team)}</p><p class="text-xs muted">${esc(r.conf || "")}</p>
          ${num(r.net) ? `<span class="rk-bar cf-bar" aria-hidden="true"><i class="${r.net >= 0 ? "pos" : "neg"}" style="width:${(50 * Math.abs(r.net) / top).toFixed(1)}%"></i></span>` : ""}</div></div></td>
        <td class="text-right">${signed(r.off, 1)}</td>
        <td class="text-right">${signed(r.def, 1)}</td>
        <td class="text-right display cf-net ${num(r.net) && r.net < 0 ? "neg" : ""}">${signed(r.net)}</td>
      </tr>`).join("")}</tbody>
    </table></div>`;
}

// A win rate on a 40% to 60% scale with the break-even point marked.
function gauge(label, m) {
  const r = m && m.win_rate, pos = (x) => Math.max(0, Math.min(100, (x - 0.4) / 0.2 * 100)).toFixed(1);
  return `<div class="panel gauge">
    <div class="flex items-end justify-between">
      <div><p class="eyebrow">${label}</p><p class="display num text-4xl">${pct(r)}</p></div>
      <p class="num text-sm muted">${m && num(m.n) ? m.n.toLocaleString() + " bets" : ""}</p>
    </div>
    <div class="gauge-track" role="img" aria-label="${label}: ${pct(r)}, break-even ${pct(BREAK_EVEN)}">
      <span class="mark" style="left:${pos(BREAK_EVEN)}%"><i>needs ${pct(BREAK_EVEN)}</i></span>
      ${num(r) ? `<span class="val" style="left:${pos(r)}%;background:var(--series-1)"></span>` : ""}
    </div>
    <div class="flex justify-between text-xs faint num"><span>40%</span><span>60%</span></div>
    <dl class="cf-stats num">
      <div><dt>ROI</dt><dd>${num(m && m.roi) ? signed(100 * m.roi) + "%" : "n/a"}</dd></div>
      <div><dt>Avg CLV</dt><dd>${num(m && m.clv_mean) ? signed(m.clv_mean, 2) : "n/a"}</dd></div>
      <div><dt>CLV t</dt><dd>${num(m && m.clv_t) ? m.clv_t.toFixed(2) : "n/a"}</dd></div>
    </dl>
  </div>`;
}

function verdict(passed) {
  if (passed === true) return { word: "Passed", cls: "pill-won", text: "It cleared every preset test on seasons it never saw. Still research until it holds up live." };
  if (passed === false) return { word: "Failed", cls: "pill-lost", text: "It did not clear the preset tests on held-out seasons. Treat every pick as research only." };
  return { word: "Not yet scored", cls: "", text: "The backtest has not been run on the held-out seasons yet. Treat every pick as research only." };
}

function renderRecord(data) {
  const b = data.backtest || {};
  const m = b.markets || {};
  const v = verdict(b.passed);
  const seasons = [...new Set([...(m.spread?.by_season || []), ...(m.total?.by_season || [])].map((s) => s.season))].sort();
  const find = (mk, s) => (m[mk]?.by_season || []).find((x) => x.season === s) || {};
  const cell = (x) => (num(x.n) && x.n > 0 ? `${pct(x.win_rate)} <span class="faint">${x.n}</span>` : `<span class="faint">–</span>`);
  const roi = (x) => (num(x.roi) ? signed(100 * x.roi) + "%" : "–");
  $("record").innerHTML = `
    <div class="panel verdict">
      <p class="eyebrow">Backtest verdict</p>
      <p class="display text-4xl mt-1">${v.word}</p>
      <p class="cap mt-2 text-sm">${v.text}</p>
      <div class="mt-4 grid grid-cols-2 gap-2 num">
        <div><p class="display text-2xl">${esc(b.tune_seasons || "n/a")}</p><p class="cap">seasons used to tune</p></div>
        <div><p class="display text-2xl gold">${esc(b.scored_seasons || "n/a")}</p><p class="cap">held-out seasons scored</p></div>
      </div>
    </div>
    <h2 class="day">Bets in the held-out seasons</h2>
    <p class="lede">Only games where the model's edge cleared the bar, graded at the closing price. CLV is how far the line moved toward the pick by kickoff, in points; t above 2 suggests it is not luck.</p>
    <div class="grid gap-3 md:grid-cols-2">${gauge("Against the spread", m.spread)}${gauge("Over/under", m.total)}</div>
    <h2 class="day">By season</h2>
    ${seasons.length ? `<div class="panel overflow-x-auto"><table class="table table-sm num">
      <thead><tr><th scope="col">Season</th><th scope="col" class="text-right">Spread</th><th scope="col" class="text-right">ROI</th><th scope="col" class="text-right">Total</th><th scope="col" class="text-right">ROI</th></tr></thead>
      <tbody>${seasons.map((s) => { const a = find("spread", s), t = find("total", s); return `<tr><td>${s}</td><td class="text-right">${cell(a)}</td><td class="text-right">${roi(a)}</td><td class="text-right">${cell(t)}</td><td class="text-right">${roi(t)}</td></tr>`; }).join("")}</tbody>
    </table></div><p class="mt-2 text-xs faint">Win rate, then the number of bets. Break-even at -110 is ${pct(BREAK_EVEN)}.</p>`
    : `<p class="text-sm muted">No season results yet.</p>`}`;
}

const TABS = ["picks", "rankings", "record"];
const tabFromHash = () => { const h = location.hash.slice(1).toLowerCase(); return TABS.includes(h) ? h : null; };

function showTab(name, fromClick) {
  for (const tab of TABS) $(tab).hidden = tab !== name;
  const hash = "#" + name;
  if (location.hash !== hash && (fromClick || location.hash)) history.replaceState(null, "", hash);
  for (const btn of $("tabs").children) {
    const on = btn.dataset.tab === name;
    btn.setAttribute("aria-selected", on);
    btn.tabIndex = on ? 0 : -1;
    btn.className = on ? "tab-on text-primary" : "muted";
  }
  const top = Math.round($("notice").getBoundingClientRect().bottom + scrollY + 12);
  if (fromClick && scrollY > top) scrollTo(0, top);
}

function notice(status) {
  const s = status || {};
  const head = s.passed === true
    ? `<strong class="text-primary">Research only.</strong> <span class="muted">Passed its backtest; no proven edge live yet.</span>`
    : `<strong class="text-primary">No proven edge.</strong> <span class="muted">Research only, not bets.</span>`;
  const note = s.note || "The college model has not passed its backtest. Read every pick as research, not a bet.";
  return `<summary><span>${head}</span></summary><p class="muted">${esc(note)}</p>`;
}

function start(data) {
  for (const r of data.rankings || []) if (r.team_id != null && r.rank != null) RANK[r.team_id] = r.rank;
  const weeks = (data.weeks && data.weeks.length ? data.weeks : [...new Set((data.games || []).map((g) => g.week))])
    .filter((w) => w != null).map(Number).sort((a, b) => a - b);
  const select = $("week");
  select.innerHTML = weeks.map((w) => `<option value="${w}">Week ${w}</option>`).join("");
  let current = data.week ?? weeks[weeks.length - 1];
  const show = (week) => {
    current = week;
    select.value = week;
    $("title").textContent = week == null ? "College" : `Week ${week}`;
    renderPicks(data, week);
  };
  select.addEventListener("change", () => show(select.value));
  $("season").textContent = data.season ?? "";
  const made = data.generated_at ? new Date(data.generated_at) : null;
  $("updated").textContent = made && !isNaN(made) ? "Updated " + made.toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }) : "";
  $("notice").innerHTML = notice(data.status);
  renderRankings(data);
  renderRecord(data);
  $("tabs").addEventListener("click", (e) => e.target.dataset.tab && showTab(e.target.dataset.tab, true));
  $("tabs").addEventListener("keydown", (e) => {
    const step = { ArrowRight: 1, ArrowLeft: -1 }[e.key];
    if (!step || !e.target.dataset.tab) return;
    const next = TABS[(TABS.indexOf(e.target.dataset.tab) + step + TABS.length) % TABS.length];
    showTab(next, true);
    $("tab-" + next).focus();
  });
  $("picks").addEventListener("click", (e) => {
    const k = e.target.dataset.filter;
    if (!k) return;
    filter = k;
    renderPicks(data, current);
  });
  $("picks").addEventListener("input", (e) => {
    if (e.target.id !== "cf-search") return;
    query = e.target.value;
    const at = e.target.selectionStart;
    renderPicks(data, current);
    const box = $("cf-search");
    box.focus();
    box.setSelectionRange(at, at);
  });
  addEventListener("hashchange", () => { const tab = tabFromHash(); if (tab && $(tab).hidden) showTab(tab); });
  show(current);
  showTab(tabFromHash() || "picks");
}

fetch("data/cfb.json", { cache: "no-store" })
  .then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); })
  .then(start)
  .catch((err) => {
    console.warn("cfb.json:", err);
    $("title").textContent = "No data yet";
    $("notice").innerHTML = "<summary><span><strong class='text-primary'>No proven edge.</strong> <span class='muted'>The college predictions file could not be loaded. It is rebuilt on a schedule, so try again later.</span></span></summary>";
  });
