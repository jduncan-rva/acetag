# AceTag

A small Android app for writing NFC tags that the Anycubic ACE Pro reads as genuine filament
spools — so third-party/non-Anycubic filament gets recognized and auto-identified just like the
official stuff.

Everything happens on your phone: no PC, no USB NFC reader/dongle. Fill in a spool's specs, tap
a blank NTAG213/215/216 sticker to the back of the phone, and it's written. AceTag also keeps a
local inventory of what you've tagged, and can read tags back (yours or genuine Anycubic ones)
to look them up or import them.

**Not affiliated with Anycubic.** The tag format was reverse-engineered by the community; see
Credits below.

## Features

- **Write tags** — pick a material type (defaults to typical temp/speed ranges for that
  material, all editable), set a color (type a hex code or sample it with the camera), and write
  it to a tag. Each spool needs two identical tags (one per end), and the app walks you through
  writing both.
- **Inventory** — every spool you write is logged locally (Room/SQLite). Mark spools used up,
  delete them, or reprint a replacement tag if one is lost/damaged.
- **Read tags** — read any tag back. If it matches something in your inventory, it opens that
  record. If it's an unrecognized tag (e.g. a genuine Anycubic spool, or one written before you
  had this app), it offers to import it.
- **JSON export** — copies your whole inventory to the clipboard as JSON. There's no server or
  web app yet — this just makes the data portable for whenever one exists.

## Requirements

- An Android phone with NFC (tap-to-write, no separate reader hardware).
- NTAG213, NTAG215, or NTAG216 NFC stickers — two per spool.
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
  SpoolTag.kt              # Anycubic tag format: encode a Spec to raw pages, decode raw pages back
  InventoryActivity.kt      # home screen: spool list, export
  WriteSpoolActivity.kt     # write flow (new spool / import / reprint)
  ReadTagActivity.kt        # read-and-identify flow
  SpoolDetailActivity.kt    # per-spool detail + actions
  ColorPickerActivity.kt    # camera-based color sampling
  data/                     # Room entity/DAO/DB + JSON export schema
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
