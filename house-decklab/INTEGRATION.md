# HOUSE Deck Lab b256 — native launcher integration prototype

**Source:** `dlwbhutch-ops/house-private/decklab/b256` (single shared JavaScript analysis and UI).
**Consumer:** the existing `dlwbhutch-ops/forge` HOUSE Android and desktop applications on the `feature/house-b257-shared-decklab-20261017` branch.

The four browser files in `forge-gui-android/assets/house-decklab/` are directly sourced from house-private. Android loads the same files using a **private, unexported** WebView Activity. Desktop Maven embeds those *same* assets under `housecommander/house-decklab/` in the cross-platform desktop JAR. Windows and macOS open a local extracted copy in the default browser. **There are not three separate deck-analysis algorithms.**

Android uses Storage Access Framework OPEN_DOCUMENT for deck/collection imports and CREATE_DOCUMENT for user-reviewed proposal export. It does not write to installed deck files, app data, tournaments or pilot history. External navigation is blocked in the privileged Android WebView. Browser metadata fetch may be limited by network/CORS; offline verified JSON import is always available.

**Not yet a release.** The Forge development branch is older than the b254/b255 binary patch. Do **not** install its Android build over b254 without confirming package identity, signer identity, versionCode and data preservation. No real Android device tests, Windows installer tests or macOS launch/notarization tests have been completed. This integration has not been merged to Forge master or to a signed HOUSE build.

## Test before merge
- Run `node --test house-decklab/tests/portable.test.js` (Node >=22).
- Verify desktop JAR contains `housecommander/house-decklab/index.html`, `app.js`, `core.js`, `examples/demo.js`.
- Verify Android APK has `assets/house-decklab/index.html` etc and `DeckLabActivity` in the merged manifest.
- On Android, open Deck Lab from Library, import a DCK and collection CSV, import verified Oracle JSON, export proposal via document picker; back out and ensure the original HOUSE game, decks, saved pods and history remain intact.
- On Windows and macOS, launch Deck Lab from the Decks pane, confirm it opens the local browser workbench and downloads proposals without changing installed decks.
- Re-run gameplay: start/resume/finish Player vs AI; target selection, stack, turn, save and 116-pod tournament checkpointing. Existing freeze problems are **not** fixed by this Deck Lab integration.

These changes are reversible launcher + asset modifications only; no rework of Forge gameplay.
