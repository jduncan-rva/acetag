# AceTag

An Android app that keeps track of your 3D printer filament, and writes NFC tags the Anycubic ACE
Pro reads as genuine spools — so third-party filament gets recognized and auto-identified just
like the official stuff.

Everything happens on your phone: no PC, no USB NFC reader/dongle.

**Not affiliated with Anycubic.** The tag format was reverse-engineered by the community; see
Credits below.

<img src="https://static.jduncan.io/images/acetag/inventory.png" width="280" align="right" />

## How a spool gets into your inventory

**It came with a tag** (Anycubic filament) — tap *Scan a spool's tag* and hold the spool to the
back of your phone. Everything about the filament is already on the tag, so you just confirm it's
the spool in your hand and add it. If you already own that exact colour and material, it says so
and adds another one; the inventory counts spools, so three black PLAs are three spools.

**It didn't** (anyone else's filament) — tap *Set up a spool without a tag*. Pick the material and
finish and it fills in sensible temperatures for you; type the brand and set the colour (type a
hex code or sample it with the camera). Then you write **two stickers, one for each side of the
spool**, so the ACE picks it up whichever way round it's loaded. The app walks you through both,
and nothing is saved until both are written.

**It can't hold a tag** (anything under 1 kg, or a refill) — add it now, tag it when it goes in
the printer. See [Small spools and refills](#small-spools-and-refills).

Scanning any tag you've already got — either sticker of a spool you tagged yourself — just opens
that spool.

<br clear="right" />

## Small spools and refills

A spool under 1 kg doesn't fit the ACE Pro's rollers, and once it's on an adapter it no longer
lines up with the NFC reader — so the tag has to go on the *adapter*. A refill has no spool of its
own at all. Either way the stickers live on reusable hardware, and whatever filament is mounted on
it is what the printer reads.

So in AceTag **the tags move**. A pair of stickers is on one spool now and can be moved to another,
while the spools themselves stay put in your inventory. Nobody peels anything.

<table>
<tr>
<td><img src="https://static.jduncan.io/images/acetag/add-small-spool.png" width="260" /></td>
<td><img src="https://static.jduncan.io/images/acetag/spool-without-tags.png" width="260" /></td>
</tr>
</table>

- **Add it without writing anything** — *Just add it to my inventory*, under the write button. The
  spool is on your shelf and counted; the printer just can't see it yet. Set the weight and the
  length follows it (250 g of PLA is 83 m), because the ACE counts remaining filament down from
  whatever the tag claims — a small spool written with a 1 kg length is wrong from the first gram.
- **Tag it when it's the one going in the printer** — open the spool and tap *Put the tags on this
  spool*, then hold each sticker to the phone in turn.
- **Moving a pair is never silent.** If those stickers are on another spool, AceTag names it and
  asks first — *"These tags are on Anycubic PLA"* — and tells you afterwards which spool lost them.
  That spool stays in your inventory; it just goes back to having no tags.
- **Or start from the sticker.** Scan an adapter that isn't on any spool and AceTag offers it to
  the spools that are waiting for tags. That's the quick one at the printer, when the adapter is
  already in your hand.
- **Using a spool up frees its stickers**, so there's no release step to forget.

The inventory says which is which at a glance: a line reads *No tags on it yet*, or *1 of 3
tagged* when only some of a group are.

## Materials and finishes

Material is split in two, the way you'd describe a spool out loud: a **base** (PLA, PLA+, PLA High
Speed, PETG, ASA, ABS, TPU) and a **finish** (Matte, Silk, Marble, Galaxy, Metallic, Glow in the
Dark, Wood, Carbon Fibre).

The tag can't always carry the finish. The ACE Pro validates the SKU field and only understands
SKUs for filament Anycubic actually sells, and Anycubic makes no wood-filled or carbon-fibre
filament — so there's no code to write. Where a combination exists (PLA Silk, PLA Matte, PLA
Luminous) the tag says so; everywhere else the tag says the base material and AceTag remembers the
rest. The form tells you which is happening before you write anything.

The upshot: your printer always loads the spool correctly, its screen may say "PLA" for a
wood-filled PLA, and your inventory always knows what the spool really is. Wood and carbon fibre
are also flagged **abrasive · hardened nozzle** wherever they're listed.

## Also

<img src="https://static.jduncan.io/images/acetag/writing-tags.png" width="240" align="right" />

- **Your inventory** — one entry per physical spool. Edit the details, rewrite the tags if you
  change something, and mark a spool used up when it runs out.
- **Filament history** — used-up spools leave the inventory but are recorded, so there's a record
  of what you get through over time. Removing an entry you added by mistake leaves no trace, so it
  doesn't pollute that record.
- **JSON export** — copies your inventory and history to the clipboard. There's no server or web
  app yet; this just makes the data portable for whenever one exists.

<br clear="right" />

## Requirements

- An Android phone with NFC (tap-to-write, no separate reader hardware).
- NTAG213, NTAG215, or NTAG216 NFC stickers — two per spool you tag yourself, or two per adapter
  if you're rotating small spools and refills through one.
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
  FilamentMaterial.kt       # base material + finish, and which combinations the tag can carry
  Type2Tag.kt / TagIo.kt    # raw NFC read/write, and whole-tag read/write on top of it
  InventoryActivity.kt      # home screen: spool list, export
  ScanActivity.kt           # read a tag and route: known spool / new spool / sticker with no spool
  AddSpoolActivity.kt       # confirm and add a spool that came with its own tag
  CustomSpoolActivity.kt    # set up a spool, with or without writing stickers; also edit/rewrite
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
