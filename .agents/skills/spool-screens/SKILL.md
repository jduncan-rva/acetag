---
name: spool-screens
description: The app's screens and the flows they implement — scanning and routing a tag, adding a scanned spool, the custom-spool form and two-sticker write, spool detail and group screens, the inventory list, colour picking. Use when changing app/src/main/java/com/jamieduncan/acetag/*Activity.kt, SpoolAdapter.kt, SpoolDisplay.kt, FilamentColorMatch.kt, or anything under app/src/main/res/.
---

# Spool screens

The screens are where the domain rules in AGENTS.md either hold or quietly break. Nothing here
runs in a unit test. A screen change is checked by a person on a phone, so the PR must say which
taps to try.

## Scan routing (`ScanActivity`)

One tag in, one route out, with no disambiguating dialog. The order is the logic:

1. UID is recorded → that spool's detail screen. That is the whole interaction.
2. Unrecorded, **and we wrote it** (`ReadResult.writtenHere`) → a sticker on no spool. Offer it
   to the spools waiting for tags.
3. Unrecorded, and it decodes → `AddSpoolActivity` (a spool we don't own yet).
4. Unrecorded, with no spool data → offer it to a spool, or to start a new one.

Step 2 must stay ahead of step 3. A released sticker still decodes and would otherwise show up
as a new Anycubic spool.

## Writing stickers (`CustomSpoolActivity`)

The same form serves CREATE, EDIT and WRITE (Intent extras). Editing a spec is what makes
stickers stale, so it belongs here.

- **Both stickers, or nothing saved.** Nothing reaches the database until sticker 2 is written,
  and sticker 2 can't be the same UID as sticker 1. Backing out halfway asks first
  (`confirmStopHalfway`).
- **The `Tag` dies when it leaves the field.** So `owners` (`repo.tagOwners()`) is loaded
  *before* arming, and the tap is answered synchronously. Never put a database call or a dialog
  between receiving a `Tag` and writing it.
- **A move is never silent, and costs a re-tap.** A sticker another spool wears gets a dialog
  that names that spool. The write doesn't happen on that tap; the next tap of the sticker goes
  through. Approval is per spool (`approvedMoves`), so the pair's other sticker doesn't ask
  again, and a rewrite of a spool's own stickers never asks. Don't replace this with a warning
  that doesn't name the spool. Naming it is the point.
- Saving goes through `SpoolRepository.addSpool` (new row) or `moveTagsTo` (existing row). Never
  set UIDs on a row directly.
- "Just add it to my inventory" (no stickers) sits beside the write button on the filled-in form,
  not on the inventory screen. The choice only means something once the spec exists.
- Weight drives length: the form fills length from `SpoolTag.lengthForWeight` as weight is typed,
  except while `fillingForm` is populating a stored spool.

## Leaving the inventory (`SpoolDetailActivity`, `SpoolGroupActivity`)

"Used it up" (`markEmpty`, recorded) and "Added by mistake" (`deleteMistake`, erased with its
history) are two buttons on purpose. Never merge them into one delete. The group screen's "used
one up" takes the **oldest** spool. They're identical, so first in, first out is the only
defensible answer.

## Adding a scanned spool (`AddSpoolActivity`)

Read-only: every field came off the tag. Owning the same colour and material already is stated
as a fact ("You already have 2 spools of this exact filament"), not raised as a duplicate. The inventory counts spools.

## Words on screen

Speak in spools and stickers, never UIDs, rows or hex. When a finish can't go on the tag
(`FilamentMaterial.needsFallback`), say plainly what the printer will show. It's a fact about
the printer, not an error.
