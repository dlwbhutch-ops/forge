HOUSE Commander Lab 0.18 Unified Distribution

ONE APP / ONE CODEBASE
HOUSE Commander Lab uses the same Forge rules bridge, deck format, tournament
engine, live battlefield, assisted pilot logic, and results model across all
supported packages.

SUPPORTED RELEASE PACKAGES
- Windows x64
- macOS Intel
- macOS Apple Silicon
- Linux x64
- Android APK

Desktop packages include a Java 17 runtime. No separate Java installation is
required when the complete archive is extracted.

FEATURES INCLUDED
- Literal Forge Commander games
- Deck Library with .dck and ManaBox/plain-text import
- Versioned deck replacement/restore
- Variable-size tournament rosters and persistent checkpoints
- Results and gauntlet tracking
- Four-seat broadcast table with active-turn and response focus
- Real exact-print card art with local on-demand cache
- Card zoom, tapped rotation, pile badges, elimination animation
- Token-safe battlefield grouping
- Offline token illustrations covering all 839 bundled Forge definitions
- Saved response-focus, arrow, animation, and token-art preferences
- Assisted Play vs AI with one HOUSE-controlled seat and three Forge AI seats

INSTALL

Windows:
1. Extract the complete Windows archive.
2. Run HOUSE-Commander-Lab.cmd.
3. Keep the runtime folder beside the JAR and launcher.

macOS:
1. Choose the Apple Silicon or Intel archive that matches the Mac.
2. Extract the complete archive.
3. Right-click HOUSE-Commander-Lab.command and choose Open the first time if
   macOS security blocks a normal double-click.
4. Keep the runtime folder beside the JAR and launcher.

Linux:
1. Extract the complete Linux archive.
2. Run: chmod +x HOUSE-Commander-Lab.sh
3. Run: ./HOUSE-Commander-Lab.sh

Android:
1. Extract the unified release bundle if needed.
2. Install HOUSE-Commander-Lab-0.18-Android.apk.
3. Android may require permission to install an APK from the app used to open it.

DATA
Desktop HOUSE data remains under the current user's home directory in:
.house-commander-lab

Existing deck-library, roster, result, checkpoint, and card-art-cache data are
therefore reused when upgrading from earlier HOUSE desktop releases.

Use Viewer settings to customize response focus, arrows, animation, and token
illustrations. Preferences are saved in broadcast.properties in the app's data
directory. That file can also be copied between Android and desktop.

CARD ART / OFFLINE PLAY
Card art is optional. HOUSE downloads exact-print images on demand and caches
them locally. Missing images or no network connection never block literal Forge
gameplay.

RELEASE INTEGRITY
RELEASE-MANIFEST.txt records the exact Git commit used for every package.
SHA256SUMS.txt contains package checksums.

iOS
The repository retains an iOS compatibility audit, but 0.18 does not claim a
native installable iOS client. The supported installable targets in this release
are Windows, macOS, Linux, and Android.
