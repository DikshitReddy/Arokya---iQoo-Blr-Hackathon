package com.arokya.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.getStringOrNull

private fun String.toDietTagsList(): List<String> = split(",").filter { it.isNotBlank() }

object ProfileDao {
    fun get(db: SQLiteDatabase): ProfileRow? {
        db.query("profile", null, "id = 0", null, null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return ProfileRow(
                name = c.getString(c.getColumnIndexOrThrow("name")),
                age = c.getString(c.getColumnIndexOrThrow("age")),
                sex = c.getString(c.getColumnIndexOrThrow("sex")),
                weightKg = c.getString(c.getColumnIndexOrThrow("weightKg")),
                heightCm = c.getString(c.getColumnIndexOrThrow("heightCm")),
                goal = c.getString(c.getColumnIndexOrThrow("goal")),
                dietTags = c.getString(c.getColumnIndexOrThrow("dietTags")).toDietTagsList(),
                stepBaseline = c.getInt(c.getColumnIndexOrThrow("stepBaseline")),
                labReportName = c.getStringOrNull(c.getColumnIndexOrThrow("labReportName")),
                languageCode = c.getString(c.getColumnIndexOrThrow("languageCode")),
            )
        }
    }

    /** id is always 0 — this app has exactly one local user, no accounts. */
    fun upsert(db: SQLiteDatabase, row: ProfileRow) {
        val values = ContentValues().apply {
            put("id", 0)
            put("name", row.name)
            put("age", row.age)
            put("sex", row.sex)
            put("weightKg", row.weightKg)
            put("heightCm", row.heightCm)
            put("goal", row.goal)
            put("dietTags", row.dietTags.joinToString(","))
            put("stepBaseline", row.stepBaseline)
            put("labReportName", row.labReportName)
            put("languageCode", row.languageCode)
        }
        db.insertWithOnConflict("profile", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

object LabFindingDao {
    fun getAll(db: SQLiteDatabase): List<LabFindingRow> {
        val results = mutableListOf<LabFindingRow>()
        db.query("lab_findings", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                results += LabFindingRow(
                    marker = c.getString(c.getColumnIndexOrThrow("marker")),
                    value = c.getString(c.getColumnIndexOrThrow("value")),
                    flag = c.getString(c.getColumnIndexOrThrow("flag")),
                    dietaryNote = c.getString(c.getColumnIndexOrThrow("dietaryNote")),
                )
            }
        }
        return results
    }

    /** The whole point of a lab report: it replaces the prior one, it doesn't accumulate. */
    fun replaceAll(db: SQLiteDatabase, findings: List<LabFindingRow>) {
        db.beginTransaction()
        try {
            db.delete("lab_findings", null, null)
            findings.forEach { f ->
                val values = ContentValues().apply {
                    put("marker", f.marker)
                    put("value", f.value)
                    put("flag", f.flag)
                    put("dietaryNote", f.dietaryNote)
                }
                db.insert("lab_findings", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

object MealDao {
    fun getAll(db: SQLiteDatabase): List<MealRow> {
        val results = mutableListOf<MealRow>()
        db.query("meals", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                results += MealRow(
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    kcal = c.getInt(c.getColumnIndexOrThrow("kcal")),
                    proteinG = c.getInt(c.getColumnIndexOrThrow("proteinG")),
                    carbsG = c.getInt(c.getColumnIndexOrThrow("carbsG")),
                    timeLabel = c.getString(c.getColumnIndexOrThrow("timeLabel")),
                    fatG = c.getInt(c.getColumnIndexOrThrow("fatG")),
                    epochMillis = c.getLong(c.getColumnIndexOrThrow("epochMillis")),
                )
            }
        }
        return results
    }

    fun insert(db: SQLiteDatabase, row: MealRow) {
        val values = ContentValues().apply {
            put("name", row.name)
            put("kcal", row.kcal)
            put("proteinG", row.proteinG)
            put("carbsG", row.carbsG)
            put("timeLabel", row.timeLabel)
            put("fatG", row.fatG)
            put("epochMillis", row.epochMillis)
        }
        db.insert("meals", null, values)
    }
}

object PantryDao {
    fun getAll(db: SQLiteDatabase): List<PantryItemRow> {
        val results = mutableListOf<PantryItemRow>()
        db.query("pantry", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                results += PantryItemRow(
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    quantity = c.getString(c.getColumnIndexOrThrow("quantity")),
                    proteinG = c.getInt(c.getColumnIndexOrThrow("proteinG")),
                    kcal = c.getInt(c.getColumnIndexOrThrow("kcal")),
                )
            }
        }
        return results
    }

    fun insertAll(db: SQLiteDatabase, items: List<PantryItemRow>) {
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("name", item.name)
                    put("quantity", item.quantity)
                    put("proteinG", item.proteinG)
                    put("kcal", item.kcal)
                }
                db.insert("pantry", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

object ReelRecipeDao {
    /** Stores raw string/int fields — the ml layer owns list<->text encoding. */
    data class Row(
        val sourceKey: String,
        val dishName: String,
        val summary: String,
        val kcal: Int,
        val proteinG: Int,
        val carbsG: Int,
        val fatG: Int,
        val ingredients: String,
        val steps: String,
        val lighterName: String?,
        val lighterKcal: Int,
        val lighterSwaps: String,
        val createdAt: Long,
    )

    fun get(db: SQLiteDatabase, sourceKey: String): Row? {
        db.query("reel_recipes", null, "sourceKey = ?", arrayOf(sourceKey), null, null, null).use { c ->
            if (!c.moveToFirst()) return null
            return Row(
                sourceKey = c.getString(c.getColumnIndexOrThrow("sourceKey")),
                dishName = c.getString(c.getColumnIndexOrThrow("dishName")),
                summary = c.getString(c.getColumnIndexOrThrow("summary")),
                kcal = c.getInt(c.getColumnIndexOrThrow("kcal")),
                proteinG = c.getInt(c.getColumnIndexOrThrow("proteinG")),
                carbsG = c.getInt(c.getColumnIndexOrThrow("carbsG")),
                fatG = c.getInt(c.getColumnIndexOrThrow("fatG")),
                ingredients = c.getString(c.getColumnIndexOrThrow("ingredients")),
                steps = c.getString(c.getColumnIndexOrThrow("steps")),
                lighterName = c.getStringOrNull(c.getColumnIndexOrThrow("lighterName")),
                lighterKcal = c.getInt(c.getColumnIndexOrThrow("lighterKcal")),
                lighterSwaps = c.getString(c.getColumnIndexOrThrow("lighterSwaps")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
            )
        }
    }

    /** Newest first — for a "saved reels" list. */
    fun getAll(db: SQLiteDatabase): List<Row> {
        val out = mutableListOf<Row>()
        db.query("reel_recipes", null, null, null, null, null, "createdAt DESC").use { c ->
            while (c.moveToNext()) get(db, c.getString(c.getColumnIndexOrThrow("sourceKey")))?.let { out += it }
        }
        return out
    }

    fun upsert(db: SQLiteDatabase, row: Row) {
        val values = ContentValues().apply {
            put("sourceKey", row.sourceKey)
            put("dishName", row.dishName)
            put("summary", row.summary)
            put("kcal", row.kcal)
            put("proteinG", row.proteinG)
            put("carbsG", row.carbsG)
            put("fatG", row.fatG)
            put("ingredients", row.ingredients)
            put("steps", row.steps)
            put("lighterName", row.lighterName)
            put("lighterKcal", row.lighterKcal)
            put("lighterSwaps", row.lighterSwaps)
            put("createdAt", row.createdAt)
        }
        db.insertWithOnConflict("reel_recipes", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

object ReminderDao {
    data class Row(
        val id: Long,
        val title: String,
        val triggerAtMillis: Long,
        val createdAt: Long,
        val fired: Boolean,
    )

    /** Returns the new row id, so the caller can key an AlarmManager PendingIntent on it. */
    fun insert(db: SQLiteDatabase, title: String, triggerAtMillis: Long, createdAt: Long): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("triggerAtMillis", triggerAtMillis)
            put("createdAt", createdAt)
            put("fired", 0)
        }
        return db.insert("reminders", null, values)
    }

    /** Future, not-yet-fired reminders — what the boot receiver re-arms. */
    fun upcoming(db: SQLiteDatabase, nowMillis: Long): List<Row> {
        val out = mutableListOf<Row>()
        db.query(
            "reminders", null, "fired = 0 AND triggerAtMillis > ?",
            arrayOf(nowMillis.toString()), null, null, "triggerAtMillis ASC",
        ).use { c -> while (c.moveToNext()) out += read(c) }
        return out
    }

    /** All reminders for a list screen, newest first. */
    fun all(db: SQLiteDatabase): List<Row> {
        val out = mutableListOf<Row>()
        db.query("reminders", null, null, null, null, null, "triggerAtMillis DESC").use { c ->
            while (c.moveToNext()) out += read(c)
        }
        return out
    }

    fun markFired(db: SQLiteDatabase, id: Long) {
        db.execSQL("UPDATE reminders SET fired = 1 WHERE id = ?", arrayOf(id))
    }

    fun delete(db: SQLiteDatabase, id: Long) {
        db.delete("reminders", "id = ?", arrayOf(id.toString()))
    }

    private fun read(c: android.database.Cursor) = Row(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        triggerAtMillis = c.getLong(c.getColumnIndexOrThrow("triggerAtMillis")),
        createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
        fired = c.getInt(c.getColumnIndexOrThrow("fired")) != 0,
    )
}
