---
type: Domain Concept
title: Spool identity
description: The spool is the countable object and it is the row; three black PLAs are three rows.
tags: [domain, inventory]
status: draft
generated: { by: claude-code/opus-5, at: 2026-08-12T12:45:15Z }
---

# Stub

Authoritative text is in `AGENTS.md`. The rule in one line: **the spool is the countable object and
it is the row.** Never a quantity column, never collapsing identical spools into one record.

The consequence that keeps mattering: a tag UID is the only thing that distinguishes two spools
with identical specs. That is why matching on specs is forbidden (see the tag-matching rule in
`AGENTS.md`) and why [the Anycubic cloud API can't tell you which spool is loaded](/decisions/anycubic-cloud-api.md).

# To write

* Why quantity columns were rejected.
* What `spools` (current inventory) vs `spool_events` (append-only history) each mean.
