# HOUSE Commander Lab — cross-platform architecture

HOUSE Commander Lab must remain usable on **Android, macOS, and Windows**.

## Product flow

**Deck Library → Tournament Builder → Forge Match Engine → Game Viewer → Results / Analytics**

The platform UI may differ, but deck identity, deck versions, tournament inputs, and
results must mean the same thing everywhere.

## Shared core

The new `house-commander-core` Maven module is pure Java 17 and deliberately has no
Android, Swing, AWT, or operating-system dependencies.

It owns:

- stable deck IDs;
- immutable deck versions;
- exact deck-content hashes;
- archive state;
- structural import parsing for Forge `.dck` and pasted lists;
- tournament rosters pinned to exact deck versions;
- a portable HOUSE library codec.

Both `forge-gui-android` and `forge-gui-desktop` depend on the same shared module.

## Portable library

A HOUSE library file preserves the stable deck ID and every version of a list. This is
what lets the same deck move between phone and computer without becoming a different
analytics identity.

Forge remains the authority for real card names, scripts, legality, and literal game
execution. The shared parser only performs structural import checks.

## Platform shells

### Android

Android keeps its foreground tournament service, touch-first UI, and app-specific file
storage. It will read/write the shared HOUSE library format and pass selected exact deck
versions to ForgeBridge.

### macOS

The 0.9.2 Endurance runner remains the current literal-game baseline: isolated worker
JVM per game, progress-aware watchdog, retry queue, and per-game checkpoint. The desktop
shell will migrate from a Mac-specific data path to a desktop storage adapter while
retaining Endurance behavior.

### Windows

Windows will use the same desktop shell and shared core as macOS. Packaging will be
native to Windows, with a bundled Java runtime, while the Forge/tournament semantics stay
identical.

## Build invariant

The shared core is tested on GitHub-hosted Linux, Windows, and macOS runners. Platform
packaging can evolve independently without forking the HOUSE data model.
