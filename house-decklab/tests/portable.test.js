"use strict";
const assert = require("node:assert/strict");
const test = require("node:test");
const fs = require("node:fs");
const path = require("node:path");
const ROOT = path.resolve(__dirname, "..", "..");
const ASSETS = path.join(ROOT, "forge-gui-android", "assets", "house-decklab");
const H = require(path.join(ASSETS, "core.js"));
test("shared browser assets are packaged once for Android and desktop", () => {
  for (const file of ["index.html", "core.js", "app.js", "examples/demo.js"]) {
    assert.ok(fs.statSync(path.join(ASSETS,file)).size > 100);
  }
  const pom = fs.readFileSync(path.join(ROOT, "forge-gui-desktop/pom.xml"), "utf8");
  assert.match(pom, /<include>house-decklab\/\*\*<\/include>/);
  assert.match(fs.readFileSync(path.join(ROOT, "forge-gui-android/src/main/AndroidManifest.xml"), "utf8"), /DeckLabActivity/);
  assert.match(fs.readFileSync(path.join(ROOT, "forge-gui-android/src/com/housecommander/lab/MainActivity.java"), "utf8"), /Open Deck Lab/);
  assert.match(fs.readFileSync(path.join(ROOT, "forge-gui-desktop/src/main/java/com/housecommander/desktop/HouseDesktopMain.java"), "utf8"), /openDeckLab\(\)/);
});
test("same role analyzer on any OS and immutable proposals", () => {
  const deck = H.parseDeck("[metadata]\nName=Example\n[commander]\n1 Garth One-Eye\n[main]\n99 Forest\n");
  const before = JSON.stringify(deck);
  assert.equal(deck.total, 100);
  const index = H.indexOracle([]);
  const analysis = H.analyze(deck,index,["untap"],["Garth One-Eye"]);
  assert.equal(analysis.total,100);
  assert.deepEqual(H.recommend({analysis,oracle:index}).swaps, []);
  assert.equal(JSON.stringify(deck),before);
});
test("native export takes SAF route; browser remains ordinary file export", () => {
  const app = fs.readFileSync(path.join(ASSETS, "app.js"), "utf8");
  assert.match(app, /HOUSE_DECKLAB_HOST\.exportDeck/);
  assert.match(app, /URL\.createObjectURL/);
  const android = fs.readFileSync(path.join(ROOT, "forge-gui-android/src/com/housecommander/lab/DeckLabActivity.java"), "utf8");
  assert.match(android, /ACTION_CREATE_DOCUMENT/);
  assert.match(android, /ACTION_OPEN_DOCUMENT/);
  assert.match(android, /setAllowUniversalAccessFromFileURLs\(false\)/);
});
