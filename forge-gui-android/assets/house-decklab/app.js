/* HOUSE b256 portable Deck Lab UI. No writes to gameplay or inventory. */
(function () {
  "use strict";
  const H = window.HouseDeckLab;
  const $ = id => document.getElementById(id);
  const safe = value => String(value == null ? "" : value).replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const rows = H.gauntlet(window.HOUSE_GAUNTLET_CSV).sort((a, b) => b.rate - a.rate || a.deck.localeCompare(b.deck));
  const names = rows.map(r => r.deck);
  const presets = [
    { includes: "garth", theme: "untap, activated ability, mana, copy", pin: "Garth One-Eye\nIntruder Alarm\nQuest for Renewal\nInstill Energy" },
    { includes: "ulalek", theme: "Eldrazi, cast, copy", pin: "Ulalek, Fused Atrocity" },
    { includes: "infinite guideline", theme: "artifact, token, counter", pin: "Infinite Guideline Station" },
    { includes: "jace", theme: "planeswalker, spell, proliferate", pin: "Jace, Multiverse Architect" },
    { includes: "aang", theme: "elemental, land, counter", pin: "Aang, Master of Elements" }
  ];
  const state = {
    active: "Garth One-Eye", deck: null, source: "", owned: new Map(), allocations: null,
    oracle: new Map(), analysis: null, suggestions: [], coverageNote: ""
  };
  const readText = id => {
    const f = $(id).files && $(id).files[0];
    return f ? f.text() : Promise.resolve(null);
  };
  function setStatus(message, isWarning) {
    $("status").innerHTML = '<p class="' + (isWarning ? 'warn' : 'okay') + '">' + safe(message) + "</p>";
  }
  function presetFor(name) {
    const p = presets.find(x => H.norm(name).includes(x.includes));
    $("theme").value = p ? p.theme : "";
    $("pins").value = p ? p.pin : "";
  }
  function showSelected() {
    const r = rows.find(x => x.deck === state.active);
    $("rate").textContent = r ? (100 * r.rate).toFixed(1) + "%" : "—";
    $("record").textContent = r ? r.wins + "/" + r.games : "—";
    $("total").textContent = state.deck ? state.deck.total : "—";
    $("coverage").textContent = state.analysis ? Math.round(100 * state.analysis.coverage) + "%" : "—";
    $("baseline").textContent = r ? r.wins + " wins in " + r.games + " Forge pods · 25% four-player benchmark. Performance is a small-sample observation, not a forecast." : "";
    const sampleAvailable = ["Garth One-Eye", "Aang, Master of Elements — Avatar State", "Jace, Multiverse Architect"].includes(state.active);
    $("demoGarth").disabled = !sampleAvailable;
    $("demoGarth").textContent = sampleAvailable ? "Load provisional " + state.active.split(",")[0].split(" ")[0] + " list" : "No provisional list available";
  }
  function resetAnalysis() {
    state.analysis = null; state.suggestions = [];
    $("recommendations").className = "empty";
    $("recommendations").textContent = "Analyze a confirmed 100-card list and verified card data to unlock proposals.";
    $("diagnostics").className = "empty";
    $("diagnostics").textContent = state.deck ? "List loaded. Analyze to see card-role coverage and gaps." : "No card-for-card list loaded for this deck.";
    $("export").disabled = true;
    showSelected();
  }
  function loadSample(name) {
    const examples = {
      "Garth One-Eye": window.HOUSE_GARTH_PROVISIONAL,
      "Aang, Master of Elements — Avatar State": window.HOUSE_AANG_PROVISIONAL,
      "Jace, Multiverse Architect": window.HOUSE_JACE_PROVISIONAL
    };
    const sample = examples[name];
    if (!sample) { setStatus("No provisional 100-card example is bundled for this deck. Import its current list.", true); return; }
    $("deckSelect").value = name;
    state.active = name;
    state.deck = H.parseDeck(sample);
    state.source = "Provisional HOUSE Library source list — may not match current installed deck";
    presetFor(state.active);
    resetAnalysis();
    setStatus("Loaded provisional " + name + " list. Import the latest approved deck before adopting changes.", true);
  }
  function diag() {
    if (!state.deck) { resetAnalysis(); return; }
    const themes = $("theme").value.split(",").map(x => x.trim()).filter(Boolean);
    const pinned = $("pins").value.split(/\r?\n/).map(x => x.trim()).filter(Boolean);
    const a = H.analyze(state.deck, state.oracle, themes, pinned);
    state.analysis = a;
    const d = a.deficits;
    const counts = a.counts;
    const show = role => '<div class="chip"><b>' + safe(role) + "</b>: " + counts[role] +
      " verified / " + H.GOALS[role] + " target " + (d[role] ? " · estimated gap " + d[role] : " · meets baseline") + "</div>";
    const warnings = a.warnings.concat(state.source.includes("Provisional") ? ["This source deck is provisional. Confirm its current 100 cards before applying changes."] : []);
    $("diagnostics").className = "";
    $("diagnostics").innerHTML =
      '<p class="note"><b>Source:</b> ' + safe(state.source) + '</p>' +
      '<p>Verified card metadata: <b>' + a.covered + ' / ' + a.total + '</b> (' + Math.round(100 * a.coverage) +
      '%). Known lands: <b>' + counts.lands + '</b>. Commander: <b>' + safe(a.deck.commander.join(" + ") || "UNSPECIFIED") + '</b>.</p>' +
      '<div class="chips">' + Object.keys(H.GOALS).map(show).join("") + '</div>' +
      (warnings.length ? '<p class="warn">' + warnings.map(safe).join("<br>") + '</p>' : '') +
      '<p class="note">Role detection is conservative and based only on verified Oracle text. One card can cover several roles. Targets are diagnostic guides—not deck-building laws. Missing metadata makes totals incomplete.</p>';
    showSelected();
    const r = H.recommend({ analysis: a, oracle: state.oracle, owned: state.owned,
      allocations: state.allocations, themes, pinned, limit: 12 });
    state.suggestions = r.swaps;
    $("export").disabled = true;
    if (!r.swaps.length) {
      $("recommendations").className = "empty";
      $("recommendations").textContent = r.warning || "No well-supported owned swaps met the identity and role-preservation guardrails. Consider verifying more candidate cards or changing the theme tags.";
      return;
    }
    $("recommendations").className = "";
    $("recommendations").innerHTML = (r.warning ? '<p class="warn">' + safe(r.warning) + "</p>" : "") +
      r.swaps.map((s, i) => '<label class="swap"><input type="checkbox" class="swapCheck" data-swap="' + i + '">' +
        '<span><strong>ADD: ' + safe(s.add) + '</strong> <span class="cut"> / REMOVE: ' + safe(s.remove) + '</span>' +
        '<small>' + safe(s.rationale) + ' · heuristic fit +' + safe(s.delta) + '</small>' +
        '<small>Collection copies: ' + safe(s.owned) + '; other-deck availability: ' +
        (s.allocationVerified ? safe(s.free) + " free" : "NOT VERIFIED") + '</small>' +
        '<small>' + safe(s.note) + '</small></span></label>').join("");
    document.querySelectorAll(".swapCheck").forEach(e => e.addEventListener("change", () => {
      $("export").disabled = !document.querySelector(".swapCheck:checked");
    }));
  }
  function download(name, content) {
    // Native Android host stores exported proposals through Storage Access Framework.
    if (window.HOUSE_DECKLAB_HOST && typeof window.HOUSE_DECKLAB_HOST.exportDeck === "function") {
      window.HOUSE_DECKLAB_HOST.exportDeck(name, content);
      return;
    }
    const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
    const href = URL.createObjectURL(blob), a = document.createElement("a");
    a.href = href; a.download = name; document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(href), 1000);
  }
  async function onlineSync() {
    if (!state.deck) { setStatus("Load a decklist first.", true); return; }
    if (!window.fetch) { setStatus("Network fetch is unavailable. Import a local Oracle JSON file instead.", true); return; }
    const select = state.deck.cards.map(r => r.name);
    const allOwned = [...state.owned.keys()];
    const needs = [...new Set([...select, ...allOwned.slice(0, 450)].map(x => H.norm(x)))]
      .filter(x => !state.oracle.has(x));
    if (!needs.length) { setStatus("All currently selected card names have metadata."); return; }
    $("sync").disabled = true;
    const collected = [...new Set([...state.oracle.values()])];
    let success = 0, unknown = 0;
    try {
      for (let offset = 0; offset < needs.length; offset += 75) {
        const batch = needs.slice(offset, offset + 75);
        setStatus("Querying verified card metadata " + Math.min(offset + 75, needs.length) + "/" + needs.length + "…");
        const resp = await fetch("https://api.scryfall.com/cards/collection", {
          method: "POST", headers: { "Content-Type": "application/json", "Accept": "application/json" },
          body: JSON.stringify({ identifiers: batch.map(name => ({ name })) })
        });
        if (!resp.ok) throw new Error("Scryfall HTTP " + resp.status + "; stop and retry later, or import local Oracle JSON.");
        const data = await resp.json();
        if (!Array.isArray(data.data)) throw new Error("Unexpected Scryfall response.");
        collected.push(...data.data); success += data.data.length;
        unknown += Array.isArray(data.not_found) ? data.not_found.length : 0;
        if (offset + 75 < needs.length) await H.sleep(550);
      }
      state.oracle = H.indexOracle(collected);
      setStatus("Loaded " + success + " verified cards; " + unknown + " names unresolved. Online inventory candidates are sampled (max 450), not exhaustive." , unknown > 0);
      diag();
    } catch (err) {
      state.oracle = H.indexOracle(collected);
      diag();
      setStatus("Rules sync incomplete: " + (err && err.message || err) + ". Partial metadata retained; no rules guessed.", true);
    } finally { $("sync").disabled = false; }
  }
  $("deckSelect").innerHTML = names.map(n => '<option value="' + safe(n) + '">' + safe(n) + '</option>').join("");
  $("deckSelect").addEventListener("change", () => {
    state.active = $("deckSelect").value; state.deck = null; state.source = "";
    presetFor(state.active); resetAnalysis();
    setStatus("Selected " + state.active + ". Import its current 100-card list.", true);
  });
  $("demoGarth").addEventListener("click", () => loadSample(state.active));
  $("deckFile").addEventListener("change", async () => {
    try {
      const txt = await readText("deckFile"); if (!txt) return;
      state.deck = H.parseDeck(txt); state.source = $("deckFile").files[0].name + " (user-supplied)";
      resetAnalysis(); setStatus("Deck imported. Card count: " + state.deck.total + ". Originals remain untouched.", state.deck.total !== 100);
    } catch (err) { setStatus("Could not parse decklist: " + err.message, true); }
  });
  $("collectionFile").addEventListener("change", async () => {
    try {
      const txt = await readText("collectionFile"); if (!txt) return;
      state.owned = H.inventory(txt); resetAnalysis();
      setStatus("Loaded " + state.owned.size + " unique names from inventory; allocations may still be unverified.");
    } catch (err) { setStatus("Could not import collection: " + err.message, true); }
  });
  $("allocationFile").addEventListener("change", async () => {
    try {
      const txt = await readText("allocationFile"); if (!txt) return;
      state.allocations = H.allocation(txt); resetAnalysis();
      setStatus("Loaded allocation records for " + state.allocations.size + " names; cross-deck stock can be screened.");
    } catch (err) { setStatus("Could not import allocations: " + err.message, true); }
  });
  $("oracleFile").addEventListener("change", async () => {
    try {
      const f = $("oracleFile").files[0]; if (!f) return;
      if (f.size > 250 * 1024 * 1024) setStatus("Large Oracle JSON file; processing may take a while.");
      const payload = JSON.parse(await f.text());
      const cards = Array.isArray(payload) ? payload : Array.isArray(payload.data) ? payload.data : null;
      if (!cards) throw new Error("Expected an array of Scryfall card objects or {data:[…]}.");
      state.oracle = H.indexOracle(cards); resetAnalysis();
      setStatus("Loaded Oracle metadata for " + state.oracle.size + " canonical names/faces. Ready to analyze.");
    } catch (err) { setStatus("Could not import Oracle JSON: " + err.message, true); }
  });
  $("sync").addEventListener("click", onlineSync);
  $("analyze").addEventListener("click", () => {
    if (!state.deck) { setStatus("Import this deck's current list before analysis.", true); return; }
    diag(); setStatus("Analysis refreshed. Suggestions are proposals only; review all swaps before exporting.");
  });
  $("export").addEventListener("click", () => {
    if (!state.deck) return;
    const chosen = [...document.querySelectorAll(".swapCheck:checked")]
      .map(e => state.suggestions[Number(e.dataset.swap)]);
    if (!chosen.length) return;
    try {
      const content = H.proposedDeck(state.deck, chosen);
      if (H.parseDeck(content).total !== state.deck.total) throw new Error("Export total differs from original.");
      download("HOUSE_" + state.active.replace(/[^A-Za-z0-9]+/g, "_") + "_PROPOSED.dck", content);
      setStatus("Exported an alternative list with " + chosen.length + " selected changes. The original deck remains unchanged.");
    } catch (err) { setStatus(err.message, true); }
  });
  $("deckSelect").value = "Garth One-Eye";
  loadSample("Garth One-Eye");
})();
