# CLAUDE.md

Guidance for Claude Code sessions working in this repo.

## What this is

AceTag: a native Android (Kotlin) app that keeps an inventory of 3D printer filament spools, and
writes NFC tags in the Anycubic ACE Pro's proprietary spool format for filament that didn't come
with one — entirely from the phone, no PC/USB reader. See README.md for the tag format details.

**Two workflows, and almost every design decision follows from which one you're in:**

1. **Anycubic filament** — the spool comes with its own tag. Scan it, confirm, it's in the
   inventory. Nothing is written. If you already own that colour and material, the confirmation
   says so; it's an *additional spool*, not a duplicate to resolve.
2. **Anything else** — no tag, so we write our own. The ACE reads whichever side of the spool
   faces it, so this needs **two stickers, one per side** — identical payloads, one inventory
   row. The write is all-or-nothing: nothing is saved until both are written, because a
   half-tagged spool works only one way up.

**The spool is the countable object and it is the row.** Three black PLAs are three rows. Never
add a quantity column or collapse identical spools into one record.

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
- `FilamentMaterial.kt` — material as a **base + finish** pair, and the table of which pairs
  Anycubic actually sells. **Only a SKU Anycubic issued may ever reach a sticker**: the ACE
  validates that field, so an invented SKU produces a spool the printer may refuse, discovered
  only after the sticker is on. Pairs with no SKU (wood, carbon fibre, and for now Marble/Galaxy/
  Metallic, whose SKUs aren't published) write the *base material* and keep the finish in the
  database. `FilamentMaterialTest` locks that down — every base×finish pair must produce a SKU
  from the issued set. If you add a material, add its SKU only from a real spool dump.
- `TagIo.kt` / `NfcActivity.kt` — whole-tag read/write on top of `Type2Tag`'s page primitives, and
  the foreground-dispatch boilerplate. Screens deal in specs and UIDs, never byte offsets.
- `ScanActivity` — reads one tag and routes, no disambiguating dialogs. Known UID → that spool's
  detail screen. Unknown UID that decodes → `AddSpoolActivity` (workflow 1). Unknown UID with no
  spool data → offer to start a custom spool.
- `AddSpoolActivity` — the workflow 1 confirmation. Read-only; every field came off the tag.
- `CustomSpoolActivity` — workflow 2, plus editing, because it's the same form and editing a spec
  is what makes stickers go stale. Three modes by Intent extra: CREATE / EDIT / REWRITE.
- `SpoolDetailActivity` — **"used it up" and "added by mistake" are two separate buttons on
  purpose.** Using a spool up records a CONSUMED event; removing a mistake deletes the row *and*
  its ADDED event, because filament you never bought must not appear in the history. Do not merge
  them into one delete.
- `data/` — Room (SQLite), `acetag2.db` at version 2, with real migrations — the inventory is a
  record of filament someone actually bought, so never reach for destructive fallback. `spools` is
  **current inventory only**; `spool_events` is the append-only history (ADDED / CONSUMED), carrying a full
  denormalized snapshot because the spool row is gone by the time a CONSUMED event is read. All
  mutations go through `SpoolRepository` so a row change and its event stay in one transaction.

  **The tag-matching rule:** never *infer* which spool a tag belongs to — not by matching specs,
  not by `groupId`, not by "open slot" heuristics. That was tried; it silently merged separate
  spools into one row and undercounted the inventory. Exact UID lookup is not inference and is
  fine: a custom spool records both its sticker UIDs at write time, so `findByTagUid` is a key
  lookup. `groupId` is part of the tag byte format only; never match on it.
- `data/SpoolJson.kt` — versioned JSON export (`SPOOL_SCHEMA_VERSION`, currently 6, exporting
  `{spools, events}`). `type` is what the tag says; `materialName`/`finish` are what the filament
  is. Keep both — they differ exactly where the tag couldn't carry the finish. Forward-looking: there is no import/ingest side yet (no web app, no
  server). When one gets built, this schema is the contract — bump the version and keep old
  fields readable rather than silently reshaping it.

## Working style for this project

- This app was built and verified interactively against a real device (Pixel 9 Pro XL) via adb —
  screenshots, uiautomator dumps, and live NFC writes/reads, not just "it compiles." Prefer that
  standard when touching write/read/decode paths: a build that type-checks is not enough
  confidence for anything that touches the NFC byte format.
- No release signing / Play Store distribution set up yet — debug builds only, sideloaded via adb.
- Keep it simple: this is a small personal/community tool, not a product. Don't add
  infrastructure (CI, signing, Play Store metadata, etc.) unless asked.
