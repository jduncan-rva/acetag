package com.jamieduncan.acetag.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * The inventory is a record of filament someone bought, so a migration that drops rows is the
 * worst bug this app can ship — and Room won't catch it: on open it checks the *shape* of the
 * schema, never the rows. This runs every migration from version 1 against a real SQLite, then
 * checks both: the shape matches the newest schema Room exported, and the rows are all there.
 *
 * Adding a migration: append it to [AppDatabase.MIGRATIONS], bump the version, build (Room writes
 * the new `app/schemas/.../<n>.json`), commit that file, and extend [seedVersion1] only if the new
 * migration transforms data that the existing rows don't exercise.
 */
class AppDatabaseMigrationTest {

    private lateinit var db: Connection

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun close() = db.close()

    /** Version 1 as Room created it: no finish, and every spool had a tag. */
    private fun createVersion1(c: Connection) = c.exec(
        """
        CREATE TABLE spools (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, spoolKey TEXT NOT NULL,
            source TEXT NOT NULL, tagUid TEXT NOT NULL, tagUid2 TEXT, groupId TEXT,
            type TEXT NOT NULL, manufacturer TEXT NOT NULL, color TEXT NOT NULL,
            nozzleMin INTEGER NOT NULL, nozzleMax INTEGER NOT NULL, bedMin INTEGER NOT NULL,
            bedMax INTEGER NOT NULL, speedMin INTEGER NOT NULL, speedMax INTEGER NOT NULL,
            diameterMm REAL NOT NULL, lengthM INTEGER NOT NULL, weightG INTEGER NOT NULL,
            tagsStale INTEGER NOT NULL, addedAt INTEGER NOT NULL)
        """,
        "CREATE UNIQUE INDEX index_spools_tagUid ON spools (tagUid)",
        "CREATE UNIQUE INDEX index_spools_tagUid2 ON spools (tagUid2)",
        "CREATE UNIQUE INDEX index_spools_spoolKey ON spools (spoolKey)",
        """
        CREATE TABLE spool_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, spoolKey TEXT NOT NULL,
            kind TEXT NOT NULL, occurredAt INTEGER NOT NULL, source TEXT NOT NULL,
            type TEXT NOT NULL, manufacturer TEXT NOT NULL, color TEXT NOT NULL,
            diameterMm REAL NOT NULL, lengthM INTEGER NOT NULL, weightG INTEGER NOT NULL,
            addedAt INTEGER NOT NULL)
        """,
        "CREATE INDEX index_spool_events_spoolKey ON spool_events (spoolKey)",
    )

    private fun seedVersion1() {
        fun spool(key: String, source: String, uid: String, uid2: String?, type: String) =
            "INSERT INTO spools (spoolKey, source, tagUid, tagUid2, groupId, type, manufacturer, " +
                "color, nozzleMin, nozzleMax, bedMin, bedMax, speedMin, speedMax, diameterMm, " +
                "lengthM, weightG, tagsStale, addedAt) VALUES ('$key', '$source', '$uid', " +
                "${uid2?.let { "'$it'" } ?: "NULL"}, NULL, '$type', 'AC', '#112233', 190, 220, " +
                "45, 60, 0, 0, 1.75, 330, 1000, 0, 1000)"
        fun event(key: String, kind: String, type: String) =
            "INSERT INTO spool_events (spoolKey, kind, occurredAt, source, type, manufacturer, " +
                "color, diameterMm, lengthM, weightG, addedAt) VALUES ('$key', '$kind', 2000, " +
                "'ANYCUBIC', '$type', 'AC', '#112233', 1.75, 330, 1000, 1000)"
        db.exec(
            spool("a", "ANYCUBIC", "04aa", null, "PLA Silk"),
            spool("b", "ANYCUBIC", "04bb", null, "PLA Matte"),
            spool("c", "CUSTOM", "04cc", "04cd", "PETG"),
            spool("d", "CUSTOM", "04dd", "04de", "PLA Luminous"),
            event("a", "ADDED", "PLA Silk"),
            event("c", "ADDED", "PETG"),
            event("gone", "CONSUMED", "PLA Matte"),
        )
    }

    private fun migrateToLatest() {
        val support = db.asSupportDatabase()
        for (m in AppDatabase.MIGRATIONS) m.migrate(support)
    }

    @Test
    fun `migrating from version 1 ends at exactly the schema Room expects`() {
        createVersion1(db)
        migrateToLatest()

        val latest = latestExportedSchema()
        assertEquals(
            "the migrations should end at the newest version",
            latest.getInt("version"),
            AppDatabase.MIGRATIONS.last().endVersion,
        )
        val expected = DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            val entities = latest.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                fresh.exec(e.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = e.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    fresh.exec(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            describe(fresh)
        }
        assertEquals(expected, describe(db))
    }

    @Test
    fun `no spool and no event is lost on the way`() {
        createVersion1(db)
        seedVersion1()
        migrateToLatest()

        assertEquals(4, db.long("SELECT COUNT(*) FROM spools"))
        assertEquals(3, db.long("SELECT COUNT(*) FROM spool_events"))
        assertEquals(
            "every sticker stays on the spool it was on",
            listOf("a:04aa:null", "b:04bb:null", "c:04cc:04cd", "d:04dd:04de"),
            db.strings("SELECT spoolKey || ':' || tagUid || ':' || IFNULL(tagUid2, 'null') FROM spools ORDER BY spoolKey"),
        )
    }

    @Test
    fun `the finishes Anycubic spells out in the type are backfilled, and the rest are plain`() {
        createVersion1(db)
        seedVersion1()
        migrateToLatest()

        assertEquals(
            listOf("a:SILK", "b:MATTE", "c:NONE", "d:GLOW"),
            db.strings("SELECT spoolKey || ':' || finish FROM spools ORDER BY spoolKey"),
        )
        assertEquals(
            listOf("a:SILK", "c:NONE", "gone:MATTE"),
            db.strings("SELECT spoolKey || ':' || finish FROM spool_events ORDER BY spoolKey"),
        )
    }

    @Test
    fun `afterwards a spool may be untagged, but two spools still cannot share a sticker`() {
        createVersion1(db)
        seedVersion1()
        migrateToLatest()

        db.exec("UPDATE spools SET tagUid = NULL, tagUid2 = NULL WHERE spoolKey IN ('c', 'd')")
        assertEquals(2, db.long("SELECT COUNT(*) FROM spools WHERE tagUid IS NULL"))
        try {
            db.exec("UPDATE spools SET tagUid = '04aa' WHERE spoolKey = 'b'")
            fail("a second spool claimed a sticker another spool holds")
        } catch (expected: SQLException) {
            assertTrue(expected.message!!.contains("UNIQUE"))
        }
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Columns (name, type, notnull, pk) and indices (name, unique, columns) per table, sorted. */
    private fun describe(c: Connection): Map<String, List<String>> =
        c.strings("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .associateWith { table ->
                val columns = c.rows("PRAGMA table_info($table)") {
                    "column ${it.getString("name")} ${it.getString("type")} " +
                        "notnull=${it.getInt("notnull")} pk=${it.getInt("pk")}"
                }
                val indices = c.rows("PRAGMA index_list($table)") {
                    it.getString("name") to it.getInt("unique")
                }.filterNot { it.first.startsWith("sqlite_") }.map { (name, unique) ->
                    "index $name unique=$unique on " + c.strings("SELECT name FROM pragma_index_info('$name') ORDER BY seqno")
                }
                (columns + indices).sorted()
            }

    private fun latestExportedSchema(): JSONObject {
        // Unit tests run with the module directory as the working directory.
        val dir = File("schemas/${AppDatabase::class.java.name}")
        val newest = dir.listFiles { f -> f.extension == "json" }
            ?.maxByOrNull { it.nameWithoutExtension.toInt() }
            ?: error("no exported schema in ${dir.absolutePath}; build once with exportSchema = true")
        return JSONObject(newest.readText()).getJSONObject("database")
    }

    private fun Connection.exec(vararg sql: String) =
        createStatement().use { s -> sql.forEach { s.execute(it.trimIndent()) } }

    private fun <T> Connection.rows(sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { s -> s.executeQuery(sql).use { r -> buildList { while (r.next()) add(map(r)) } } }

    private fun Connection.strings(sql: String) = rows(sql) { it.getString(1) }

    private fun Connection.long(sql: String) = rows(sql) { it.getLong(1) }.single()

    /** Enough of [SupportSQLiteDatabase] for a migration, which only ever calls `execSQL`. */
    private fun Connection.asSupportDatabase(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    require(args.size == 1) { "bind arguments aren't supported here" }
                    exec(args[0] as String)
                    Unit
                }
                else -> throw UnsupportedOperationException("a migration called ${method.name}")
            }
        } as SupportSQLiteDatabase
}
