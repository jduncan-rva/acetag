---
type: Reference
title: Materials and SKUs
description: Material as a base + finish pair, and the constraint that only a SKU Anycubic issued may reach a sticker.
resource: ../app/src/main/java/com/jamieduncan/acetag/FilamentMaterial.kt
tags: [materials, format]
status: draft
generated: { by: claude-code/opus-5, at: 2026-08-12T12:45:15Z }
---

# Stub

Authoritative table is `FilamentMaterial.kt`, locked down by `FilamentMaterialTest`.

The hard constraint: **only a SKU Anycubic issued may ever reach a sticker.** The ACE validates
that field, so an invented SKU produces a spool the printer may refuse — discovered only after the
sticker is on. Pairs with no issued SKU write the base material and keep the finish in the
database.

There is no API to fetch this from; see [the cloud API decision](/decisions/anycubic-cloud-api.md).
New SKUs come from real spool dumps only.

# To write

* The issued-SKU set with provenance per entry — which dump each came from.
* Which base × finish pairs currently fall back to the base material, and why.
