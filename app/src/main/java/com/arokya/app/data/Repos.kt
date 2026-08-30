package com.arokya.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.arokya.app.data.db.ArokyaDbHelper
import com.arokya.app.data.db.FieldCrypto
import com.arokya.app.data.db.LabFindingDao
import com.arokya.app.data.db.LabFindingRow
import com.arokya.app.data.db.MealDao
import com.arokya.app.data.db.MealRow
import com.arokya.app.data.db.PantryDao
import com.arokya.app.data.db.PantryItemRow
import com.arokya.app.data.db.ProfileDao
import com.arokya.app.data.db.ProfileRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Single in-memory store. Compose observes these lists/values directly,
 * so every screen updates live when data changes.
 *
 * Backed by [ArokyaDbHelper] (plain SQLite — see that file for why this
 * isn't Room) — [load] populates everything durable from disk once at app
 * start, and [saveProfile]/[addMeal] write back through. Ephemeral,
 * demo-simulated values ([stepsToday], [streakDays]) are intentionally NOT
 * persisted. Sensitive fields (profile PII, lab findings) are encrypted
 * per-value via [FieldCrypto] before they're written.
 */
object Store {
    val profile = UserProfile()

    val pantry = mutableStateListOf<PantryItem>()

    val meals = mutableStateListOf<Meal>()

    /**
     * Today's steps, written by [com.arokya.app.sensor.StepSensor] from the
     * phone's real pedometer. Starts at 0 rather than a demo value — an
     * invented step count would silently drive the whole rule engine.
     */
    val stepsToday = mutableIntStateOf(0)

    /** Consecutive days the user has checked in. Demo value. */
    val streakDays = mutableIntStateOf(4)

    /** Set true once onboarding finishes so re-launches skip it. */
    val onboarded = mutableStateOf(false)

    /** Flips true when [load] has finished, so the splash knows where to go next. */
    val loaded = mutableStateOf(false)

    /** The last recommendation produced — read by the Why-this sheet. */
    val lastRecommendation = mutableStateOf<Recommendation?>(null)

    private lateinit var db: SQLiteDatabase

    /**
     * Reads profile, lab findings, meals and pantry from disk. Call once at
     * app start, before any screen reads [Store]. On a fresh install (nothing
     * saved yet) seeds meals/pantry with the same demo data this app has
     * always shipped with, and persists that seed immediately.
     */
    suspend fun load(context: Context): Unit = withContext(Dispatchers.IO) {
        db = ArokyaDbHelper.getInstance(context).writableDatabase

        ProfileDao.get(db)?.let { saved ->
            profile.name = FieldCrypto.decrypt(saved.name)
            profile.age = FieldCrypto.decrypt(saved.age)
            profile.sex = FieldCrypto.decrypt(saved.sex)
            profile.weightKg = FieldCrypto.decrypt(saved.weightKg)
            profile.heightCm = FieldCrypto.decrypt(saved.heightCm)
            profile.goal = saved.goal
            profile.languageCode = saved.languageCode
            profile.dietTags.clear()
            profile.dietTags.addAll(saved.dietTags)
            profile.stepBaseline = saved.stepBaseline
            profile.labReportName = saved.labReportName?.let { FieldCrypto.decrypt(it) }
            onboarded.value = true
        }
        profile.labFindings = LabFindingDao.getAll(db).map {
            LabFinding(
                marker = FieldCrypto.decrypt(it.marker),
                value = FieldCrypto.decrypt(it.value),
                flag = FieldCrypto.decrypt(it.flag),
                dietaryNote = FieldCrypto.decrypt(it.dietaryNote),
            )
        }

        // The DB keeps every meal ever logged; [meals] is TODAY only. Without
        // this filter yesterday's food kept counting against today's budget.
        val savedMeals = MealDao.getAll(db).filter { isToday(it.epochMillis) }
        meals.clear()
        if (savedMeals.isNotEmpty()) {
            meals.addAll(
                savedMeals.map {
                    Meal(
                        name = it.name, kcal = it.kcal, proteinG = it.proteinG,
                        carbsG = it.carbsG, timeLabel = it.timeLabel, fatG = it.fatG,
                        epochMillis = it.epochMillis,
                    )
                }
            )
        } else {
            val seed = listOf(
                Meal("Oats bowl", kcal = 320, proteinG = 12, carbsG = 54, timeLabel = "8:40 AM", fatG = 7),
                Meal("Veg thali", kcal = 640, proteinG = 18, carbsG = 92, timeLabel = "1:15 PM", fatG = 20),
            )
            meals.addAll(seed)
            seed.forEach {
                MealDao.insert(
                    db,
                    MealRow(
                        name = it.name, kcal = it.kcal, proteinG = it.proteinG,
                        carbsG = it.carbsG, timeLabel = it.timeLabel, fatG = it.fatG,
                        epochMillis = it.epochMillis,
                    )
                )
            }
        }

        val savedPantry = PantryDao.getAll(db)
        pantry.clear()
        if (savedPantry.isNotEmpty()) {
            pantry.addAll(savedPantry.map { PantryItem(it.name, it.quantity, it.proteinG, it.kcal) })
        } else {
            val seed = listOf(
                PantryItem("Eggs", "6 pcs", proteinG = 6, kcal = 70),
                PantryItem("Paneer", "200 g", proteinG = 18, kcal = 265),
                PantryItem("Spinach", "1 bunch", proteinG = 3, kcal = 25),
                PantryItem("Curd", "400 g", proteinG = 11, kcal = 98),
                PantryItem("Tomatoes", "4 pcs", proteinG = 1, kcal = 22),
            )
            pantry.addAll(seed)
            PantryDao.insertAll(
                db,
                seed.map { PantryItemRow(name = it.name, quantity = it.quantity, proteinG = it.proteinG, kcal = it.kcal) }
            )
        }

        loaded.value = true
    }

    /** Persists the CURRENT in-memory [profile] (every field, including lab findings). */
    suspend fun saveProfile() = withContext(Dispatchers.IO) {
        ProfileDao.upsert(
            db,
            ProfileRow(
                name = FieldCrypto.encrypt(profile.name),
                age = FieldCrypto.encrypt(profile.age),
                sex = FieldCrypto.encrypt(profile.sex),
                weightKg = FieldCrypto.encrypt(profile.weightKg),
                heightCm = FieldCrypto.encrypt(profile.heightCm),
                goal = profile.goal,
                languageCode = profile.languageCode,
                dietTags = profile.dietTags.toList(),
                stepBaseline = profile.stepBaseline,
                labReportName = profile.labReportName?.let { FieldCrypto.encrypt(it) },
            )
        )
        LabFindingDao.replaceAll(
            db,
            profile.labFindings.map {
                LabFindingRow(
                    marker = FieldCrypto.encrypt(it.marker),
                    value = FieldCrypto.encrypt(it.value),
                    flag = FieldCrypto.encrypt(it.flag),
                    dietaryNote = FieldCrypto.encrypt(it.dietaryNote),
                )
            }
        )
    }

    /** Whole calendar days between [epochMillis] and now (0 = today, 1 = yesterday). */
    fun calendarDaysAgo(epochMillis: Long): Int {
        val then = Calendar.getInstance().apply {
            timeInMillis = epochMillis
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return ((today.timeInMillis - then.timeInMillis) / (24L * 60 * 60 * 1000)).toInt()
    }

    /** True when [epochMillis] falls on the same calendar day as right now. */
    fun isToday(epochMillis: Long): Boolean {
        if (epochMillis <= 0L) return false
        val then = Calendar.getInstance().apply { timeInMillis = epochMillis }
        val now = Calendar.getInstance()
        return then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }

    /** Outcome of a log attempt, so the UI can show a real state instead of guessing. */
    sealed interface LogResult {
        data object Saved : LogResult
        /** Same dish and calories logged moments ago — a double tap, not a second helping. */
        data object Duplicate : LogResult
        data class Failed(val message: String) : LogResult
    }

    /**
     * How close together two identical entries have to be to count as an
     * accidental double-tap. Long enough to swallow a frustrated triple-tap,
     * short enough that genuinely eating the same snack twice still logs.
     */
    private const val DUPLICATE_WINDOW_MS = 60_000L

    /**
     * Logs a meal — updates the in-memory list Compose observes AND persists it.
     *
     * Guards against duplicates at the STORE, not just in the UI: every log
     * screen shares this path, and a disabled button doesn't help if the same
     * meal is reachable from two screens.
     */
    suspend fun addMeal(meal: Meal): LogResult = withContext(Dispatchers.IO) {
        val duplicate = meals.any {
            it.name.equals(meal.name, ignoreCase = true) &&
                    it.kcal == meal.kcal &&
                    kotlin.math.abs(it.epochMillis - meal.epochMillis) < DUPLICATE_WINDOW_MS
        }
        if (duplicate) return@withContext LogResult.Duplicate

        try {
            MealDao.insert(
                db,
                MealRow(
                    name = meal.name, kcal = meal.kcal, proteinG = meal.proteinG,
                    carbsG = meal.carbsG, timeLabel = meal.timeLabel, fatG = meal.fatG,
                    epochMillis = meal.epochMillis,
                )
            )
        } catch (e: Exception) {
            // Surface the real problem rather than pretending the meal saved.
            return@withContext LogResult.Failed(e.message ?: "Couldn't save that meal.")
        }
        // Only reflect it in the dashboard once it's actually on disk.
        meals.add(meal)
        LogResult.Saved
    }

    /** Cached reel recipe for [sourceKey], or null if never analyzed. */
    suspend fun getReelRecipe(sourceKey: String): com.arokya.app.data.db.ReelRecipeDao.Row? =
        withContext(Dispatchers.IO) { com.arokya.app.data.db.ReelRecipeDao.get(db, sourceKey) }

    /** Every saved reel recipe, newest first. */
    suspend fun allReelRecipes(): List<com.arokya.app.data.db.ReelRecipeDao.Row> =
        withContext(Dispatchers.IO) { com.arokya.app.data.db.ReelRecipeDao.getAll(db) }

    suspend fun saveReelRecipe(row: com.arokya.app.data.db.ReelRecipeDao.Row) =
        withContext(Dispatchers.IO) { com.arokya.app.data.db.ReelRecipeDao.upsert(db, row) }

    /** Every reminder, newest-trigger first, for the reminders screen. */
    suspend fun allReminders(): List<com.arokya.app.data.db.ReminderDao.Row> =
        withContext(Dispatchers.IO) { com.arokya.app.data.db.ReminderDao.all(db) }

    suspend fun deleteReminder(id: Long) =
        withContext(Dispatchers.IO) { com.arokya.app.data.db.ReminderDao.delete(db, id) }

    /** Every meal ever logged, across all days — for the history/calendar view. */
    suspend fun mealHistory(): List<Meal> = withContext(Dispatchers.IO) {
        MealDao.getAll(db).map {
            Meal(
                name = it.name, kcal = it.kcal, proteinG = it.proteinG,
                carbsG = it.carbsG, timeLabel = it.timeLabel, fatG = it.fatG,
                epochMillis = it.epochMillis,
            )
        }
    }

    /**
     * A short, model-readable summary of what the user ate over the last
     * [days] days (excluding today), so suggestions reflect real patterns
     * rather than a single day. Empty when there's no prior history.
     */
    suspend fun recentEatingSummary(days: Int = 3): String = withContext(Dispatchers.IO) {
        val history = mealHistory().filter { !isToday(it.epochMillis) && it.epochMillis > 0 }
        if (history.isEmpty()) return@withContext ""

        val byDay = history.groupBy { calendarDaysAgo(it.epochMillis) }
            .filterKeys { it in 1..days }

        byDay.entries.sortedBy { it.key }.joinToString("; ") { (daysAgo, meals) ->
            val label = if (daysAgo == 1) "yesterday" else "$daysAgo days ago"
            val kcal = meals.sumOf { it.kcal }
            "$label: ${meals.joinToString { it.name }} ($kcal kcal)"
        }
    }

    fun buildContext(): HealthContext = HealthContext(
        stepsToday = stepsToday.intValue,
        stepBaseline = profile.stepBaseline,
        kcalToday = meals.sumOf { it.kcal },
        proteinToday = meals.sumOf { it.proteinG },
        mealsLogged = meals.toList(),
        pantry = pantry.toList(),
        goal = profile.goal,
        dietTags = profile.dietTags.toList(),
        hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        labFindings = profile.labFindings,
    )
}
