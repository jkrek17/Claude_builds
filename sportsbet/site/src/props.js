// The Props tab: type any line for a player's prop and see the model's chance of over, under and push.
// Reads data/props.json (written by `nflprops --json`), loaded only when the tab is first opened.
// Each row carries the model's full distribution, so every probability here is worked out in the browser.
"use strict";

const Props = (() => {
  const ORDER = ["pass_yds", "pass_tds", "pass_att", "pass_cmp", "rush_yds", "rec_yds", "receptions", "any_td"];
  const SHORT = { pass_yds: "Pass", pass_tds: "Pass TD", pass_att: "Att", pass_cmp: "Cmp", rush_yds: "Rush",
                  rec_yds: "Rec", receptions: "Catches", any_td: "TD" };
  const CHIP = { pass_yds: "Pass yds", pass_tds: "Pass TDs", pass_att: "Attempts", pass_cmp: "Completions", rush_yds: "Rush yds",
                 rec_yds: "Rec yds", receptions: "Catches", any_td: "TD" };
  const HEADLINE = { QB: "pass_yds", RB: "rush_yds", WR: "rec_yds", TE: "rec_yds" };
  const POSITIONS = ["QB", "RB", "WR", "TE"];
  const STORE = "nflmodel-props-lines";

  let data, site, players = new Map(), saved = { lines: {} }, game = "all", query = "", open = null;
  let market = null;  // which prop of the open player is showing
  let unfolded = new Set();  // games whose players are showing; the rest are folded to a heading
  // "top" ranks the plays that pass the betting rules at FanDuel's prices, "players" lists every prop by
  // game, "td" ranks anytime touchdown chances.
  let view = "players", rankAll = false;
  let plays = null;  // data/plays.json, or null when live prices were not published
  const RANK_SHOWN = 25, TOP_SHOWN = 10;

  // ---- Probability ----------------------------------------------------------------------------

  // P(X <= x) from quantile values q at probabilities probs. Flat stretches (mass at one value,
  // such as zero yards) count in full at that value.
  function cdf(x, q, probs) {
    const n = q.length;
    if (x < q[0]) return 0;
    if (x >= q[n - 1]) return 1;
    let lo = 0, hi = n - 1;
    while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (q[mid] <= x) lo = mid; else hi = mid; }
    if (q[hi] === q[lo]) return probs[hi];
    return probs[lo] + (probs[hi] - probs[lo]) * (x - q[lo]) / (q[hi] - q[lo]);
  }

  // The value at probability p: the inverse of cdf.
  function quantile(p, q, probs) {
    if (p <= probs[0]) return q[0];
    for (let i = 1; i < probs.length; i++) {
      if (p <= probs[i]) return q[i - 1] + (q[i] - q[i - 1]) * (p - probs[i - 1]) / (probs[i] - probs[i - 1]);
    }
    return q[q.length - 1];
  }

  // Chances of under, push and over for a line. Outcomes are whole numbers, so a whole-number line
  // can push and a half-point line cannot. A yardage result of exactly N is the model's N-0.5 to N+0.5.
  function chances(row, line) {
    const whole = Number.isInteger(line);
    let under, push = 0;
    if (row.pmf) {
      const total = row.pmf.reduce((a, b) => a + b, 0) || 1;
      under = row.pmf.reduce((sum, p, k) => sum + (k < line ? p : 0), 0) / total;
      if (whole) push = (row.pmf[line] || 0) / total;
    } else {
      const F = (x) => cdf(x, row.q, data.probs);
      under = whole ? F(line - 0.5) : F(line);
      if (whole) push = F(line + 0.5) - under;
    }
    const clamp = (v) => Math.min(1, Math.max(0, v));
    under = clamp(under); push = clamp(push);
    return { under, push, over: clamp(1 - under - push) };
  }

  // Whole percentages that add to 100 (largest remainder).
  function wholePercents(parts) {
    const raw = parts.map((p) => p * 100), out = raw.map(Math.floor);
    let left = 100 - out.reduce((a, b) => a + b, 0);
    raw.map((v, i) => [v - out[i], i]).sort((a, b) => b[0] - a[0]).forEach(([, i]) => { if (left-- > 0) out[i]++; });
    return out;
  }

  // Never print 0% or 100%: the model is not that sure of anything.
  const show = (pct) => (pct < 1 ? "<1%" : pct > 99 ? ">99%" : pct + "%");

  function lean(c) {
    const [side, p] = c.over >= c.under ? ["over", c.over] : ["under", c.under];
    const share = c.push < 1 ? p / (1 - c.push) : 0.5;  // chance among results that do not push
    if (share < 0.54) return "Toss-up, no lean";
    return (share < 0.6 ? "Slight lean " : share < 0.7 ? "Lean " : "Strong lean ") + side;
  }

  // The no-margin American price for a chance: what a bet would pay if the book took no cut.
  function fairPrice(p) {
    if (!(p > 0.005 && p < 0.995)) return "–";
    return p >= 0.5 ? "-" + Math.round(100 * p / (1 - p)) : "+" + Math.round(100 * (1 - p) / p);
  }

  const implied = (american) => (american > 0 ? 100 / (american + 100) : -american / (-american + 100));

  // ---- Saved lines (kept on this device for the week) ------------------------------------------

  function loadSaved() {
    try {
      const s = JSON.parse(localStorage.getItem(STORE) || "null");
      if (s && s.season === data.season && s.week === data.week && s.lines) saved = s;
    } catch (e) { /* storage blocked or empty: lines last for this visit only */ }
    saved.season = data.season; saved.week = data.week;
  }
  function store() {
    try { localStorage.setItem(STORE, JSON.stringify(saved)); } catch (e) { /* see loadSaved */ }
  }
  const key = (row) => row.player_id + "|" + row.prop;
  const entry = (row) => saved.lines[key(row)] || {};
  function setEntry(row, patch) {
    const next = { ...entry(row), ...patch };
    if (next.line == null && !next.pinned && next.overOdds == null && next.underOdds == null) delete saved.lines[key(row)];
    else saved.lines[key(row)] = next;
    store();
  }
  const lineFor = (row) => (entry(row).line != null ? entry(row).line : row.fair_line);

  // ---- Small pieces of markup ------------------------------------------------------------------

  const esc = (s) => String(s).replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
  const fmt = (v) => (Number.isInteger(v) ? String(v) : v.toFixed(1));
  const label = (prop) => (data.props[prop] || {}).label || prop;
  const isCount = (row) => !!row.pmf;

  function result(row, line) {
    const c = chances(row, line);
    const [u, p, o] = wholePercents([c.under, c.push, c.over]);
    const overWins = c.over >= c.under;
    const side = (name, pct, wins) => `<div class="pr-side ${wins ? "wins" : ""}">
      <span class="eyebrow">${name} ${fmt(line)}</span><span class="display num pr-pct">${show(pct)}</span></div>`;
    const graded = row.actual == null ? "" : `<p class="pr-actual num">Actual: <strong>${fmt(row.actual)}</strong> · ${
      row.actual > line ? "over hit" : row.actual < line ? "under hit" : "push"}</p>`;
    return `<div class="pr-sides">${side("Under", u, !overWins)}${side("Over", o, overWins)}</div>
      <div class="pr-bar" role="img" aria-label="Under ${show(u)}, over ${show(o)}${p ? `, push ${show(p)}` : ""}">
        <span style="flex:${Math.max(c.under, 0.004)}" class="u"></span>${c.push > 0.004 ? `<span style="flex:${c.push}" class="p"></span>` : ""}<span style="flex:${Math.max(c.over, 0.004)}" class="o"></span>
      </div>
      <p class="pr-note num">${lean(c)}${p ? ` · push ${show(p)}` : ""}</p>${graded}`;
  }

  // Twenty dots, each 5% of the model's outcomes, on a yard scale. Dots right of the line are overs.
  function dotPlot(row, line) {
    const q = row.q, P = data.probs, W = 320, PAD = 8, R = 5, STEP = 11;
    const lo = Math.min(0, quantile(0.01, q, P)), hi = Math.max(quantile(0.99, q, P), lo + 1);
    const x = (v) => PAD + (W - 2 * PAD) * Math.min(1, Math.max(0, (v - lo) / (hi - lo)));
    const stacks = {};
    let tallest = 1;
    const dots = [];
    for (let i = 0; i < 20; i++) {
      const v = quantile((i + 0.5) / 20, q, P), bin = Math.round(x(v) / STEP);
      stacks[bin] = (stacks[bin] || 0) + 1;
      tallest = Math.max(tallest, stacks[bin]);
      dots.push({ bin, level: stacks[bin], over: v > line });
    }
    const H = Math.max(34, tallest * STEP + 8), base = H - 6;
    const marks = dots.map((d) => `<circle cx="${d.bin * STEP}" cy="${base - R - (d.level - 1) * STEP}" r="${R - 0.5}" class="${d.over ? "over" : "under"}"/>`).join("");
    const lx = x(line), mx = x(row.median);
    const count = dots.filter((d) => d.over).length;
    // Round axis numbers, a small mark for the median, and the line labelled as the line.
    const span = hi - lo, unit = [5, 10, 20, 25, 50, 100, 200].find((u) => span / u <= 5) || 500;
    let ticks = "";
    for (let v = Math.ceil(lo / unit) * unit; v <= hi; v += unit) {
      ticks += `<line x1="${x(v)}" x2="${x(v)}" y1="${base}" y2="${base + 3}" class="axis"/><text x="${x(v)}" y="${H + 17}" text-anchor="middle" class="tick">${v}</text>`;
    }
    const anchor = lx < 46 ? "start" : lx > W - 46 ? "end" : "middle";
    return `<svg viewBox="0 -14 ${W} ${H + 34}" class="pr-dots" role="img" aria-label="${count} of 20 model outcomes are over ${fmt(line)}. The median is ${Math.round(row.median)}.">
      <line x1="${PAD}" x2="${W - PAD}" y1="${base}" y2="${base}" class="axis"/>${ticks}${marks}
      <path d="M${mx - 4} ${base + 9}h8l-4 -6z" class="med"/>
      <line x1="${lx}" x2="${lx}" y1="-2" y2="${base + 3}" class="mark"/>
      <text x="${lx}" y="-5" text-anchor="${anchor}" class="tick lab">line ${fmt(line)}</text>
    </svg>
    <p class="pr-cap">Each dot is 5% of the model's outcomes: filled dots are over the line, hollow dots under. ${count} of 20 land over ${fmt(line)}. The small triangle under the axis marks the median, ${Math.round(row.median)}.</p>`;
  }

  // One bar per whole number, coloured by which side of the line it falls on.
  function countBars(row, line) {
    const total = row.pmf.reduce((a, b) => a + b, 0) || 1, pmf = row.pmf.map((p) => p / total);
    let last = pmf.length - 1;
    while (last > 1 && pmf.slice(last).reduce((a, b) => a + b, 0) < 0.01) last--;
    last = Math.min(last, 12);
    const bars = [];
    for (let k = 0; k <= last; k++) bars.push(k === last ? pmf.slice(k).reduce((a, b) => a + b, 0) : pmf[k]);
    const top = Math.max(...bars);
    return `<div class="pr-bars" role="img" aria-label="Chance of each exact number">${bars.map((p, k) => `
      <div class="${k > line ? "over" : k < line ? "under" : "push"}">
        <span class="num">${p >= 0.005 ? Math.round(p * 100) : "<1"}</span>
        <i style="height:${Math.max(2, 44 * p / top)}px"></i>
        <b class="num">${k}${k === last && last < pmf.length - 1 ? "+" : ""}</b>
      </div>`).join("")}</div>
      <p class="pr-cap">Chance of each exact number, in percent.</p>`;
  }

  function oddsBox(row, line) {
    const e = entry(row), c = chances(row, line);
    const live = c.push < 1 ? 1 - c.push : 1;  // a push returns the stake, so compare on what is left
    const part = (name, odds, p) => {
      if (odds == null || !Number.isFinite(odds) || Math.abs(odds) < 100) return "";
      const need = implied(odds), model = p / live, gap = (model - need) * 100;
      return `<p class="num"><strong>${name} ${odds > 0 ? "+" : ""}${odds}</strong>: break-even ${(need * 100).toFixed(1)}%, model ${Math.round(model * 100)}%.
        The model is ${Math.abs(gap).toFixed(1)} points ${gap >= 0 ? "higher" : "lower"}.</p>`;
    };
    const field = (side, value) => `<label><span class="eyebrow">${side} price</span>
      <input type="text" inputmode="text" autocomplete="off" class="pr-odds" data-odds="${side.toLowerCase()}" value="${value == null ? "" : (value > 0 ? "+" : "") + value}" placeholder="-110" aria-label="${side} price for ${esc(row.player)} ${esc(label(row.prop))}"></label>`;
    return `<div class="pr-oddsrow">${field("Over", e.overOdds)}${field("Under", e.underOdds)}</div>
      <div data-role="oddsout">${part("Over", e.overOdds, c.over)}${part("Under", e.underOdds, c.under)}</div>
      <p class="pr-cap">A gap is a disagreement with the book, not a proven edge. Books are usually closer to right.</p>`;
  }

  function propBlock(row) {
    const k = esc(key(row));
    if (row.prop === "any_td") {
      const p = row.chance != null ? row.chance : 1 - row.pmf[0], pct = Math.round(p * 100), lit = Math.round(p * 10);
      return `<section class="pr-block" data-key="${k}">
        <div class="pr-head"><h3>${esc(label(row.prop))}</h3></div>
        <div class="pr-td"><span class="display num pr-pct">${show(pct)}</span>
          <span class="pr-ten" aria-hidden="true">${Array.from({ length: 10 }, (_, i) => `<i class="${i < lit ? "on" : ""}"></i>`).join("")}</span></div>
        <p class="pr-cap">Scores in about ${lit} of 10 games like this one, when active.</p>
      </section>`;
    }
    const line = lineFor(row), e = entry(row);
    // The props model marks players whose results spread less or more than usual for their workload.
    const NOTE = { steady: "Results bunch closer to the median than usual", "boom or bust": "Results spread wider than usual" };
    const tag = NOTE[row.consistency] ? ` <span class="chip ${row.consistency === "steady" ? "" : "chip-gold"}" title="${NOTE[row.consistency]}">${esc(row.consistency)}</span>` : "";
    const presets = isCount(row) ? "" : `<div class="pr-presets" role="group" aria-label="Quick lines"><span class="eyebrow">Try</span>${[0.25, 0.5, 0.75].map((p) => {
      const v = Math.floor(quantile(p, row.q, data.probs)) + 0.5;
      return `<button type="button" data-act="preset" data-value="${v}">${fmt(v)}</button>`;
    }).join("")}</div>`;
    return `<section class="pr-block" data-key="${k}">
      <div class="pr-head">
        <h3>${esc(label(row.prop))}${tag}</h3>
        <p class="num">median ${Math.round(row.median)} · 8 in 10 games land ${Math.round(Math.max(0, row.low))} to ${Math.round(row.high)}</p>
      </div>
      <div class="pr-entry">
        <button type="button" class="pr-step" data-act="step" data-by="-1" aria-label="Lower the line">−</button>
        <input type="text" inputmode="decimal" autocomplete="off" class="pr-line num" value="${fmt(line)}" aria-label="${esc(label(row.prop))} line for ${esc(row.player)}">
        <button type="button" class="pr-step" data-act="step" data-by="1" aria-label="Raise the line">+</button>
        <button type="button" class="pr-pin ${e.pinned ? "on" : ""}" data-act="pin" aria-pressed="${!!e.pinned}">${e.pinned ? "Saved" : "Save line"}</button>
      </div>
      ${presets}
      <div data-role="result" aria-live="polite">${result(row, line)}</div>
      <div data-role="viz">${isCount(row) ? countBars(row, line) : dotPlot(row, line)}</div>
      <details class="more pr-more" ${e.overOdds != null || e.underOdds != null ? "open" : ""}>
        <summary>Add the book's price</summary>
        <div data-role="odds">${oddsBox(row, line)}</div>
      </details>
    </section>`;
  }

  // Steady and boom-or-bust labels for a player's row, so they show without opening the player.
  const TRAIT_PROPS = { rush_yds: "rushing", rec_yds: "receiving" };
  function traits(p) {
    const chips = Object.keys(TRAIT_PROPS).filter((prop) => p.rows[prop] && ["steady", "boom or bust"].includes(p.rows[prop].consistency))
      .map((prop) => `<span class="chip ${p.rows[prop].consistency === "steady" ? "" : "chip-gold"}">${esc(p.rows[prop].consistency)} · ${TRAIT_PROPS[prop]}</span>`);
    return chips.length ? `<span class="pr-traits">${chips.join("")}</span>` : "";
  }

  function playerRow(p) {
    const head = p.rows[HEADLINE[p.position]] || Object.values(p.rows)[0];
    const headline = head.prop === "any_td" ? `TD ${Math.round(100 * (head.chance || 0))}%` : `${SHORT[head.prop]} ${Math.round(head.median)}`;
    const isOpen = open === p.id;
    return `<article class="panel pr-player ${isOpen ? "open" : ""}" data-player="${esc(p.id)}" data-search="${esc(p.search)}">
      <button type="button" class="pr-row" data-act="toggle" aria-expanded="${isOpen}">
        ${window.teamLogo ? window.teamLogo(p.team) : ""}
        <span class="pr-name"><span class="display">${esc(p.name)}</span><span class="pr-meta">${p.position} · ${p.team} vs ${p.opponent}${p.status ? ` · <strong class="pr-status">${esc(p.status)}</strong>` : ""}</span>${traits(p)}</span>
        <span class="pr-headline num">${headline}</span>
        <span class="pr-chev" aria-hidden="true">${isOpen ? "−" : "+"}</span>
      </button>
      <div class="pr-body">${isOpen ? body(p) : ""}</div>
    </article>`;
  }
  // One prop at a time, with a row of buttons to switch, so an open player is one screen and not five.
  function body(p) {
    const props = ORDER.filter((prop) => p.rows[prop]);
    const head = HEADLINE[p.position];
    if (!props.includes(market)) market = props.includes(head) ? head : props[0];
    const chips = props.length < 2 ? "" : `<div class="pr-chips pr-markets" role="group" aria-label="Prop">${props.map((prop) =>
      `<button type="button" class="pr-chip ${prop === market ? "on" : ""}" data-act="market" data-prop="${prop}" aria-pressed="${prop === market}">${esc(CHIP[prop] || SHORT[prop] || label(prop))}${entry(p.rows[prop]).pinned ? " ✓" : ""}</button>`).join("")}</div>`;
    return chips + propBlock(p.rows[market]);
  }

  function checked() {
    const rows = Object.entries(saved.lines).filter(([, e]) => e.pinned).map(([k]) => {
      const [id, prop] = k.split("|"), p = players.get(id), row = p && p.rows[prop];
      if (!row) return "";
      const line = lineFor(row), c = chances(row, line), over = c.over >= c.under;
      const [u, , o] = wholePercents([c.under, c.push, c.over]);
      return `<li><button type="button" data-act="goto" data-player="${esc(id)}">
          <strong>${esc(p.name)}</strong> ${over ? "over" : "under"} ${fmt(line)} ${esc(label(prop)).toLowerCase()}
          <span class="num">${show(over ? o : u)}</span></button>
        <button type="button" data-act="unpin" data-key="${esc(k)}" aria-label="Remove ${esc(p.name)} ${esc(label(prop))}">×</button></li>`;
    }).join("");
    return rows ? `<div class="panel pr-checked"><p class="eyebrow">Checked lines</p><ul>${rows}</ul></div>` : "";
  }

  // Anytime touchdown chances for the games still to be played, most likely first. It follows the
  // search box and the game filter. Games already played are listed after, with what happened.
  function ranking() {
    const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const rows = [...players.values()].filter((p) => p.rows.any_td && (game === "all" || p.game === game) && words.every((w) => p.search.includes(w)))
      .map((p) => ({ p, row: p.rows.any_td, chance: p.rows.any_td.chance != null ? p.rows.any_td.chance : 1 - p.rows.any_td.pmf[0] }))
      .sort((a, b) => b.chance - a.chance || a.p.name.localeCompare(b.p.name));
    const line = ({ p, row, chance }, i, played) => `<li class="${played ? "played" : ""}">
      <button type="button" class="pr-rankrow" data-act="goto" data-player="${esc(p.id)}">
        <span class="pr-rank-n display num">${played ? "" : i + 1}</span>
        ${window.teamLogo ? window.teamLogo(p.team) : ""}
        <span class="pr-name"><span class="display">${esc(p.name)}</span><span class="pr-meta">${p.position} · ${p.team} vs ${p.opponent}${p.status ? ` · <strong class="pr-status">${esc(p.status)}</strong>` : ""}</span>
          <span class="pr-rank-bar"><i style="width:${Math.round(chance * 100)}%"></i></span></span>
        <span class="pr-rank-num"><span class="display num">${show(Math.round(chance * 100))}</span>
          <span class="pr-meta num">${played ? (row.actual > 0 ? "scored" : "no TD") : "fair " + fairPrice(chance)}</span></span>
      </button></li>`;
    const upcoming = rows.filter((r) => r.row.actual == null), played = rows.filter((r) => r.row.actual != null);
    if (!rows.length) return `<p class="lede">No player matches that search.</p>`;
    const shown = rankAll ? upcoming : upcoming.slice(0, RANK_SHOWN);
    return `<h2 class="day">Anytime touchdown ranking</h2>
      <p class="lede">The model's chance each player scores a rushing or receiving touchdown, most likely first. Fair is the price with no bookmaker margin. Books pay less than fair on most players, and the model has no proven edge. Tap a player for all their props.</p>
      ${upcoming.length ? `<ol class="panel pr-ranklist">${shown.map((r, i) => line(r, i, false)).join("")}</ol>` : `<p class="lede">Every game in this view has been played.</p>`}
      ${upcoming.length > shown.length ? `<button type="button" class="pr-chip mt-3" data-act="rankall">Show all ${upcoming.length}</button>` : ""}
      ${played.length ? `<h2 class="day">Already played</h2><ol class="panel pr-ranklist">${played.map((r, i) => line(r, i, true)).join("")}</ol>` : ""}`;
  }

  // Plays whose game has not kicked off, best first. Pure, so it can be tested.
  function openPlays(file, now) {
    return ((file && file.plays) || []).filter((p) => !p.kickoff || new Date(p.kickoff).getTime() > now)
      .sort((a, b) => b.edge - a.edge);
  }

  const stamp = (iso) => new Date(iso).toLocaleString("en-US", { weekday: "short", hour: "numeric", minute: "2-digit" });
  const PLAY = { rec_yds: "rec yards", receptions: "catches" };
  const signed = (x) => (x >= 0 ? "+" : "−") + show(Math.abs(Math.round(x * 100)));

  // The week's best bets under the betting rules, from FanDuel's prices at the last update. The search
  // box and game filter are hidden in this view, so the list starts near the top of a phone screen.
  function topPlays() {
    const head = "";
    if (!plays) return `${head}<div class="panel p-4"><p class="display text-xl">Live prices are not connected</p>
      <p class="lede mt-2">Top plays compares the model with FanDuel's lines, and no prices were published with this update. Every prop is still under All props.</p></div>`;
    const rows = openPlays(plays, Date.now()).filter((b) => players.has(b.player_id));
    const made = stamp(plays.generated_at);
    const about = `<details class="panel notice pr-notice text-sm leading-relaxed"><summary><span><strong class="text-primary">Small edge, not proven.</strong> <span class="muted">${esc(plays.book)} prices from ${made}.</span></span></summary>
      <p class="muted">These are ${esc(plays.book)} receiving yards and receptions unders, the only props where the model held up against real prices. A play needs the model's chance to beat the price by ${Math.round(plays.rules.min_model_edge * 100)}% or more, with at most one per player and ${plays.rules.per_game} per game. Edge is the expected return per $1 after leaning the model most of the way toward the book. In testing these rules returned about 3% to 7%, and a losing season is normal. The system bets a flat stake on up to ${plays.rules.week_bets} a week. Lines move: check the line and price in the app. Skip the bet if the line is lower than shown or the price is worse than the skip number. Tap a play to see the player's full range.</p></details>`;
    if (!rows.length) return `${head}${about}<div class="panel p-4"><p class="display text-xl">No plays right now</p>
      <p class="lede mt-2">${plays.games_priced ? `Nothing passed the rules at ${esc(plays.book)}'s prices for the ${plays.games_priced} of ${plays.games_open} games with lines posted.` : `${esc(plays.book)} had not posted receiving lines when this was checked.`} Lines usually appear a day or two before kickoff, and this list is rebuilt before each kickoff slot.</p></div>`;
    const shown = rankAll ? rows : rows.slice(0, TOP_SHOWN);
    const line = (b, i) => { const p = players.get(b.player_id); return `<li>
      <button type="button" class="pr-rankrow pr-play" data-act="goto" data-player="${esc(p.id)}" data-prop="${esc(b.prop)}" data-line="${b.line}">
        <span class="pr-rank-n display num">${i + 1}</span>
        ${window.teamLogo ? window.teamLogo(p.team) : ""}
        <span class="pr-name"><span class="display">${esc(p.name)}</span>
          <span class="pr-bet num">Under ${fmt(b.line)} ${esc(PLAY[b.prop] || label(b.prop).toLowerCase())} <strong>${esc(b.price)}</strong></span>
          <span class="pr-meta num">${p.team} vs ${p.opponent}${b.kickoff ? " · " + stamp(b.kickoff) : ""}${p.status ? ` · <strong class="pr-status">${esc(p.status)}</strong>` : ""}</span>
          <span class="pr-meta num">Model ${show(Math.round(b.p_model * 100))} · book ${show(Math.round(b.p_book * 100))}</span>
          <span class="pr-meta num">Skip if worse than ${esc(b.worst_price)}</span></span>
        <span class="pr-rank-num"><span class="display num">${signed(b.edge)}</span><span class="pr-meta">edge</span></span>
      </button></li>`; };
    return `${head}${about}
      <ol class="panel pr-ranklist">${shown.map(line).join("")}</ol>
      ${rows.length > shown.length ? `<button type="button" class="pr-chip mt-3" data-act="rankall">Show all ${rows.length}</button>` : ""}`;
  }

  // ---- Page ------------------------------------------------------------------------------------

  function games() {
    const cards = (site.weeks && site.weeks[String(data.week)]) || [];
    const when = Object.fromEntries(cards.map((c) => [c.game_id, c]));
    const ids = [...new Set(data.rows.map((r) => r.game_id))];
    return ids.map((id) => {
      const [, , away, home] = id.split("_"), c = when[id] || {};
      const day = data.rows.find((r) => r.game_id === id).gameday;
      return { id, away, home, day, time: c.gametime || "", final: !!c.final };
    }).sort((a, b) => (a.day + a.time + a.id).localeCompare(b.day + b.time + b.id));
  }

  function render() {
    const root = document.getElementById("props"), list = games();
    const made = new Date(data.generated_at).toLocaleString("en-US", { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
    const chip = (id, text, dim) => `<button type="button" class="pr-chip ${game === id ? "on" : ""} ${dim ? "dim" : ""}" data-act="game" data-game="${id}">${text}</button>`;
    if (!unfolded.size && list.length) unfolded.add((list.find((g) => !g.final) || list[0]).id);  // start with the next game open
    root.innerHTML = `
      <div data-role="checked">${checked()}</div>
      <div class="pr-tools">
        <label class="sr-only" for="pr-search">Search for a player or team</label>
        <input id="pr-search" type="search" class="pr-search" placeholder="Player or team" autocomplete="off" value="${esc(query)}">
      </div>
      <div class="pr-filters">
        <div class="pr-views" role="group" aria-label="View">
          <button type="button" class="${view === "top" ? "on" : ""}" data-act="view" data-view="top" aria-pressed="${view === "top"}">Top plays</button>
          <button type="button" class="${view === "players" ? "on" : ""}" data-act="view" data-view="players" aria-pressed="${view === "players"}">All props</button>
          <button type="button" class="${view === "td" ? "on" : ""}" data-act="view" data-view="td" aria-pressed="${view === "td"}">Touchdowns</button>
        </div>
        <div class="pr-chips" role="group" aria-label="Game">${chip("all", "All games")}${list.map((g) => chip(g.id, `${g.away} @ ${g.home}`, g.final)).join("")}</div>
      </div>
      <details class="panel notice pr-notice text-sm leading-relaxed" data-role="notice"><summary><span><strong class="text-primary">Model only, no sportsbook lines.</strong> <span class="muted" data-role="hint">Tap a game, then a player, then set the line.</span></span></summary>
        <p class="muted">Week ${data.week} props, updated ${made}. Type the line your book shows and the page gives the model's chance. A book's line usually sits near the model's median, so expect numbers close to 50%. This is not proof of an edge. Players may be inactive: check before you bet. Players marked steady or boom or bust have results that spread less or more than usual; search "steady" or "boom" to list them.</p></details>
      <div data-role="rank" hidden></div>
      ${list.map((g) => {
        const day = window.dayLabel ? window.dayLabel(g.day) : g.day, time = g.time && window.kickoff ? " · " + window.kickoff(g.time) : "";
        const group = [...players.values()].filter((p) => p.game === g.id)
          .sort((a, b) => POSITIONS.indexOf(a.position) - POSITIONS.indexOf(b.position) || (a.team === g.away ? -1 : 1) - (b.team === g.away ? -1 : 1) || b.weight - a.weight);
        return `<section class="pr-game" data-game="${g.id}">
          <h2 class="day"><button type="button" class="pr-fold" data-act="fold" data-game="${g.id}" aria-expanded="false">
            <span class="pr-match">${g.away} @ ${g.home}</span><span class="pr-when">${day}${time}${g.final ? " · final" : ""} · <span class="pr-count num"></span></span></button></h2>
          <div class="pr-gbody grid grid-cols-1 gap-2 md:grid-cols-2">${group.map(playerRow).join("")}</div>
        </section>`;
      }).join("")}
      <p class="lede pr-empty" hidden>No player matches that search.</p>
      ${backtest()}`;
    filter();
  }

  function backtest() {
    const b = data.backtest;
    if (!b) return `<p class="pr-cap mt-8">A backtest summary for each prop will appear here when the props model is final.</p>`;
    const rows = ORDER.filter((prop) => b[prop]).map((prop) => {
      const r = b[prop], cell = (v, f) => (v == null ? "–" : f(v));
      const miss = (v) => v.toFixed(v < 10 ? 2 : 1);
      return `<tr><td>${esc(label(prop))}</td><td class="text-right">${cell(r.n, (v) => v.toLocaleString())}</td>
        <td class="text-right">${cell(r.mae, miss)}</td><td class="text-right">${cell(r.mae_baseline, miss)}</td>
        <td class="text-right">${cell(r.coverage_80, (v) => Math.round(v * 100) + "%")}</td></tr>`;
    }).join("");
    return `<h2 class="day">How reliable is it?</h2>
      <p class="lede">Tested on ${data.backtest_seasons ? `the ${data.backtest_seasons[0]} to ${data.backtest_seasons[1]} seasons` : "past seasons"} without looking ahead. The "most games" range should hold about 80% of results.</p>
      <div class="panel overflow-x-auto" tabindex="0" role="region" aria-label="How reliable each prop is"><table class="table table-sm num"><thead><tr><th scope="col">Prop</th><th scope="col" class="text-right">Games</th>
        <th scope="col" class="text-right">Model's miss</th><th scope="col" class="text-right">Miss using his average</th><th scope="col" class="text-right">Range held</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }

  function filter() {
    const root = document.getElementById("props"), words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const rank = root.querySelector('[data-role="rank"]'), td = view !== "players";  // a ranked view hides the game list
    rank.hidden = !td;
    rank.innerHTML = view === "td" ? ranking() : view === "top" ? topPlays() : "";
    root.querySelector('[data-role="hint"]').hidden = td;
    root.querySelector('[data-role="notice"]').hidden = view === "top";
    root.classList.toggle("pr-top", view === "top");
    root.querySelectorAll(".pr-views button").forEach((b) => { const on = b.dataset.view === view; b.classList.toggle("on", on); b.setAttribute("aria-pressed", String(on)); });
    let any = false;
    root.querySelectorAll(".pr-game").forEach((section) => {
      if (td) { section.hidden = true; return; }
      let shown = 0;
      const inGame = game === "all" || section.dataset.game === game;
      section.querySelectorAll(".pr-player").forEach((el) => {
        const hit = inGame && words.every((w) => el.dataset.search.includes(w));
        el.hidden = !hit;
        if (hit) shown++;
      });
      section.hidden = !shown;
      any = any || shown > 0;
      // Folded unless picked: a search or a single game always shows its players.
      const openNow = words.length > 0 || game !== "all" || unfolded.has(section.dataset.game);
      section.querySelector(".pr-gbody").hidden = !openNow;
      const fold = section.querySelector(".pr-fold");
      fold.setAttribute("aria-expanded", String(openNow));
      fold.querySelector(".pr-count").textContent = `${shown} player${shown === 1 ? "" : "s"}`;
    });
    root.querySelector(".pr-empty").hidden = any || td;
  }

  function parseLine(text) {
    const v = parseFloat(String(text).replace(",", "."));
    return Number.isFinite(v) && v >= 0 && v < 1000 ? Math.round(v * 2) / 2 : null;
  }

  function refresh(block, row, line, { keepInput } = {}) {
    if (!keepInput) block.querySelector(".pr-line").value = fmt(line);
    block.querySelector('[data-role="result"]').innerHTML = result(row, line);
    block.querySelector('[data-role="viz"]').innerHTML = isCount(row) ? countBars(row, line) : dotPlot(row, line);
    const out = block.querySelector('[data-role="odds"]');
    if (out && !out.contains(document.activeElement)) out.innerHTML = oddsBox(row, line);
    document.querySelector('#props [data-role="checked"]').innerHTML = checked();
  }

  function rowOf(el) {
    const block = el.closest(".pr-block");
    if (!block) return {};
    const [id, prop] = block.dataset.key.split("|");
    return { block, row: players.get(id).rows[prop] };
  }

  function toggle(id, scroll) {
    const root = document.getElementById("props");
    const was = open && root.querySelector(`.pr-player[data-player="${CSS.escape(open)}"]`);
    open = open === id ? null : id;
    market = null;
    if (was) was.outerHTML = playerRow(players.get(was.dataset.player));
    const el = open && root.querySelector(`.pr-player[data-player="${CSS.escape(open)}"]`);
    if (el) {
      el.outerHTML = playerRow(players.get(open));
      const now = root.querySelector(`.pr-player[data-player="${CSS.escape(open)}"]`);
      if (scroll && now) now.scrollIntoView({ block: "start", behavior: "smooth" });
    }
    filter();
    const btn = root.querySelector(`.pr-player[data-player="${CSS.escape(id)}"] .pr-row`);
    if (btn && !scroll && document.activeElement === document.body) btn.focus({ preventScroll: true });
  }

  function onClick(event) {
    const el = event.target.closest("[data-act]");
    if (!el) return;
    const act = el.dataset.act;
    if (act === "toggle") return toggle(el.closest(".pr-player").dataset.player);
    if (act === "game") { game = el.dataset.game; document.querySelectorAll(".pr-chip").forEach((c) => c.classList.toggle("on", c.dataset.game === game)); return filter(); }
    if (act === "view") { view = el.dataset.view; rankAll = false; return filter(); }
    if (act === "market") {
      market = el.dataset.prop;
      const card = el.closest(".pr-player");
      card.querySelector(".pr-body").innerHTML = body(players.get(card.dataset.player));
      const on = card.querySelector(".pr-markets .on");
      on.scrollIntoView({ block: "nearest", inline: "nearest" });
      return on.focus({ preventScroll: true });
    }
    if (act === "fold") {
      const id = el.dataset.game;
      if (game !== "all" || query.trim()) return;  // already showing everything that matches
      unfolded.has(id) ? unfolded.delete(id) : unfolded.add(id);
      return filter();
    }
    if (act === "rankall") { rankAll = true; return filter(); }
    if (act === "goto") {
      game = "all"; query = ""; view = "players";
      document.getElementById("pr-search").value = "";
      document.querySelectorAll(".pr-chip").forEach((c) => c.classList.toggle("on", c.dataset.game === "all"));
      if (open === el.dataset.player) open = null;
      unfolded.add(players.get(el.dataset.player).game);
      const pick = players.get(el.dataset.player).rows[el.dataset.prop];
      if (pick) setEntry(pick, { line: Number(el.dataset.line) });  // a top play opens on the book's line
      toggle(el.dataset.player, true);
      if (pick) {
        market = el.dataset.prop;
        const card = document.querySelector(`#props .pr-player[data-player="${CSS.escape(el.dataset.player)}"] .pr-body`);
        if (card) card.innerHTML = body(players.get(el.dataset.player));
      }
      // The player's card only has a place on the page once the game list is showing again.
      const target = document.querySelector(`#props .pr-player[data-player="${CSS.escape(el.dataset.player)}"]`);
      if (target) requestAnimationFrame(() => target.scrollIntoView({ block: "start", behavior: "smooth" }));
      return;
    }
    if (act === "unpin") {
      const [id, prop] = el.dataset.key.split("|"), row = players.get(id).rows[prop];
      setEntry(row, { pinned: false });
      const block = document.querySelector(`.pr-block[data-key="${CSS.escape(el.dataset.key)}"]`);
      if (block) { const pin = block.querySelector(".pr-pin"); pin.classList.remove("on"); pin.textContent = "Save line"; pin.setAttribute("aria-pressed", "false"); }
      const box = document.querySelector('#props [data-role="checked"]');
      box.innerHTML = checked();
      // Keep keyboard focus: the next saved line, or the search box when none are left.
      (box.querySelector('[data-act="unpin"]') || document.getElementById("pr-search")).focus({ preventScroll: true });
      return;
    }
    const { block, row } = rowOf(el);
    if (!row) return;
    if (act === "step" || act === "preset") {
      const line = act === "preset" ? Number(el.dataset.value) : Math.max(0, lineFor(row) + Number(el.dataset.by));
      setEntry(row, { line });
      refresh(block, row, line);
    } else if (act === "pin") {
      const pinned = !entry(row).pinned;
      setEntry(row, { pinned, line: lineFor(row) });
      el.classList.toggle("on", pinned); el.textContent = pinned ? "Saved" : "Save line"; el.setAttribute("aria-pressed", String(pinned));
      document.querySelector('#props [data-role="checked"]').innerHTML = checked();
    }
  }

  let searchTimer;
  function onInput(event) {
    const el = event.target;
    if (el.id === "pr-search") {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => {
        query = el.value;
        const root = document.getElementById("props"), searching = !!query.trim();
        root.classList.toggle("searching", searching);
        filter();
        if (!searching) return;
        // Bring the results right under the search box, and open the player if only one matches.
        const hits = [...root.querySelectorAll(".pr-player")].filter((p) => !p.hidden);
        if (view === "players" && hits.length === 1 && open !== hits[0].dataset.player) toggle(hits[0].dataset.player);
        const tools = root.querySelector(".pr-tools"), first = [...root.querySelectorAll(".pr-game")].find((g) => !g.hidden) || root.querySelector(".pr-empty");
        const under = document.getElementById("tabs").offsetHeight + tools.offsetHeight + 8;
        scrollTo(0, Math.max(0, first.getBoundingClientRect().top + scrollY - under));
      }, 160);
      return;
    }
    const { block, row } = rowOf(el);
    if (!row) return;
    if (el.classList.contains("pr-line")) {
      const line = parseLine(el.value);
      if (line == null) return;
      setEntry(row, { line });
      refresh(block, row, line, { keepInput: true });
    } else if (el.classList.contains("pr-odds")) {
      const v = parseInt(el.value.replace(/[^\d+-]/g, ""), 10);
      setEntry(row, { [el.dataset.odds + "Odds"]: Number.isFinite(v) ? v : null });
      const line = lineFor(row), tmp = document.createElement("div");
      tmp.innerHTML = oddsBox(row, line);
      block.querySelector('[data-role="oddsout"]').innerHTML = tmp.querySelector('[data-role="oddsout"]').innerHTML;
    }
  }

  function index() {
    players = new Map();
    for (const row of data.rows) {
      let p = players.get(row.player_id);
      if (!p) {
        p = { id: row.player_id, name: row.player, team: row.team, opponent: row.opponent, position: row.position,
              game: row.game_id, rows: {}, weight: 0, status: row.status || "",
              search: `${row.player} ${row.team} ${row.position} ${(window.TEAM_NAMES || {})[row.team] || ""}`.toLowerCase() };
        players.set(row.player_id, p);
      }
      p.rows[row.prop] = row;
      if (row.consistency && row.consistency !== "typical") p.search += " " + row.consistency;
      if (row.prop === HEADLINE[p.position]) p.weight = row.median;
    }
  }

  let started = false;
  function openTab(siteData) {
    if (started) return;
    started = true;
    site = siteData;
    const root = document.getElementById("props");
    root.innerHTML = `<p class="lede">Loading props…</p>`;
    // Top plays need live sportsbook prices. Without them the tab opens on the full list, as before.
    const prices = fetch("data/plays.json", { cache: "no-store" }).then((r) => (r.ok ? r.json() : null)).catch(() => null);
    Promise.all([fetch("data/props.json", { cache: "no-store" }).then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); }), prices])
      .then(([json, top]) => {
        data = json;
        plays = top && top.season === json.season && top.week === json.week && Array.isArray(top.plays) ? top : null;
        view = plays ? "top" : "players";
        index(); loadSaved(); render();
        root.addEventListener("click", onClick);
        root.addEventListener("input", onInput);
        root.addEventListener("focusin", (e) => { if (e.target.classList.contains("pr-line")) e.target.select(); });
        root.addEventListener("focusout", (e) => {
          if (!e.target.classList.contains("pr-line")) return;
          const { row } = rowOf(e.target);
          if (row) e.target.value = fmt(lineFor(row));
        });
      })
      .catch(() => {
        started = false;
        root.innerHTML = `<div class="panel p-4"><p class="display text-2xl">Props are not published yet</p>
          <p class="lede mt-2">The player props file could not be loaded. It is rebuilt with the rest of the page, so try again later.</p>
          <button type="button" class="pr-chip on mt-3" id="pr-retry">Try again</button></div>`;
        document.getElementById("pr-retry").addEventListener("click", () => openTab(siteData));
      });
  }

  return { open: openTab, _test: { openPlays, cdf, quantile, chances, wholePercents, implied, fairPrice, setData: (d) => { data = d; } } };
})();

if (typeof module !== "undefined") module.exports = Props;
