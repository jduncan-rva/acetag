# AGENTS.md

## What this is

AceTag: a native Android (Kotlin) app that keeps an inventory of 3D printer filament spools, and
writes NFC tags in the Anycubic ACE Pro's proprietary spool format for filament that didn't come
with one — entirely from the phone, no PC/USB reader. Open source; releases are signed APKs on
GitHub Releases, sideloaded. No Play Store.

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

**Tags are movable, and a spool with none is normal.** Case 3 puts the stickers on reusable
hardware — an adapter, a reused spool — that different filament passes through, so a pair of
stickers is *on* one spool now and can be *moved* to another. Both UID columns are nullable and an
untagged spool is ordinary inventory: on the shelf and counted, the printer just can't see it yet.

- **A move is never silent.** A sticker owned by another spool isn't refused — moving it is the
  point — but the app names that spool and asks first, then says afterwards which spool lost its
  tags. Quietly making a spool unprintable is the failure this design guards against.
- **Taking one sticker of a pair takes both.** A spool left holding half a pair is one the printer
  can't read but the app thinks is fine.
- **Never infer which spool a tag belongs to** — not by matching specs, not by `groupId`, not by
  "open slot" heuristics. That was tried; it silently merged separate spools and undercounted the
  inventory. Exact UID lookup (`findByTagUid`) is a key lookup, not inference, and is the only
  resolution allowed. A move rewrites which row owns a UID; it never guesses one.
- **Weight drives length.** The ACE counts remaining filament down from what the tag claims, so a
  250 g spool written with a 1 kg length is wrong from the first gram.
- **Only a SKU Anycubic issued may reach a sticker.** The ACE validates it; an invented one is
  discovered after the sticker is on.

## Commands

The pod image (`.factory/stack`: `android-35`) provides JDK 17, SDK platform 35 and build-tools
35.0.0 with `JAVA_HOME`/`ANDROID_HOME` set; nothing else is needed on the host. On a Mac,
`scripts/check` falls back to Android Studio's bundled JDK and `~/Library/Android/sdk`.

```bash
scripts/check <changed files...>   # the checks related to those files; no args = everything
./gradlew testDebugUnitTest --tests com.jamieduncan.acetag.SpoolTagFormatTest   # one test class
./gradlew testDebugUnitTest assembleDebug                                       # everything
```

`scripts/check` takes repo-relative paths: a test file alone runs that class; anything under
`app/src/main`, a Gradle file or `app/schemas` runs the whole unit suite plus a debug APK; docs and
README are skipped and named.

Tests are plain JUnit on the JVM — no Robolectric, no instrumented tests. Android classes are stubs
there, so keep decisions in plain Kotlin (`SpoolTag`, `FilamentMaterial`, `SpoolGroup`) where a test
can reach them. `org.json` and SQLite (`sqlite-jdbc`) are real in tests.

## CI and releases

- `.github/workflows/ci.yml` runs on every PR and push to `main`: unit tests plus a debug APK,
  uploaded as a run artifact for trying a PR on the phone. `main` is protected: changes land by
  PR, and only once `test` is green.
- `.github/workflows/release.yml` runs on a `vX.Y.Z` tag: tests, a signed release APK, and a
  GitHub Release with generated notes. The version comes from the tag (`-PreleaseVersion`);
  `versionCode` is `major*10000 + minor*100 + patch`, so minor and patch stay under 100.
- Release signing reads `ACETAG_KEYSTORE`, `ACETAG_KEYSTORE_PASSWORD`, `ACETAG_KEY_ALIAS`,
  `ACETAG_KEY_PASSWORD` from the environment; the workflow fills them from the repo secrets
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Every release must use
  the same key, or installed copies can't update in place.

## What only a phone can check

NFC reads and writes, the camera colour picker, and every screen flow run only on a physical
device; the emulator has no NFC. A change to `TagIo`/`Type2Tag`/`NfcActivity`, any `*Activity`, or
a layout ends its PR with **what to try on the phone** — which taps, which stickers, what should
happen. A change to the tag layout itself also needs a written sticker loaded in an ACE before it
merges.

## Where things are

- `SpoolTag.kt` — the tag byte format: `buildTag` encodes, `decode` decodes. Skill: `tag-format`.
- `TagIo.kt`, `Type2Tag.kt`, `NfcActivity.kt` — whole-tag read/write over raw Type 2 commands.
  Screens deal in specs and UIDs, never byte offsets. Skill: `tag-format`.
- `FilamentMaterial.kt` — base + finish, and the SKU table. Skill: `materials`.
- `*Activity.kt`, `res/` — the screens and flows. Skill: `spool-screens`.
- `data/` — Room (`acetag2.db`), migrations, `SpoolRepository`, JSON export. Skill:
  `inventory-data`. Schemas are exported to `app/schemas/` and committed.
- `scripts/spool_tag.py` — the original CLI encoder; superseded by the app, kept as the reference
  `SpoolTagFormatTest` checks against.
- `docs/` — an OKF knowledge bundle; mostly stubs pointing back here.
  `docs/decisions/anycubic-cloud-api.md` is settled: AceTag doesn't talk to Anycubic's cloud.

## For a session with the phone attached

These need a person and the Pixel; a pod can't do them.

- Install and run: `./gradlew assembleDebug && adb install -r
  app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n
  com.jamieduncan.acetag/.InventoryActivity`.
- Screen asleep during adb testing: `adb shell settings put system screen_off_timeout <ms>` —
  ask before touching lock-screen/keyguard state on a personal device, and restore the original
  value afterwards.
- Rehearse a schema change on real data before release: pull `acetag2.db` with `adb exec-out
  run-as com.jamieduncan.acetag cat databases/acetag2.db`, apply the migration SQL to the copy
  with `sqlite3`, and check row counts and constraints survive.
- README screenshots live on Cloudflare R2 (bucket `jduncan-io-static`, `images/acetag/`, served
  from `https://static.jduncan.io`), credentials in `~/Code/jduncan-blog/.env`. They're public the
  moment they're uploaded — ask before adding any.
