package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_23_24
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_39_40
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {

    /** The schema this build expects. Read from the annotation rather than restated,
     *  so the pre-open check below can never drift from @Database(version = ...). */
    private val TARGET_VERSION: Int by lazy {
        AppDatabase::class.java.getAnnotation(Database::class.java)?.version
            ?: error("AppDatabase is missing its @Database annotation")
    }

    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase {
        // AURA-021 (Room discipline): copy the file to filesDir/db_backups before Room
        // opens it, whenever the persisted schema version differs from this build's —
        // the migration, the downgrade, and the corrupt-header cases all end up here,
        // and every later decision (retry, restore, explicit destructive fallback) then
        // has bytes to restore from. Best-effort by contract: a failed backup logs and
        // opens anyway; Room carries its own guard (no fallbackToDestructiveMigration
        // anywhere in the fork, and the open is lazy, so an unresolvable schema lands
        // as a query-time error naming this directory in the log line below).
        runCatching {
            val dbFile = context.getDatabasePath(name)
            val current = readUserVersion(dbFile)
            if (DatabaseBackup.needed(current, TARGET_VERSION, dbFile.exists())) {
                val dest = DatabaseBackup.backup(
                    dbFile = dbFile,
                    backupsDir = File(context.filesDir, DatabaseBackup.BACKUP_DIR_NAME),
                    fromVersion = current ?: 0,
                    timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()),
                )
                if (dest != null) {
                    android.util.Log.i(
                        "AppDatabaseFactory",
                        "pre-migration backup written: ${dest.absolutePath} " +
                            "(persisted=${current ?: -1} -> target=$TARGET_VERSION)",
                    )
                } else {
                    android.util.Log.w("AppDatabaseFactory", "pre-migration backup produced no copy; opening anyway")
                }
            }
        }.onFailure {
            android.util.Log.w("AppDatabaseFactory", "pre-migration backup skipped; opening anyway", it)
        }
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_23_24, Migration_30_31, Migration_39_40)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Both steps below are best-effort FTS setup: a failure here (missing dict
                    // assets, FTS5 module unavailable, native lib not loaded yet) must degrade
                    // search, not crash every single app launch by throwing out of onOpen and
                    // failing the whole database open.
                    try {
                        val dictDir = SimpleDictManager.extractDict(context)
                        val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                        cursor.use {
                            if (it.moveToFirst()) {
                                val result = it.getString(0)
                                val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                                if (!success) {
                                    android.util.Log.e(
                                        "DataSourceModule",
                                        "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DataSourceModule", "onOpen: jieba_dict setup failed", e)
                    }

                    try {
                        db.execSQL(me.rerere.rikkahub.data.db.fts.MESSAGE_FTS_CREATE_SQL.trimIndent())
                    } catch (e: Exception) {
                        android.util.Log.e("DataSourceModule", "onOpen: message_fts table creation failed", e)
                    }
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
    }

    /** PRAGMA user_version of an existing file via the platform reader, read-only and
     *  closed before Room opens. Null when the file is absent or unreadable (an
     *  unreadable header still gets a backup — [DatabaseBackup.needed] says so). */
    private fun readUserVersion(file: File): Int? = try {
        if (!file.exists()) null
        else {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
            try {
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else null
                }
            } finally {
                db.close()
            }
        }
    } catch (t: Throwable) {
        android.util.Log.w("AppDatabaseFactory", "user_version unreadable, treating as unknown", t)
        null
    }
}
