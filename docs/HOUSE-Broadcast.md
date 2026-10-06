# HOUSE Commander Lab broadcast viewer

## Recovered app preferences

These choices come from the shared “Tournament Engine Update” conversation:

- Show an overhead table with all four players visible at once.
- Give attention to the active player's turn and to responses to that player's actions.
- Give every token a recognizable visual identity; Warriors, Wizards, and other roles must differ.
- Support the existing Android, Windows, and Mac app.

The existing HOUSE data remains authoritative: the bundled 19-deck roster,
the 95-pod schedule, deck imports and history, literal Forge results, and saved
tournament progress. Viewer preferences do not rewrite these files.

## Viewer controls

The Watch page shows the four seats in registration order, clockwise. Eliminated
players retain their seats. Gold marks the active turn; cyan marks priority or
a response. Attack arrows are coral, block arrows cyan, and spell targets gold.
The middle lane shows the phase, focus state, and top stack entry. The existing
Stack panel shows the full stack. Click or tap a card to zoom; click or tap the
rest of a player's panel to inspect every grouped permanent and public zone.

On Android, **Full table** opens a full-screen viewer. Pause, Live, and Step affect
only the spectator buffer. Forge continues resolving the game.

On Android, **Watch current game** attaches to the running tournament or pilot
game. It opens the live table without starting another game. If Android stopped
the app process, the saved run is shown as **INTERRUPTED** and **Run / resume**
continues from the next uncompleted pod. Completed games, standings, imported
decks, and the selected roster are preserved. A saved RUNNING label alone is
not treated as evidence that Forge is still running.

The technical Forge log follows the current game in every mode, including
gauntlets. Viewer snapshot failures are recorded separately in a `.viewer.log`
sidecar and surfaced in the Watch status; they do not substitute game results.

**Viewer settings** saves response focus, arrows, animation, and token-art
preferences to `broadcast.properties` in the app's data directory. Defaults
enable the features requested in the shared conversation. This file can be
copied between platforms; settings stored only on another device cannot
be recovered from a shared conversation.

## Token coverage and art

`.github/scripts/build-house-token-registry.py` reads every file in the checked-in
Forge `tokenscripts` directory. The current catalog includes **839 definitions**.
The build regenerates the catalog whenever the rules bundle is packaged.
Variable and star power/toughness stay strings instead of being forced to zero.

Both platforms draw the same original vector token illustrations locally.
Species and roles select the illustration; the full runtime identity determines
its color and constellation. This is a procedural illustration system, not a
collection of hundreds of individually commissioned paintings. No image server
is needed for tokens. Named copies can use source-card art when available.
Tokens created dynamically use their actual runtime name, types, colors,
keywords, and power/toughness rather than an “Unknown token” label.

## Verification

After bundling rules, build with `mvn -pl forge-gui-desktop -am package`. Run
`bash .github/scripts/test-house-broadcast.sh` for catalog coverage, token swarm
grouping, fixed seats, response focus, immutable state, preference persistence,
and offline rendering of every token. It writes desktop and phone-sized preview
PNGs under `forge-gui-desktop/target`. Existing literal-game smoke gates verify
that real Forge games and the bundled custom roster load and advance.

The viewer presents actual Forge state. It does not replace game outcomes with
estimated win probabilities or assign invented tournament results.
