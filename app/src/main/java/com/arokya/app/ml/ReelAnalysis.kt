package com.arokya.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.arokya.app.data.HealthContext
import com.arokya.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * ============================================================
 *  FOOD VIDEO -> RECIPE — the "share a reel" pipeline.
 * ============================================================
 *
 * A shared video (a downloaded reel, a screen recording, any gallery clip)
 * goes through three stages, all on-device:
 *
 *   1. FRAMES   — [MediaMetadataRetriever] pulls stills at fixed fractions of
 *                 the runtime. No decoder service, no ffmpeg, stock Android.
 *   2. EYES     — [Ml.describer] looks at each frame separately and writes a
 *                 short factual note: dish stage, visible ingredients, cooking
 *                 action, and any ON-SCREEN TEXT (reels routinely overlay
 *                 ingredient lists — the vision model can read them, which is
 *                 often worth more than the pictures).
 *   3. RECIPE   — [Ml.llm] gets all the notes plus the user's diet/goal/labs
 *                 and reconstructs ONE practical recipe with macros, and a
 *                 genuinely lighter version of the same dish.
 *
 * Whether the lighter version is SHOWN is not the model's call — the engine
 * decides (see NutritionTargets.isHighCalorie), same split as everywhere else.
 *
 * NOTE ON INSTAGRAM: sharing a reel from the Instagram app sends a URL, not
 * the video. Fetching that URL would need Instagram's servers — a cloud call
 * this app deliberately doesn't make. The UI explains the workaround
 * (download the reel / screen-record, then share the file); this pipeline
 * only ever sees real video bytes.
 */

data class ReelRecipe(
    val dishName: String,
    val summary: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val ingredients: List<String>,
    val steps: List<String>,
    /** A lower-calorie take on the same dish. Display is gated by the ENGINE. */
    val lighterName: String?,
    val lighterKcal: Int,
    val lighterSwaps: List<String>,
)

// ---------------------------------------------------------------------------
//  Persistence: a generated recipe is expensive (minute+ of CPU), so it's
//  cached by the reel's identity. Re-opening the same reel returns the saved
//  result instantly instead of re-running the whole pipeline.
// ---------------------------------------------------------------------------

/** Newline-join round-trips cleanly — no ingredient or step contains a newline. */
private fun List<String>.encode(): String = joinToString("\n")
private fun String.decodeList(): List<String> =
    split("\n").map { it.trim() }.filter { it.isNotBlank() }

private fun com.arokya.app.data.db.ReelRecipeDao.Row.toRecipe() = ReelRecipe(
    dishName = dishName, summary = summary,
    kcal = kcal, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
    ingredients = ingredients.decodeList(), steps = steps.decodeList(),
    lighterName = lighterName, lighterKcal = lighterKcal,
    lighterSwaps = lighterSwaps.decodeList(),
)

private fun ReelRecipe.toRow(sourceKey: String, createdAt: Long) =
    com.arokya.app.data.db.ReelRecipeDao.Row(
        sourceKey = sourceKey, dishName = dishName, summary = summary,
        kcal = kcal, proteinG = proteinG, carbsG = carbsG, fatG = fatG,
        ingredients = ingredients.encode(), steps = steps.encode(),
        lighterName = lighterName, lighterKcal = lighterKcal,
        lighterSwaps = lighterSwaps.encode(), createdAt = createdAt,
    )

/**
 * Stable identity for a shared reel, used as the cache key:
 *  - an Instagram link -> its shortcode (same reel = same key across devices);
 *  - a local video    -> its content-URI string (stable per picked file).
 */
fun reelSourceKey(instagramText: String?, videoUri: Uri?): String? = when {
    instagramText != null -> InstagramFetch.shortcodeKey(instagramText)
    videoUri != null -> "vid:$videoUri"
    else -> null
}

/** Cached recipe for this source, or null. Stamped Long is passed in (script rule). */
suspend fun cachedReel(sourceKey: String?): ReelRecipe? =
    sourceKey?.let { Store.getReelRecipe(it)?.toRecipe() }

/** Persist a freshly generated recipe against its source. */
suspend fun saveReel(sourceKey: String?, recipe: ReelRecipe, nowMillis: Long) {
    if (sourceKey == null) return
    Store.saveReelRecipe(recipe.toRow(sourceKey, nowMillis))
}

/** Real progress — one event per actual stage, never cosmetic. */
sealed interface ReelProgress {
    /** Only for the link path: contacting Instagram's servers (the one cloud call). */
    data object FetchingFromInstagram : ReelProgress
    data object Preparing : ReelProgress
    /** Fired as each frame goes to the model, with the frame itself for the UI. */
    data class Frame(val index: Int, val total: Int, val thumbnail: Bitmap) : ReelProgress
    data object WritingRecipe : ReelProgress
}

/**
 * Resolves a shared Instagram LINK to a local video file, then runs the same
 * [analyzeFoodVideo] pipeline. This is the only path that touches the network
 * — see [InstagramFetch] for the disclosure rules.
 */
suspend fun analyzeReelLink(
    context: Context,
    sharedText: String,
    healthContext: HealthContext,
    onProgress: (ReelProgress) -> Unit,
): ReelRecipe = withContext(Dispatchers.IO) {
    onProgress(ReelProgress.FetchingFromInstagram)
    val videoUrl = InstagramFetch.resolveVideoUrl(sharedText)
    val cached = File(context.cacheDir, "reel_dl.mp4")
    InstagramFetch.download(videoUrl, cached)
    if (cached.length() <= 0) {
        throw IOException("Instagram returned an empty video — try the download-and-share route.")
    }
    analyzeFoodVideo(context, Uri.fromFile(cached), healthContext, onProgress)
}

/** Fractions of the runtime to sample. Skips intro/outro title cards. */
private val FRAME_POSITIONS = listOf(0.12f, 0.35f, 0.60f, 0.85f)

/**
 * Frames are downscaled harder than single-photo scans (768 vs 1024): the
 * pipeline runs [FRAME_POSITIONS].size vision passes on a phone CPU, and at
 * reel quality nothing readable survives above 768 anyway.
 */
private const val MAX_FRAME_DIMENSION = 768

private const val TAG = "ArokyaReel"

private fun framePrompt(index: Int, total: Int): String =
    "This is frame $index of $total sampled from a short cooking/food video. " +
            "In one or two dense sentences, describe ONLY what is visible: the dish " +
            "or ingredients, any cooking action happening, and any on-screen text " +
            "(ingredient names, quantities, dish title). " +
            "If nothing food-related is visible, respond with exactly: NOTHING_FOOD"

private fun recipePrompt(notes: List<String>, c: HealthContext): String =
    "You watched a short cooking video for a nutrition app user. Frame-by-frame " +
            "notes from the video:\n" +
            notes.mapIndexed { i, n -> "[frame ${i + 1}] $n" }.joinToString("\n") + "\n\n" +
            "The user: goal ${c.goal}; diet ${c.dietTags.joinToString().ifBlank { "unspecified" }}." +
            labFlagsClause(c.labFindings) + "\n\n" +
            "Identify the dish and reconstruct a practical home recipe for it — real " +
            "quantities, real steps, nothing invented that contradicts the frames. " +
            "Estimate nutrition for ONE serving of this specific dish. Then design a " +
            "genuinely lower-calorie version of the SAME dish (ingredient swaps, " +
            "cooking method changes), with its own calorie estimate.\n\n" +
            "Respond in EXACTLY this format — one field per line, STEP repeated once " +
            "per step, no extra commentary:\n" +
            "NAME: <dish name>\n" +
            "SUMMARY: <one sentence about the dish>\n" +
            "CALORIES: <number> kcal per serving\n" +
            "PROTEIN: <number> g\n" +
            "CARBS: <number> g\n" +
            "FAT: <number> g\n" +
            "INGREDIENTS: <ingredient with quantity>; <ingredient>; ...\n" +
            "STEP: <first step>\n" +
            "STEP: <next step>\n" +
            "LIGHTER_NAME: <name of the lighter version>\n" +
            "LIGHTER_CALORIES: <number> kcal per serving\n" +
            "LIGHTER_SWAPS: <change>; <change>; ...\n\n" +
            "If the notes clearly do not describe food, respond with exactly: NOT_FOOD" +
            structuredLanguageDirective()

/**
 * The whole pipeline. Throws with an actionable message on any failure —
 * the screen shows real errors, never an invented recipe.
 */
suspend fun analyzeFoodVideo(
    context: Context,
    uri: Uri,
    healthContext: HealthContext,
    onProgress: (ReelProgress) -> Unit,
): ReelRecipe = withContext(Dispatchers.IO) {
    onProgress(ReelProgress.Preparing)

    // Copy to cache first: share-sheet URI grants can lapse, and a local file
    // keeps MediaMetadataRetriever's many reads cheap and permission-free.
    val cached = File(context.cacheDir, "shared_reel.tmp")
    context.contentResolver.openInputStream(uri)?.use { input ->
        cached.outputStream().use { input.copyTo(it) }
    } ?: throw IOException("Couldn't open that video.")

    try {
        val retriever = MediaMetadataRetriever()
        val notes = mutableListOf<String>()
        try {
            retriever.setDataSource(cached.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) throw IOException("That file doesn't look like a playable video.")
            Log.i(TAG, "Video duration=${durationMs}ms — sampling ${FRAME_POSITIONS.size} frames")

            FRAME_POSITIONS.forEachIndexed { index, position ->
                val frame = retriever.getFrameAtTime(
                    (durationMs * position * 1000).toLong(), // ms -> µs
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: return@forEachIndexed

                val scaled = frame.downscaled(MAX_FRAME_DIMENSION)
                onProgress(ReelProgress.Frame(index + 1, FRAME_POSITIONS.size, scaled))

                // Frame notes are internal scratch — always English; only the
                // final recipe is written in the user's language.
                val note = Ml.describer.describe(
                    scaled.toJpeg(),
                    framePrompt(index + 1, FRAME_POSITIONS.size),
                ).trim()
                Log.i(TAG, "frame ${index + 1}: \"$note\"")
                if (!note.contains("NOTHING_FOOD", ignoreCase = true) && note.isNotBlank()) {
                    notes += note
                }
            }
        } finally {
            retriever.release()
        }

        if (notes.isEmpty()) {
            throw IOException(
                "Couldn't spot any food in that video — is it actually a cooking or food clip?"
            )
        }

        onProgress(ReelProgress.WritingRecipe)
        val reply = Ml.llm.respond(
            listOf(ChatTurn(fromUser = true, text = recipePrompt(notes, healthContext))),
            healthContext,
        )
        parseReelRecipe(reply)
    } finally {
        cached.delete()
    }
}

private fun parseReelRecipe(reply: String): ReelRecipe {
    if (reply.trim().equals("NOT_FOOD", ignoreCase = true)) {
        throw IOException("The model couldn't find a dish in that video.")
    }

    // Hand-rolled rather than a label map: STEP repeats, so a Map would keep
    // only the last step.
    val fields = mutableMapOf<String, String>()
    val steps = mutableListOf<String>()
    reply.lines().forEach { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@forEach
        val key = line.substring(0, idx).trim().uppercase()
        val value = line.substring(idx + 1).trim()
        if (key == "STEP") { if (value.isNotBlank()) steps += value }
        else fields[key] = value
    }

    fun list(key: String) = fields[key].orEmpty()
        .split(";").map { it.trim() }.filter { it.isNotBlank() }

    val name = fields["NAME"].orEmpty()
    if (name.isBlank() || steps.isEmpty()) {
        throw IOException(
            "The model's answer didn't contain a usable recipe — try again, or share a clearer clip."
        )
    }

    return ReelRecipe(
        dishName = name,
        summary = fields["SUMMARY"].orEmpty(),
        kcal = firstNumber(fields["CALORIES"].orEmpty()),
        proteinG = firstNumber(fields["PROTEIN"].orEmpty()),
        carbsG = firstNumber(fields["CARBS"].orEmpty()),
        fatG = firstNumber(fields["FAT"].orEmpty()),
        ingredients = list("INGREDIENTS"),
        steps = steps,
        lighterName = fields["LIGHTER_NAME"]?.takeIf { it.isNotBlank() },
        lighterKcal = firstNumber(fields["LIGHTER_CALORIES"].orEmpty()),
        lighterSwaps = list("LIGHTER_SWAPS"),
    )
}

private fun Bitmap.downscaled(maxDimension: Int): Bitmap {
    val scale = maxDimension.toFloat() / maxOf(width, height)
    return if (scale < 1f) {
        Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    } else this
}

private fun Bitmap.toJpeg(): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
