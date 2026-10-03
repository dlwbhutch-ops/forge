HOUSE Commander Lab 0.13 Live Battlefield

Requires Java 17 or newer.

macOS:
1. Unzip the package.
2. Double-click HOUSE-Commander-Lab.command.
3. If macOS blocks it, right-click the file and choose Open.
4. If Terminal reports permission denied, run:
   chmod +x HOUSE-Commander-Lab.command

Windows:
1. Unzip the package.
2. Double-click HOUSE-Commander-Lab.cmd.

HOUSE data is stored in:
~/.house-commander-lab

The Deck Library may contain more than 19 decks. The active HOUSE tournament
roster remains exactly 19 decks so the 95-pod triple-round-robin schedule stays
mathematically identical to the Android version.

The desktop shell uses the same HOUSE core deck/roster classes and the same
literal Forge bridge/watchdog source as Android.

0.10 adds exact 100-card deck viewing, imported-deck replacement, archived
version history with restore, and safe imported-deck removal.

0.11 accepts Forge .dck, ManaBox text exports, and simple 100-card text lists.
For a plain list with no headings, place the commander first. HOUSE converts
the text into a Forge .dck and keeps the same version-management safeguards.


0.12 combines Decks, Tournament, Results, Watch, and Play into one HOUSE
application shell on desktop and Android. Watch uses the same literal Forge engine
and streams the test-game event log in-app. Play is reserved in the same shell for
the upcoming human-seat decision bridge; it will not require a second application.


0.13 adds immutable live Forge game-state snapshots and renders a four-player
spectator battlefield inside the same HOUSE app. The Watch view shows turn/phase,
active player, life/poison, hand/library counts, public zones, battlefield
permanents, tap/token/P-T state, and the live stack while retaining the raw Forge
event log for diagnostics. The UI never reads the mutable Forge game directly.
