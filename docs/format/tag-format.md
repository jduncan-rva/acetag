---
type: Format Specification
title: Anycubic spool tag format
description: The reverse-engineered page layout AceTag reads and writes on Type 2 Tags.
resource: ../app/src/main/java/com/jamieduncan/acetag/SpoolTag.kt
tags: [nfc, format, reverse-engineering]
status: draft
generated: { by: claude-code/opus-5, at: 2026-08-12T12:45:15Z }
---

# Stub

Authoritative text is the "Tag format" section of `README.md`; the implementation is `SpoolTag.kt`
(`buildTag` encodes, `decode` decodes).

Two facts that belong here rather than in code comments:

* **The `0x4D` marker at page `0x27` byte 3** is present on everything AceTag writes and absent
  from every genuine dump. It is what lets a scan tell "a spool I've never seen" from "a sticker I
  wrote that's on no spool" — which is why the marker check must run *before* the decode check.
* **Weight drives length.** The ACE counts remaining filament down from what the tag claims, so a
  250 g spool written with a 1 kg length is wrong from the first gram. `lengthForWeight` is
  arithmetic over `MATERIAL_DEFAULTS`, not part of the byte layout.

# To write

* The full page map, with the known-good dumps as `sources` entries.
* What the ACE validates and what it ignores.

Any change to this concept should be verified against real page dumps before it is trusted; a
build that type-checks is not evidence.
