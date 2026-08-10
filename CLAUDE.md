# CLAUDE.md

Guidance for Claude Code sessions working in this repo.

## What this is

AceTag: a native Android (Kotlin) app that writes NFC tags in the Anycubic ACE Pro's proprietary
filament-spool format, entirely from the phone (no PC/USB reader), and keeps a local inventory of
what's been written. See README.md for the full feature list and tag format details.

## Build environment

- No system Java on this machine — use Android Studio's bundled JDK:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- Android SDK: `export ANDROID_HOME="$HOME/Library/Android/sdk"` (platform 35, build-tools 34
  installed as of this writing).
- Build: `./gradlew assembleDebug` from repo root.
- Install/run: needs a **physical Android device** with NFC — the emulator can't do NFC, and the
  camera color picker also wants a real camera. `adb install -r
  app/build/outputs/apk/debug/app-debug.apk`, then `adb shell am start -n
  com.jamieduncan.acetag/.InventoryActivity`.
- Screen timeout: if testing over adb leaves the screen asleep, `adb shell settings put system
  screen_off_timeout <ms>` — but always ask before touching lock-screen/keyguard state on a
  personal device, and restore the original timeout value when done.

## Architecture

- `SpoolTag.kt` — the actual tag format: `buildTag(Spec): Pages` encodes, `decode(Pages): Spec?`
  decodes. This is the one file that has to stay byte-exact with the reverse-engineered Anycubic
  format (see README's "Tag format" section). Verify any change here against the known-good page
  dumps in the format documentation before trusting it.
- `WriteSpoolActivity` — one Activity handles three flows (CREATE / IMPORT / REPRINT) selected by
  which Intent extras are present. See the class doc comment before adding a fourth flow; consider
  whether it actually needs to be a fourth mode of this Activity or its own screen.
- `ReadTagActivity` — reads a tag, decodes it, and either opens a matching inventory row, offers
  to attach it as a spool's second tag (if there's exactly one open-slot match), or hands off to
  `WriteSpoolActivity` in IMPORT mode.
- `data/` — Room (SQLite). `SpoolEntity.tagUidA`/`tagUidB` are the physical NFC tag UIDs, which is
  how a scanned tag gets linked back to an inventory row — not a synthetic/written ID.
- `data/SpoolJson.kt` — versioned JSON export (`SPOOL_SCHEMA_VERSION`). This is forward-looking:
  there is no import/ingest side yet (no web app, no server). When one gets built, this schema is
  the contract — bump the version and keep old fields readable rather than silently reshaping it.

## Working style for this project

- This app was built and verified interactively against a real device (Pixel 9 Pro XL) via adb —
  screenshots, uiautomator dumps, and live NFC writes/reads, not just "it compiles." Prefer that
  standard when touching write/read/decode paths: a build that type-checks is not enough
  confidence for anything that touches the NFC byte format.
- No release signing / Play Store distribution set up yet — debug builds only, sideloaded via adb.
- Keep it simple: this is a small personal/community tool, not a product. Don't add
  infrastructure (CI, signing, Play Store metadata, etc.) unless asked.
