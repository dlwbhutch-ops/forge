# HOUSE 19 — Literal Forge Replay Status

Date: 2026-08-29

## Requested job
Run the current 19 active Commander decks card-by-card through Forge using the August 29 balanced HOUSE schedule, where every unique deck pair shares a pod exactly three times per complete gauntlet.

## Integrity rule
A result is "literal Forge" only after the Forge Commander rules engine actually completes the game and writes a real log. No calibrated matchup model is substituted for Forge.

## Current preflight
- Active decks staged: **19 / 19**.
- Exact named-card 100-card Forge `.dck` exports: **19 / 19**.
- Balanced schedule: 19 rounds, 95 multiplayer pods per complete gauntlet.
- Unique deck pairs: 171; every pair meets exactly 3 times per complete gauntlet.
- Literal Forge games completed: **0**.

## August 29 slot resolution
- Mathematics — Omnath, Locus of All: **Blaze (BBD #167, foil)** retained/restored in the former unresolved slot.
- Omnath, Locus of Mana: **Owlbear Cub (CLB #246)** retained/restored in the former unresolved slot.
- Both lists independently recount to exactly 100 cards and now have all card names identified.

## Remaining runtime blocker
- Java available locally: OpenJDK 21.0.11.
- Forge 2.0.14 runtime jar available locally: **NO**.
- SourceForge release page is reachable through web retrieval, but sandbox/container binary transfer remains blocked by outbound DNS/download restrictions.
- Therefore no Forge rules-engine game can truthfully be claimed yet.

## Strict runner behavior
`run_house19_forge.py` refuses to start unless:
1. a real Forge runtime jar is supplied through `FORGE_JAR` / `--forge-jar`,
2. all 19 manifest rows are `EXACT`,
3. all 19 `.dck` files exist,
4. the 95-pod schedule verifies every pair exactly three times.

The deck-data gate now passes 19/19. Only the runtime is missing.
