package com.arokya.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Hand-rolled SQLite — no Room. Room's annotation processor (kapt AND KSP)
 * bundles a metadata reader capped at Kotlin metadata version 2.3.0, but
 * this project's classes compile to version 2.4.0 (Kotlin 2.4.10 is required
 * for LiteRT-LM — see MlEndpoints.kt). That makes Room's codegen unable to
 * read this module's own sources at all right now, regardless of kapt vs
 * KSP. A plain [SQLiteOpenHelper] needs no codegen, no native library, and
 * no annotation processor, so there's no tooling-version wall to hit.
 */
class ArokyaDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "arokya.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE profile (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                age TEXT NOT NULL,
                sex TEXT NOT NULL,
                weightKg TEXT NOT NULL,
                heightCm TEXT NOT NULL,
                goal TEXT NOT NULL,
                dietTags TEXT NOT NULL,
                stepBaseline INTEGER NOT NULL,
                labReportName TEXT,
                languageCode TEXT NOT NULL DEFAULT 'en'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE lab_findings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                marker TEXT NOT NULL,
                value TEXT NOT NULL,
                flag TEXT NOT NULL,
                dietaryNote TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE meals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kcal INTEGER NOT NULL,
                proteinG INTEGER NOT NULL,
                carbsG INTEGER NOT NULL,
                timeLabel TEXT NOT NULL,
                fatG INTEGER NOT NULL DEFAULT 0,
                epochMillis INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE pantry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                quantity TEXT NOT NULL,
                proteinG INTEGER NOT NULL,
                kcal INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(reelTableSql())
        db.execSQL(reminderTableSql())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrate in place — never drop the user's profile, lab findings or
        // meal history just to add a column.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE meals ADD COLUMN fatG INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // Meals had no date at all, so every row counted as "today" forever.
            // Backfill existing rows to now: they were almost certainly logged
            // today, and dropping them would lose real user data.
            db.execSQL("ALTER TABLE meals ADD COLUMN epochMillis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE meals SET epochMillis = ${System.currentTimeMillis()} WHERE epochMillis = 0")
            db.execSQL("ALTER TABLE profile ADD COLUMN languageCode TEXT NOT NULL DEFAULT 'en'")
        }
        if (oldVersion < 4) {
            // Cache of generated reel-to-recipe results, so re-opening the same
            // reel doesn't re-run the minute-long pipeline.
            db.execSQL(reelTableSql())
        }
        if (oldVersion < 5) {
            db.execSQL(reminderTableSql())
        }
    }

    /**
     * One cached reel recipe. [sourceKey] is the reel's identity (Instagram
     * shortcode, or a stable id for a local video) — the lookup key. List
     * fields are stored as newline-joined text; there are no newlines inside an
     * ingredient or step, so it round-trips cleanly and needs no JSON.
     */
    private fun reelTableSql(): String = """
        CREATE TABLE IF NOT EXISTS reel_recipes (
            sourceKey TEXT PRIMARY KEY,
            dishName TEXT NOT NULL,
            summary TEXT NOT NULL,
            kcal INTEGER NOT NULL,
            proteinG INTEGER NOT NULL,
            carbsG INTEGER NOT NULL,
            fatG INTEGER NOT NULL,
            ingredients TEXT NOT NULL,
            steps TEXT NOT NULL,
            lighterName TEXT,
            lighterKcal INTEGER NOT NULL,
            lighterSwaps TEXT NOT NULL,
            createdAt INTEGER NOT NULL
        )
    """.trimIndent()

    /**
     * Reminders the user asked the coach to set. Persisted so they survive an
     * app kill or reboot — AlarmManager alarms do NOT, so a boot receiver
     * re-arms every future reminder from this table. [fired] marks past ones.
     */
    private fun reminderTableSql(): String = """
        CREATE TABLE IF NOT EXISTS reminders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            triggerAtMillis INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            fired INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    companion object {
        @Volatile private var instance: ArokyaDbHelper? = null

        fun getInstance(context: Context): ArokyaDbHelper {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = ArokyaDbHelper(context)
                instance = created
                return created
            }
        }
    }
}
