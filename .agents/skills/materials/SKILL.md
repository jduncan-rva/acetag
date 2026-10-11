---
name: materials
description: Filament as base material plus finish, which pairs Anycubic sells, the SKU each one writes, and per-material default temperatures and lengths. Use when changing app/src/main/java/com/jamieduncan/acetag/FilamentMaterial.kt, SpoolTag.MATERIAL_DEFAULTS or lengthForWeight, FilamentMaterialTest.kt or SpoolTagLengthTest.kt.
---

# Materials and SKUs

## Only issued SKUs reach a sticker

The ACE validates the SKU at page 0x05. If a SKU is invented, the printer may refuse the spool,
and you only find out once the sticker is on. So:

- `BASE_SKUS` and `NATIVE` hold only SKUs taken from a real Anycubic spool dump. Never build one
  from the `H<material><colour>-<version>` pattern, even when the material code is documented.
  That's why Marble, Galaxy and Metallic are absent: their codes are known but their full SKUs
  aren't.
- A pair Anycubic doesn't sell (wood, carbon fibre, and anything not in `NATIVE`) writes the
  **base material's** type and SKU. The finish lives only in the database's `finish` column, and
  `needsFallback` is how the form says so before writing.
- `FilamentMaterialTest` checks every base × finish pair against the issued set. A new material
  goes into the table and the test's expected set together, from a dump. If you can't cite a dump
  in the PR, the material doesn't go in.

## Things that look like finishes and aren't

PLA+ and PLA High Speed are **bases**. They're different formulations with their own SKUs and
temperatures. `fromTagType` must not read "PLA+" as PLA (there's a test for that). An unknown
type string degrades to PLA rather than throwing, because a scanned tag can say anything.

## Defaults and length

`SpoolTag.MATERIAL_DEFAULTS` is keyed by **tag type string**. A finish without its own entry falls
back to the base material's numbers. These are starting values for the form, and the user can
always edit them.

`lengthForWeight` scales a material's full-spool length by weight. The ACE counts filament down
from the length the tag claims, so a 250 g spool written with 1 kg's length is wrong from the
first gram. It returns 0, meaning "leave the field alone", when the material has no defaults or
the weight isn't positive. Don't make it guess instead.

Every entry in `BASES` must have defaults (`SpoolTagLengthTest` checks this). Adding a base means
adding its SKU, its defaults and the dump the SKU came from, together.
