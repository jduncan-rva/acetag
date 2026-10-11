# CLAUDE.md

Guidance for Claude Code sessions working in this repo.

## What this is

AceTag: a native Android (Kotlin) app that keeps an inventory of 3D printer filament spools, and
writes NFC tags in the Anycubic ACE Pro's proprietary spool format for filament that didn't come
with one — entirely from the phone, no PC/USB reader. See README.md for the tag format details.

**Three ways a spool gets in, and almost every design decision follows from which one you're in:**

1. **Anycubic filament** — the spool comes with its own tag. Scan it, confirm, it's in the
   inventory. Nothing is written. If you already own that colour and material, the confirmation
   says so; it's an *additional spool*, not a duplicate to resolve.
2. **Anything else** — no tag, so we write our own. The ACE reads whichever side of the spool
   faces it, so this needs **two stickers, one per side** — identical payloads, one inventory
   row. The write is all-or-nothing: nothing is saved until both are written, because a
   half-tagged spool works only one way up.
3. **Filament that can't hold a tag** — anything under 1 kg doesn't fit the ACE's rollers, and on
   an adapter it no longer lines up with the reader; a refill has no spool at all. Added with no
   stickers written, and tagged later.

**The spool is the countable object and it is the row.** Three black PLAs are three rows. Never
add a quantity column or collapse identical spools into one record.

**Tags are movable, and a spool with none is normal.** Case 3 means the stickers live on reusable
hardware — an adapter, a reused spool — that different filament passes through, so a pair of
stickers is *on* one spool now and can be *moved* to another. Both UID columns are nullable and an
untagged spool is ordinary inventory, not a broken record: it's on the shelf and counted, the
printer just can't see it yet.

Consequences worth not relearning:

- **A move is never silent.** A sticker owned by another spool isn't refused — moving it is the
  point — but the app names that spool and asks first, then says afterwards which spool lost its
  tags. Quietly making a spool unprintable is the failure mode this whole design guards against.
- **Taking one sticker of a pair takes both.** Two stickers are one label spread over two sides; a
  spool left holding half of one is a spool the printer can't read but the app thinks is fine.
- **The confirmation costs a re-tap, unavoidably.** An Android `Tag` goes stale the moment it
  leaves the field, so the app cannot write *after* a dialog. It asks, and the next tap of that
  sticker goes through. Approval is per spool, so the pair's second sticker doesn't ask again.
  Don't "fix" this by pre-warning without naming the spool — naming it is the entire value.
- **Weight drives length.** The ACE counts remaining filament down from what the tag claims, so a
  250 g spool written with a 1 kg length is wrong from the first gram. `SpoolTag.lengthForWeight`
  scales from the material's own defaults and the form fills the field in as you type.

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
  dumps in the format documentation before trusting it. `isCustomWritten` reads the `0x4D` marker
  at page `0x27` byte 3 — present on everything this app writes, absent from every genuine dump —
  which is what lets a scan tell "a spool I've never seen" from "a sticker I wrote that's on no
  spool". `lengthForWeight` is arithmetic over `MATERIAL_DEFAULTS`, not part of the byte format.
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
  detail screen. **Unknown UID we wrote** → a sticker on no spool: offer it to the spools waiting
  for tags. Unknown UID that decodes → `AddSpoolActivity` (case 1). Unknown UID with no spool data
  → offer it to a spool, or to start a new one. The "we wrote it" check must stay *before* the
  decode check: a released sticker still decodes and would otherwise read as a spool you don't own.
- `AddSpoolActivity` — the case 1 confirmation. Read-only; every field came off the tag.
- `CustomSpoolActivity` — cases 2 and 3, plus editing, because it's the same form and editing a
  spec is what makes stickers go stale. Three modes by Intent extra: CREATE / EDIT / WRITE, where
  WRITE covers both a rewrite and a first set of tags for a spool added without any. "Just add it
  to my inventory" sits beside the write button rather than being a third button on the inventory
  screen — the choice only means anything once you're looking at a filled-in form.
- `SpoolDetailActivity` — **"used it up" and "added by mistake" are two separate buttons on
  purpose.** Using a spool up records a CONSUMED event; removing a mistake deletes the row *and*
  its ADDED event, because filament you never bought must not appear in the history. Do not merge
  them into one delete. Using a spool up also frees its stickers, silently and without a separate
  release step — the row is deleted, so the UIDs go with it.
- `data/` — Room (SQLite), `acetag2.db` at version 3, with real migrations — the inventory is a
  record of filament someone actually bought, so never reach for destructive fallback. `spools` is
  **current inventory only**; `spool_events` is the append-only history (ADDED / CONSUMED), carrying a full
  denormalized snapshot because the spool row is gone by the time a CONSUMED event is read. All
  mutations go through `SpoolRepository` so a row change and its event stay in one transaction.

  **The tag-matching rule:** never *infer* which spool a tag belongs to — not by matching specs,
  not by `groupId`, not by "open slot" heuristics. That was tried; it silently merged separate
  spools into one row and undercounted the inventory. Exact UID lookup is not inference and is
  fine: a custom spool records both its sticker UIDs at write time, so `findByTagUid` is a key
  lookup. `groupId` is part of the tag byte format only; never match on it. **Movable tags do not
  weaken this** — a move rewrites which row owns a UID, it never guesses one.

  **Moves go through `SpoolRepository.moveTagsTo`** (or `addSpool` for a brand-new row), which
  releases the pair from its old owner and claims it in one transaction. The release has to come
  first: the unique indices on `tagUid`/`tagUid2` would reject the claim otherwise, which is the
  guarantee worth keeping — two rows can never hold one sticker. SQLite treats NULLs as distinct,
  so any number of untagged spools coexist under those same unique indices.
- `data/SpoolJson.kt` — versioned JSON export (`SPOOL_SCHEMA_VERSION`, currently 7, exporting
  `{spools, events}`). `type` is what the tag says; `materialName`/`finish` are what the filament
  is. Keep both — they differ exactly where the tag couldn't carry the finish. `tagUid` is
  optional from v7 and `hasTags` says whether the printer could read the spool, so a consumer
  never has to know that an Anycubic spool needs one UID while a custom one needs two — and must
  not treat a missing `tagUid` as a malformed row. Forward-looking: there is no import/ingest side yet (no web app, no
  server). When one gets built, this schema is the contract — bump the version and keep old
  fields readable rather than silently reshaping it.

## Working style for this project

- This app was built and verified interactively against a real device (Pixel 9 Pro XL) via adb —
  screenshots, uiautomator dumps, and live NFC writes/reads, not just "it compiles." Prefer that
  standard when touching write/read/decode paths: a build that type-checks is not enough
  confidence for anything that touches the NFC byte format.
- Schema changes get rehearsed before they run: pull the device's `acetag2.db` with `adb exec-out
  run-as`, apply the migration SQL to the copy with `sqlite3`, and check the row counts and
  constraints survive. Room only validates the *shape* on open — it will happily accept a
  migration that dropped rows.
- README screenshots are hosted on Cloudflare R2, not committed: bucket `jduncan-io-static` under
  `images/acetag/`, served from `https://static.jduncan.io`. Credentials are in
  `~/Code/jduncan-blog/.env` (`CLOUDFLARE_R2_*`, `R2_S3_ENDPOINT`); upload with the `aws` CLI and
  `--endpoint-url`. They're public the moment they're uploaded — worth a word before adding any.
- No release signing / Play Store distribution set up yet — debug builds only, sideloaded via adb.
- Keep it simple: this is a small personal/community tool, not a product. Don't add
  infrastructure (CI, signing, Play Store metadata, etc.) unless asked.
