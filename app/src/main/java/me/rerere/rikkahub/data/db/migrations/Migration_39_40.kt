package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val TAG = "Migration_39_40"

/** Albedo's slug-derived id — must equal GuardianSeed.assistantId("albedo") and
 *  DEFAULT_ASSISTANT_ID (AssistantRetirementTest pins all three against this literal). */
private const val ALBEDO_ID = "1f7021e8-fb41-3f31-b85e-d0db562c3d68"

/**
 * v39 → v40 (v0.6 assistant purge, owner: "delete the 2 default assistants").
 *
 * Data-only step, no schema change: conversations owned by the two retired donor
 * skeletons are re-homed to Albedo so upgrading users keep every chat history with a
 * live assistant card. Settings-side removal (retireDefaultAssistants + empty
 * DEFAULT_ASSISTANTS) covers the roster; this covers the Room rows, which settings
 * rewrites never touch. The UPDATE is idempotent (after the first run the WHERE matches
 * nothing), so a replayed migration is free.
 */
val Migration_39_40 = object : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 39 to 40 (re-home retired-assistant conversations to Albedo)")
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE `ConversationEntity` SET `assistant_id` = '$ALBEDO_ID' " +
                    "WHERE `assistant_id` IN ('0950e2dc-9bd5-4801-afa3-aa887aa36b4e', " +
                    "'3d47790c-c415-4b90-9388-751128adb0a0')"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 39 to 40 success")
        } finally {
            db.endTransaction()
        }
    }
}
