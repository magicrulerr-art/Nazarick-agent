package me.rerere.rikkahub.data.db

import java.io.File

/**
 * Pre-migration database backup (AURA-021 v1.0, Room discipline).
 *
 * The fork carries a complete migration chain (AutoMigration 1..39 plus the manual
 * steps registered in [AppDatabaseFactory]) and deliberately NO
 * fallbackToDestructiveMigration: a schema Room cannot walk is an event, not a
 * background condition. But between "no fallback" and "the data is gone" there was
 * nothing recoverable - an unexpected open failure (downgrade after a beta, a corrupt
 * migration, a future rollback decision) left the user holding an error dialog and a
 * wiped chat history. This helper puts a byte-copy of the file on disk BEFORE Room
 * ever opens it, so every later decision - retry, restore, or an explicit destructive
 * fallback - has something to restore from.
 *
 * Kept pure (java.io.File + injected version reader) so the naming, no-clobber and
 * skip logic run on the headless JVM; the Android side only supplies paths and the
 * PRAGMA reader.
 */
object DatabaseBackup {

    /** Subdirectory of filesDir holding the copies; named here once so error
     *  messages and the factory agree on the same path. */
    const val BACKUP_DIR_NAME = "db_backups"

    /** True when opening [name] at [targetVer] would change the schema, i.e. when a
     *  backup is warranted. Unknown persisted version on an existing file also
     *  warrants one (unreadable version = unexpected open ahead). */
    fun needed(persistedVersion: Int?, targetVersion: Int, dbFileExists: Boolean): Boolean {
        if (!dbFileExists) return false
        if (persistedVersion == null) return true
        return persistedVersion != targetVersion
    }

    /**
     * Copies [dbFile] to `<backupsDir>/<name>.<fromVer>-<timestamp>.db`.
     * Never overwrites: an existing destination gains a `-2`, `-3`, ... suffix, so a
     * crash loop that reopens the app five times leaves five copies instead of
     * silently keeping only the last. Returns null (and copies nothing) when the
     * source is missing or the versions match — [needed] is the caller's gate, but
     * the helper re-checks the file itself because a delete can race the decision.
     */
    fun backup(
        dbFile: File,
        backupsDir: File,
        fromVersion: Int,
        timestamp: String,
    ): File? {
        if (!dbFile.exists() || dbFile.length() == 0L) return null
        if (!backupsDir.exists() && !backupsDir.mkdirs()) return null
        var dest = File(backupsDir, "${dbFile.name}.$fromVersion-$timestamp.db")
        var n = 2
        while (dest.exists()) {
            dest = File(backupsDir, "${dbFile.name}.$fromVersion-$timestamp-$n.db")
            n++
        }
        dbFile.copyTo(dest, overwrite = false)
        return dest
    }

    /** Best-effort: a failed backup must never block opening the database, but it
     *  must not pass silently either — [onSkip] receives the reason for the log. */
    fun backupBestEffort(
        dbFile: File,
        backupsDir: File,
        readVersion: (File) -> Int?,
        targetVersion: Int,
        timestamp: String,
        onSkip: (String) -> Unit,
    ): File? = try {
        // An unreadable version is itself an unexpected-open signal, so it degrades to
        // null (= back up from version 0) instead of skipping: report it, then copy.
        val current = try {
            readVersion(dbFile)
        } catch (t: Throwable) {
            onSkip("version unreadable (${t.message}), backing up anyway")
            null
        }
        if (!needed(current, targetVersion, dbFile.exists())) {
            null
        } else {
            backup(dbFile, backupsDir, current ?: 0, timestamp)
                ?: run { onSkip("backup produced no copy (file vanished or dir unwritable)"); null }
        }
    } catch (t: Throwable) {
        onSkip("backup failed: ${t.message}")
        null
    }
}
