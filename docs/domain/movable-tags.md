---
type: Domain Concept
title: Movable tags
description: Stickers live on reusable hardware, so a pair is on one spool now and can be moved to another.
tags: [domain, nfc]
status: draft
generated: { by: claude-code/opus-5, at: 2026-08-12T12:45:15Z }
---

# Stub

Authoritative text is in `AGENTS.md`. Both UID columns are nullable; an untagged spool is ordinary
inventory, not a broken record.

The invariants worth not relearning: **a move is never silent**, and **taking one sticker of a pair
takes both**. Moves go through `SpoolRepository.moveTagsTo`, release before claim, in one
transaction — the unique indices on `tagUid`/`tagUid2` are what guarantee two rows can never hold
one sticker.

# To write

* The release-then-claim ordering and why the indices demand it.
* How SQLite's distinct-NULLs behaviour lets any number of untagged spools coexist.
