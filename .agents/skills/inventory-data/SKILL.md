---
name: inventory-data
description: The Room database (spools, spool_events), its migrations and exported schemas, SpoolRepository, display grouping, and the versioned JSON export. Use when changing anything under app/src/main/java/com/jamieduncan/acetag/data/, app/schemas/, or AppDatabaseMigrationTest.kt.
---

# Inventory data

`acetag2.db` holds filament someone actually bought, and there is no backup and no server.
Losing rows is the worst bug this app can ship.

## Shape

- `spools` is **current inventory only**, one row per physical spool. Never add a quantity column
  or merge identical spools. `SpoolGroup`/`groupBySpec` is display-only grouping, and its key
  includes `finish` because wood PLA and plain PLA both say "PLA" on the tag.
- `spool_events` is append-only history (ADDED, CONSUMED). Each event carries a full snapshot,
  not a foreign key, because a CONSUMED spool's row is already gone. Events point at `spoolKey`
  (a UUID), never `id`: SQLite reuses row ids after a delete.
- `tagUid`/`tagUid2` are nullable, and an untagged spool is normal. Unique indices on each column
  mean two rows can never hold one sticker. SQLite treats NULLs as distinct, so any number of
  untagged spools coexist. No index spans the pair, so `tagIsKnown` checks both columns. Don't
  "fix" that with one index; there isn't one that can.

## Every mutation goes through `SpoolRepository`

A row change and its event share one transaction, so the tables never disagree.

- `addSpool`: insert plus ADDED. It also releases any stickers the new row claims.
- `moveTagsTo`: **release first, then claim**, in one transaction. The unique indices would
  reject the claim otherwise, and that rejection is the guarantee. `releaseFor` takes the
  displaced spool's **whole pair**, never one sticker of it.
- `markEmpty` (used it up): CONSUMED event plus delete. The stickers go with the row.
- `deleteMistake` (added by mistake): delete the row **and** its events. Filament never bought
  must not appear in the history. Never merge these two operations.

Tag-to-spool resolution is an exact UID lookup (`findByTagUid`) and nothing else. No matching by
spec, `groupId` or "open slot". That was tried, and it silently merged separate spools and
undercounted the inventory.

## Migrations

Real migrations only. Never `fallbackToDestructiveMigration`. To change the schema:

1. Change the entity, bump `version` in `AppDatabase`, and write a `Migration(n, n+1)`. SQLite
   can't drop NOT NULL or change a column in place, so rebuild the table the way `MIGRATION_2_3`
   does: create `_new`, copy with an explicit column list, drop, rename, recreate every index.
2. Append it to `AppDatabase.MIGRATIONS`.
3. Build. Room writes `app/schemas/com.jamieduncan.acetag.data.AppDatabase/<n+1>.json`. Commit it,
   and never edit an old one.
4. `AppDatabaseMigrationTest` runs every migration from version 1 on a real SQLite. It checks
   that the result matches the newest exported schema exactly, and that the seeded rows, stickers
   and events all survive. If the new migration transforms data, add rows to `seedVersion1` that
   exercise it, plus a test that asserts the result.

Room only checks the schema's shape on open. That's why the row assertions exist. Before release,
a person also rehearses the migration on a copy of the real phone database (AGENTS.md).

## JSON export (`SpoolJson.kt`)

`SPOOL_SCHEMA_VERSION` (7) is a contract for a consumer that doesn't exist yet. Any change to the
shape bumps the version, keeps old fields readable where it can, and adds a paragraph to the
version history in the file's header comment. `type` is what the tag says, and
`materialName`/`finish` are what the filament is. Keep both. `tagUid` is optional, and `hasTags`
is the derived "can the printer read this spool" flag.
