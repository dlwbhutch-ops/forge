/* HOUSE Deck Lab b256 — deterministic analysis, no app-data mutation. */
(function (root, factory) {
  const api = factory();
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.HouseDeckLab = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const GOALS = Object.freeze({ mana: 10, flow: 10, interaction: 10, protection: 8 });
  const norm = v => String(v || "").normalize("NFKC").trim().toLowerCase()
    .replace(/[’‘]/g, "'").replace(/\s+/g, " ");
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

  function csv(text) {
    const out = []; let row = [], field = "", quoted = false;
    text = String(text || "").replace(/^\uFEFF/, "");
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quoted) {
        if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
        else if (c === '"') quoted = false;
        else field += c;
      } else if (c === '"') quoted = true;
      else if (c === ",") { row.push(field); field = ""; }
      else if (c === "\n" || c === "\r") {
        if (c === "\r" && text[i + 1] === "\n") i++;
        row.push(field); field = "";
        if (row.some(x => x.trim())) out.push(row);
        row = [];
      } else field += c;
    }
    if (quoted) throw new Error("CSV has an unclosed quoted field.");
    row.push(field);
    if (row.some(x => x.trim())) out.push(row);
    return out;
  }

  function csvObjects(text) {
    const rows = csv(text), header = (rows.shift() || []).map(x => x.trim());
    return rows.map(row => Object.fromEntries(header.map((key, i) => [key, row[i] || ""])));
  }

  function parseDeck(text) {
    let section = "main", name = "", records = [];
    for (const raw of String(text || "").split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith("#") || line.startsWith("// ")) continue;
      const heading = line.match(/^\[([^\]]+)\]$/);
      if (heading) { section = norm(heading[1]); continue; }
      if (/^name\s*=/i.test(line)) { name = line.slice(line.indexOf("=") + 1).trim(); continue; }
      if (/^(commander|deck|sideboard|mainboard)$/i.test(line)) { section = norm(line); continue; }
      if (line.includes("=") && !/^\d+\s/.test(line)) continue;
      if (/^(sideboard|maybeboard|metadata|general)$/i.test(section)) continue;
      const m = line.match(/^(?:(\d+)\s*[xX]?\s+)?(.+?)\s*$/);
      if (!m) continue;
      const qty = Number(m[1] || 1);
      const card = m[2].replace(/\s+\([A-Za-z0-9]{2,8}\)\s*(?:\d+\w?)?$/, "").trim();
      if (!card || qty < 1 || qty > 100) continue;
      if (/^commander$/i.test(card)) continue;
      records.push({ name: card, qty, section: section === "commander" ? "commander" : "main" });
    }
    const total = records.reduce((sum, r) => sum + r.qty, 0);
    return { name, cards: records, total,
      commander: records.filter(r => r.section === "commander").map(r => r.name),
      warnings: total !== 100 ? ["Deck contains " + total + " cards (expected 100 for standard Commander)."] : [] };
  }

  function inventory(text) {
    const rows = csvObjects(text); const counts = new Map();
    for (const row of rows) {
      const name = row.Name || row.name || row["Card Name"] || row.card;
      const qty = Number(row.Quantity || row.quantity || row.Qty || 1);
      if (!name || !Number.isFinite(qty) || qty < 0) continue;
      const key = norm(name);
      counts.set(key, (counts.get(key) || 0) + qty);
    }
    return counts;
  }

  function allocation(text) {
    const rows = csvObjects(text), counts = new Map();
    for (const row of rows) {
      const name = row.card || row.Card || row.Name || row.name;
      const deck = row.deck || row.Deck;
      const qty = Number(row.quantity || row.Quantity || row.qty || 1);
      if (!name || !deck || !Number.isFinite(qty) || qty <= 0) continue;
      const k = norm(name);
      if (!counts.has(k)) counts.set(k, new Map());
      counts.get(k).set(norm(deck), (counts.get(k).get(norm(deck)) || 0) + qty);
    }
    return counts;
  }

  function gauntlet(text) {
    return csvObjects(text).map(r => ({
      deck: r.deck, wins: Number(r.pod_wins), games: Number(r.games),
      rate: Number(r.games) ? Number(r.pod_wins) / Number(r.games) : 0
    })).filter(r => r.deck && Number.isFinite(r.wins) && r.games > 0);
  }

  function indexOracle(cards) {
    const m = new Map();
    for (const c of cards || []) {
      if (!c || !c.name || !c.type_line) continue;
      m.set(norm(c.name), c);
      if (Array.isArray(c.card_faces))
        for (const face of c.card_faces) if (face.name) m.set(norm(face.name), c);
    }
    return m;
  }

  function features(card, themeWords) {
    if (!card) return null;
    const faces = card.card_faces || [];
    const oracle = [card.oracle_text, ...faces.map(f => f.oracle_text)].filter(Boolean).join(" ").toLowerCase();
    const type = [card.type_line, ...faces.map(f => f.type_line)].filter(Boolean).join(" ");
    const mana = /\badd\s+\{[cwubrg0-9]/i.test(oracle)
      || /\bsearch your library for (?:a|up to \w+) (?:basic )?land card.*(?:onto the battlefield|put it onto the battlefield)/i.test(oracle);
    const flow = /\bdraw (?:a|one|two|three|four|five|x|\d+) cards?\b/i.test(oracle)
      || /\b(?:exile|look at) the top .{0,75}(?:may play|may cast|into your hand)\b/i.test(oracle);
    const interaction = /\b(?:destroy target|exile target|counter target|return target .{0,60} to (?:its|their|the) owner's hand|deals? \d+ damage to any target)\b/i.test(oracle);
    const protection = /\b(?:hexproof|indestructible|phase out|phases out|protection from)\b/i.test(oracle);
    const words = (themeWords || []).map(norm).filter(x => x.length >= 3);
    const hits = words.filter(t => norm(oracle + " " + type).includes(t));
    return {
      land: /\bland\b/i.test(type), mana, flow, interaction, protection,
      themeHits: hits, cmc: Number(card.cmc || 0),
      name: card.name, type, oracle, colors: card.color_identity || [],
      legal: card.legalities ? card.legalities.commander === "legal" : null
    };
  }

  function analyze(deck, oracle, themes = [], pinned = []) {
    const missing = [], f = [], counts = { mana: 0, flow: 0, interaction: 0, protection: 0, lands: 0 };
    const protect = new Set(pinned.map(norm));
    for (const r of deck.cards) {
      const metadata = oracle.get(norm(r.name));
      if (!metadata) missing.push(r.name);
      const data = features(metadata, themes);
      if (data) {
        for (const role of ["mana", "flow", "interaction", "protection"]) if (data[role]) counts[role] += r.qty;
        if (data.land) counts.lands += r.qty;
      }
      f.push({ ...r, meta: data, pinned: protect.has(norm(r.name)) || r.section === "commander" });
    }
    const deficits = Object.fromEntries(Object.entries(GOALS).map(([k, goal]) => [k, Math.max(0, goal - counts[k])]));
    const known = f.filter(r => r.meta).reduce((sum, r) => sum + r.qty, 0);
    return { deck, cards: f, counts, deficits, missing,
      covered: known, total: deck.total, coverage: deck.total ? known / deck.total : 0,
      warnings: [
        ...deck.warnings,
        ...(missing.length ? [missing.length + " card names lack verified Oracle metadata. Role totals are minimum known values, not complete deck counts."] : [])
      ] };
  }

  function availableCopies(name, inventoryCounts, alloc, selectedName) {
    const key = norm(name), owned = inventoryCounts.get(key) || 0;
    if (!alloc) return { owned, free: null, verified: false };
    const used = [...(alloc.get(key) || new Map()).entries()]
      .filter(([deck]) => deck !== norm(selectedName)).reduce((s, [, n]) => s + n, 0);
    return { owned, free: Math.max(0, owned - used), verified: true };
  }

  function recommend({ analysis: a, oracle, owned = new Map(), allocations = null,
    themes = [], pinned = [], limit = 10, allowUnowned = false }) {
    if (a.coverage < 0.8) return { swaps: [], warning: "Import verified Oracle data for at least 80% of this deck before card replacements." };
    if (!a.deck.commander.length) return { swaps: [], warning: "Set the commander in the imported decklist before checking color identity." };
    const commanderCards = a.deck.commander.map(n => oracle.get(norm(n)));
    if (commanderCards.some(x => !x || !Array.isArray(x.color_identity)))
      return { swaps: [], warning: "Commander color identity is unverified. Swaps are blocked." };
    const commanderColors = new Set(commanderCards.flatMap(c => c.color_identity));
    const inDeck = new Set(a.deck.cards.map(r => norm(r.name)));
    const locked = new Set(pinned.map(norm));
    const roleKeys = Object.keys(GOALS);
    const score = f => {
      const theme = Math.min(3, f.themeHits.length) * 3.5;
      const roles = roleKeys.reduce((s, role) => s + (f[role] ? (a.deficits[role] > 0 ? 4.5 : 1.1) : 0), 0);
      const curve = f.land ? 0 : Math.max(-2, (5 - f.cmc) * 0.35);
      return theme + roles + curve;
    };
    const cuts = a.cards.filter(r => !r.pinned && !locked.has(norm(r.name)) && r.meta && !r.meta.land);
    const options = [];
    for (const [key, meta] of oracle) {
      if (key !== norm(meta.name) || inDeck.has(key) || !meta.color_identity) continue;
      const fit = features(meta, themes);
      if (!fit || fit.land || fit.legal !== true || !fit.colors.every(c => commanderColors.has(c))) continue;
      const stock = availableCopies(meta.name, owned, allocations, a.deck.name);
      if (!allowUnowned && (stock.owned < 1 || (stock.verified && stock.free < 1))) continue;
      for (const cut of cuts) {
        // Preserve a needed role if removing its only contributing card.
        const erasedNeeded = roleKeys.some(role => cut.meta[role] && a.counts[role] <= GOALS[role] && !fit[role]);
        if (erasedNeeded) continue;
        const delta = score(fit) - score(cut.meta);
        const themeImprovement = fit.themeHits.length > cut.meta.themeHits.length;
        const roleImprovement = roleKeys.some(role => a.deficits[role] > 0 && fit[role] && !cut.meta[role]);
        if (delta < 2.4 || !(roleImprovement || themeImprovement)) continue;
        options.push({
          add: meta.name, remove: cut.name, delta: Math.round(delta * 10) / 10,
          owned: stock.owned, free: stock.free, allocationVerified: stock.verified,
          rationale: [
            ...(roleKeys.filter(role => a.deficits[role] > 0 && fit[role] && !cut.meta[role])
              .map(role => "fills " + role + " gap")),
            ...(themeImprovement ? ["stronger alignment with: " + fit.themeHits.join(", ")] : [])
          ].join("; "),
          note: "Heuristic proposal — card strength, interactions and AI execution require Forge testing."
        });
      }
    }
    options.sort((a, b) => b.delta - a.delta || a.add.localeCompare(b.add) || a.remove.localeCompare(b.remove));
    const uniqueAdd = new Set(), uniqueRemove = new Set(), swaps = [];
    for (const item of options) {
      if (uniqueAdd.has(norm(item.add)) || uniqueRemove.has(norm(item.remove))) continue;
      swaps.push(item); uniqueAdd.add(norm(item.add)); uniqueRemove.add(norm(item.remove));
      if (swaps.length >= limit) break;
    }
    return { swaps, warning: allocations ? "" : "Card ownership verified, but other HOUSE deck allocations were not provided. Check availability before swapping." };
  }

  function proposedDeck(deck, swaps) {
    const next = deck.cards.map(r => ({ ...r }));
    for (const s of swaps) {
      const remove = next.find(r => norm(r.name) === norm(s.remove) && r.section !== "commander");
      if (!remove || remove.qty < 1 || next.some(r => norm(r.name) === norm(s.add))) throw new Error("Invalid swap: " + s.add + " / " + s.remove);
      remove.qty--;
      next.push({ name: s.add, qty: 1, section: "main" });
    }
    const lines = ["[metadata]", "Name=" + (deck.name || "HOUSE Deck Lab Proposal"), "[general]", "Commander", "[commander]"];
    for (const r of next.filter(r => r.section === "commander" && r.qty > 0)) lines.push(r.qty + " " + r.name);
    lines.push("[main]");
    for (const r of next.filter(r => r.section !== "commander" && r.qty > 0)) lines.push(r.qty + " " + r.name);
    return lines.join("\n") + "\n";
  }

  return { norm, csv, csvObjects, parseDeck, inventory, allocation, gauntlet, indexOracle,
    features, analyze, availableCopies, recommend, proposedDeck, GOALS, sleep };
});
