package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for the pre-migration backup gate (AURA-021 Room discipline):
 * when a copy is warranted, where it lands, and that a crash loop can never
 * overwrite a previous copy.
 */
class DatabaseBackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dbFile(content: String = "sqlite-bytes"): File =
        File(tmp.root, "rikka_hub.db").apply { writeText(content) }

    @Test
    fun `no backup when versions match or file missing`() {
        assertFalseCompat(DatabaseBackup.needed(40, 40, true))
        assertFalseCompat(DatabaseBackup.needed(null, 40, false))
        assertFalseCompat(DatabaseBackup.needed(39, 40, false))
    }

    @Test
    fun `backup when version differs or header unreadable`() {
        assertTrue(DatabaseBackup.needed(39, 40, true))
        assertTrue(DatabaseBackup.needed(41, 40, true))
        assertTrue(DatabaseBackup.needed(null, 40, true))
        assertTrue(DatabaseBackup.needed(0, 40, true))
    }

    @Test
    fun `backup copies with version and timestamp naming`() {
        val src = dbFile("payload-40")
        val dest = DatabaseBackup.backup(src, File(tmp.root, "db_backups"), 39, "20261113-120000")
        assertTrue(dest != null)
        assertEquals("rikka_hub.db.39-20261113-120000.db", dest!!.name)
        assertEquals("payload-40", dest.readText())
    }

    @Test
    fun `crash loop never overwrites an earlier copy`() {
        val src = dbFile("first")
        val dir = File(tmp.root, "db_backups")
        val a = DatabaseBackup.backup(src, dir, 39, "T")
        src.writeText("second")
        val b = DatabaseBackup.backup(src, dir, 39, "T")
        assertTrue(a != null && b != null)
        assertEquals("rikka_hub.db.39-T.db", a!!.name)
        assertEquals("rikka_hub.db.39-T-2.db", b!!.name)
        assertEquals("first", a.readText())
        assertEquals("second", b.readText())
    }

    @Test
    fun `empty or vanished source copies nothing`() {
        val dir = File(tmp.root, "db_backups")
        assertNull(DatabaseBackup.backup(File(tmp.root, "absent.db"), dir, 39, "T"))
        val empty = File(tmp.root, "zero.db").apply { writeText("") }
        assertNull(DatabaseBackup.backup(empty, dir, 39, "T"))
    }

    @Test
    fun `best-effort swallows reader failure and reports the reason`() {
        val src = dbFile()
        var skip: String? = null
        val dest = DatabaseBackup.backupBestEffort(
            dbFile = src,
            backupsDir = File(tmp.root, "db_backups"),
            readVersion = { error("pragma blew up") },
            targetVersion = 40,
            timestamp = "T",
            onSkip = { skip = it },
        )
        assertTrue("unreadable version must still yield a copy", dest != null)
        assertEquals("rikka_hub.db.0-T.db", dest!!.name)
        assertTrue("the skip log must explain the unreadable header", skip != null && skip!!.contains("unreadable"))
    }

    @Test
    fun `best-effort matches versions leaves the dir untouched`() {
        val src = dbFile()
        val dir = File(tmp.root, "db_backups")
        var skip: String? = null
        val dest = DatabaseBackup.backupBestEffort(src, dir, { 40 }, 40, "T") { skip = it }
        assertNull(dest)
        assertNull(skip)
        assertTrue(!dir.exists() || dir.list()!!.isEmpty())
    }

    private fun assertFalseCompat(cond: Boolean) = assertTrue("expected false", !cond)
}
