# AceTag

An Android app that keeps track of your 3D printer filament, and writes NFC tags the Anycubic ACE
Pro reads as genuine spools — so third-party filament gets recognized and auto-identified just
like the official stuff.

Everything happens on your phone: no PC, no USB NFC reader/dongle.

**Not affiliated with Anycubic.** The tag format was reverse-engineered by the community; see
Credits below.

## The two ways a spool gets into your inventory

**It came with a tag** (Anycubic filament) — tap *Scan a spool's tag* and hold the spool to the
back of your phone. Everything about the filament is already on the tag, so you just confirm it's
the spool in your hand and add it. If you already own that exact colour and material, it says so
and adds another one; the inventory counts spools, so three black PLAs are three spools.

**It didn't** (anyone else's filament) — tap *Set up a spool without a tag*. Pick the material and
it fills in sensible temperatures for you; type the brand and set the colour (type a hex code or
sample it with the camera). Then you write **two stickers, one for each side of the spool**, so
the ACE picks it up whichever way round it's loaded. The app walks you through both, and nothing
is saved until both are written.

Scanning any tag you've already got — either sticker of a spool you tagged yourself — just opens
that spool.

## Also

- **Your inventory** — one entry per physical spool. Edit the details, rewrite the tags if you
  change something, and mark a spool used up when it runs out.
- **Filament history** — used-up spools leave the inventory but are recorded, so there's a record
  of what you get through over time. Removing an entry you added by mistake leaves no trace, so it
  doesn't pollute that record.
- **JSON export** — copies your inventory and history to the clipboard. There's no server or web
  app yet; this just makes the data portable for whenever one exists.

## Requirements

- An Android phone with NFC (tap-to-write, no separate reader hardware).
- NTAG213, NTAG215, or NTAG216 NFC stickers — two per spool you tag yourself.
- Android Studio (or just the Gradle wrapper + an Android SDK) to build it. There's no signed
  release APK published yet.

## Building

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the repo root in Android Studio and run it on a device — NFC doesn't work in the
emulator, so you'll need a physical phone.

## Project layout

```
app/src/main/java/com/jamieduncan/acetag/
  SpoolTag.kt               # Anycubic tag format: encode a Spec to raw pages, decode raw pages back
  Type2Tag.kt / TagIo.kt    # raw NFC read/write, and whole-tag read/write on top of it
  InventoryActivity.kt      # home screen: spool list, export
  ScanActivity.kt           # read a tag and route: known spool / new spool / blank sticker
  AddSpoolActivity.kt       # confirm and add a spool that came with its own tag
  CustomSpoolActivity.kt    # set up a spool and write both stickers; also edit/rewrite
  SpoolDetailActivity.kt    # per-spool detail + actions
  ColorPickerActivity.kt    # camera-based color sampling
  data/                     # Room entities/DAO/DB, repository, JSON export schema
scripts/spool_tag.py        # original CLI prototype (copy/paste hex into NFC Tools Pro) — superseded by the app, kept for reference
```

## Tag format

Pages 0x04–0x27 of an NTAG213 hold the spool spec (SKU, type, color, temp/speed ranges,
diameter, length, weight); pages 0x00–0x03 and 0x28–0x2c are chip/management data and are never
touched. See `SpoolTag.kt` for the exact byte layout.

## Credits

The NFC tag format was reverse-engineered by [Molodos/anycubic-nfc-filament](https://github.com/Molodos/anycubic-nfc-filament)
— this project independently reimplements that format as a native Android app with no
dependency on that project's code.

## License

MIT — see [LICENSE](LICENSE).
