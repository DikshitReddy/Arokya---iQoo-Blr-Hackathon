package com.arokya.app.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.arokya.app.data.HealthContext
import com.arokya.app.data.LabFinding
import com.arokya.app.data.MealAnalysis
import com.arokya.app.data.ScannedItem
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Turns a raw network exception into a message that actually tells you what to do. */
private fun describeServerError(e: Exception): Exception = when (e) {
    is SocketTimeoutException -> IOException(
        "The model server didn't respond in time — it may be hung. " +
                "Try restarting llama-server in Termux.", e
    )
    is ConnectException -> IOException(
        "Can't connect to the model server. Check llama-server is running in Termux on port 8080.", e
    )
    else -> e
}

/** Turns a raw LiteRT-LM engine exception into a message that tells you what to do. */
private fun describeOnDeviceError(e: Exception): Exception = when {
    e is IOException -> e // already carries an actionable message (e.g. missing model file)
    else -> IOException("On-device model failed: ${e.message ?: e.javaClass.simpleName}", e)
}

/**
 * The published litertlm-android artifact's [Message] has no `.text` convenience
 * property (unlike the getting_started.md sample, which is ahead of this release) —
 * its reply is a [Content] list, so pull the text blocks out by hand.
 */
private fun Message.textContent(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

/**
 * ============================================================
 *  LITERT-LM ON-DEVICE RUNTIME — one shared Engine + model backing
 *  both the vision and chat endpoints below.
 * ============================================================
 * https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
 *
 * Needs a multimodal .litertlm model (e.g. Gemma3n, which supports both
 * text and image input) pushed to the device once — it's several hundred
 * MB to a few GB, too large to bundle in the APK:
 *
 *   adb push gemma-3n-E2B-it-int4.litertlm \
 *     "/sdcard/Android/data/com.arokya.app/files/models/model.litertlm"
 *
 * engine.initialize() blocks for several seconds while it loads the model,
 * so the very first call (vision or chat, whichever runs first) after app
 * start is slow — both endpoints already call this from Dispatchers.IO.
 */
private object LiteRtLmRuntime {
    private const val TAG = "ArokyaLiteRtLm"
    private const val MODELS_DIR = "models"

    /**
     * Kept deliberately in sync with the [EngineConfig] built below — the
     * per-result chips report this, so if the backend here ever changes to
     * GPU/NPU this label MUST change with it or the app starts lying.
     */
    const val BACKEND_LABEL = "CPU"

    @Volatile private var engine: Engine? = null
    @Volatile private var modelName: String? = null
    private val lock = Any()

    /** Name shown on the per-result chips, e.g. "Gemma 4 E2B". */
    val modelLabel: String get() = modelName ?: "On-device model"

    /**
     * Any `*.litertlm` in the models folder works, so the file can keep its
     * real name (and show it on the chips). A generically-named
     * `model.litertlm` is used last, as a fallback.
     */
    private fun modelFile(context: Context): File? {
        val dir = File(context.getExternalFilesDir(null), MODELS_DIR)
        val candidates = dir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".litertlm", ignoreCase = true)
        }.orEmpty()
        return candidates.sortedBy { it.name.equals("model.litertlm", ignoreCase = true) }.firstOrNull()
    }

    /** "gemma-4-E2B-it.litertlm" -> "Gemma 4 E2B" */
    private fun prettyModelName(fileName: String): String {
        val stem = fileName.substringBeforeLast(".")
        if (stem.equals("model", ignoreCase = true)) return "On-device model"
        return stem.replace('_', ' ').replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() && !it.equals("it", ignoreCase = true) }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
            .ifBlank { "On-device model" }
    }

    /** Loads the model on first call and reuses it after. Blocking — call from Dispatchers.IO. */
    fun ensureEngine(context: Context): Engine {
        engine?.let { return it }
        synchronized(lock) {
            engine?.let { return it }
            val model = modelFile(context)
            if (model == null) {
                val expected = File(File(context.getExternalFilesDir(null), MODELS_DIR), "model.litertlm")
                throw IOException(
                    "No on-device model in ${expected.parent}. Push one first, e.g.:\n" +
                            "adb push <model>.litertlm \"${expected.absolutePath}\""
                )
            }
            Log.i(TAG, "Loading model ${model.absolutePath} (${model.length()} bytes)…")
            val config = EngineConfig(
                modelPath = model.absolutePath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                cacheDir = context.cacheDir.path,
            )
            val newEngine = Engine(config)
            newEngine.initialize()
            Log.i(TAG, "Engine ready")
            modelName = prettyModelName(model.name)
            engine = newEngine
            return newEngine
        }
    }
}

/**
 * Reads the engine's token counter without letting a failure break inference.
 *
 * `getTokenCount()` is a FUNCTION on this Kotlin class, not a property, so it
 * can't be reached as `.tokenCount` — Kotlin only synthesises properties from
 * Java getters.
 */
private fun com.google.ai.edge.litertlm.Conversation.tokenCountOrUnknown(): Int =
    runCatching { getTokenCount() }.getOrDefault(-1)

/** Records one measured inference for the per-result chips. */
private fun recordInference(startMs: Long, tokens: Int) {
    InferenceStats.record(
        model = LiteRtLmRuntime.modelLabel,
        backend = LiteRtLmRuntime.BACKEND_LABEL,
        elapsedMs = System.currentTimeMillis() - startMs,
        tokens = tokens,
    )
}

/**
 * ============================================================
 *  LAB REPORT ENDPOINT — one interface, three possible backends.
 * ============================================================
 *
 * IMPORTANT: this returns MARKERS, never diagnoses. Arokya reads values
 * out of a report and turns them into dietary constraints for the rule
 * engine. Any clinical interpretation belongs to a doctor, and the UI
 * says so.
 *
 * Option A (recommended, matches your privacy pitch):
 *     OnDeviceLabAnalyzer — ML Kit text recognition reads the PDF/image
 *     locally, regex pulls out marker values. Nothing leaves the phone.
 *
 * Option B: a real server. Add to build.gradle.kts:
 *     implementation("com.squareup.okhttp3:okhttp:4.12.0")
 *   and to AndroidManifest.xml:
 *     <uses-permission android:name="android.permission.INTERNET" />
 *   then POST the bytes in RemoteLabAnalyzer below.
 *   NOTE: if you do this, your "Where this ran" screen must stop
 *   claiming zero cloud calls, or the demo contradicts itself.
 *
 * Option C: FakeLabAnalyzer (active now) — instant, reliable for demos.
 */
interface LabReportAnalyzer {
    /** Reads [uri] and returns extracted markers. Throws on unreadable input. */
    suspend fun analyze(context: Context, uri: Uri): List<LabFinding>
}

class FakeLabAnalyzer : LabReportAnalyzer {
    override suspend fun analyze(context: Context, uri: Uri): List<LabFinding> {
        delay(1600) // simulate reading + extraction
        return listOf(
            LabFinding(
                marker = "Haemoglobin",
                value = "11.2 g/dL",
                flag = "Low",
                dietaryNote = "Favour iron-rich foods: spinach, lentils, dates."
            ),
            LabFinding(
                marker = "Vitamin D",
                value = "18 ng/mL",
                flag = "Low",
                dietaryNote = "Fortified milk and egg yolk help; sunlight matters more."
            ),
            LabFinding(
                marker = "HbA1c",
                value = "5.4 %",
                flag = "Normal",
                dietaryNote = "No carbohydrate restriction needed."
            ),
            LabFinding(
                marker = "Total cholesterol",
                value = "192 mg/dL",
                flag = "Normal",
                dietaryNote = "Current fat intake looks fine."
            ),
        )
    }
}

/**
 * SKELETON for the real server version. Left unwired on purpose —
 * fill in your endpoint, add okhttp + INTERNET permission, then set
 * Ml.lab = RemoteLabAnalyzer("https://your-api/analyze") in MlEndpoints.kt.
 */
class RemoteLabAnalyzer(private val endpoint: String) : LabReportAnalyzer {
    override suspend fun analyze(context: Context, uri: Uri): List<LabFinding> {
        // val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        // ... multipart POST to `endpoint`, parse JSON into LabFinding ...
        throw NotImplementedError("Wire up your backend before using RemoteLabAnalyzer.")
    }
}

/** Longest edge a rendered PDF page / photo is downscaled to before it goes to the model. */
private const val MAX_LAB_PAGE_DIMENSION = 1280

private fun labExtractionPrompt() =
    "You are reading a page from a blood/lab test report for a nutrition app. " +
            "Extract EVERY marker or test result you can find on this page. " +
            "Respond with EXACTLY one line per marker, no headers, no numbering, " +
            "no extra commentary, in this exact pipe-separated format:\n" +
            "Marker name | value with units | Low, Normal, or High (use the report's " +
            "own reference range if it's shown) | one short sentence about what this " +
            "means for FOOD choices — never a diagnosis\n\n" +
            "If this page has no lab markers on it, respond with exactly: NONE_ON_PAGE" + structuredLanguageDirective()

/** Shared by [LiteRtLmLabAnalyzer] — turns one page's pipe-separated answer into findings. */
private fun parseLabFindings(answer: String): List<LabFinding> {
    if (answer.equals("NONE_ON_PAGE", ignoreCase = true)) return emptyList()
    return answer.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("|") }
        .mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size < 4) return@mapNotNull null
            LabFinding(
                marker = parts[0],
                value = parts[1],
                flag = normalizeLabFlag(parts[2]),
                dietaryNote = parts[3],
            )
        }
}

/** The rest of the app (chip colors, the [labFlagsClause] filter) expects exactly one of these three. */
private fun normalizeLabFlag(raw: String): String = when {
    raw.contains("high", ignoreCase = true) || raw.contains("elevat", ignoreCase = true) -> "High"
    raw.contains("low", ignoreCase = true) -> "Low"
    else -> "Normal"
}

/**
 * REAL ENDPOINT — reads a lab report fully on-device via the same LiteRT-LM
 * vision engine used for fridge scans (see [LiteRtLmRuntime]). A PDF is
 * rendered page-by-page with Android's built-in [PdfRenderer] (no PDF
 * library needed); a plain image is decoded directly. Every page/photo gets
 * the same extraction prompt and the answers are merged. LIVE ONLY, same
 * rule as [FoodVision]/[Llm]: a file with nothing readable is a real error.
 */
class LiteRtLmLabAnalyzer(private val androidContext: Context) : LabReportAnalyzer {
    companion object { private const val TAG = "ArokyaLiteRtLmLab" }

    override suspend fun analyze(context: Context, uri: Uri): List<LabFinding> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val pages = if (mimeType == "application/pdf") renderPdfPages(context, uri)
            else listOf(decodeImage(context, uri))
            Log.i(TAG, "analyze pages=${pages.size} mime=$mimeType")

            val engine = LiteRtLmRuntime.ensureEngine(androidContext)
            val findings = mutableListOf<LabFinding>()
            var tokens = 0
            pages.forEachIndexed { index, page ->
                val answer = engine.createConversation().use { conversation ->
                    val reply = conversation.sendMessage(
                        Contents.of(
                            Content.ImageBytes(page),
                            Content.Text(labExtractionPrompt()),
                        )
                    ).textContent().trim()
                    conversation.tokenCountOrUnknown().let { if (it > 0) tokens += it }
                    reply
                }
                Log.i(TAG, "page ${index + 1}/${pages.size} answer=\"$answer\"")
                findings += parseLabFindings(answer)
            }
            recordInference(startMs, tokens)

            Log.i(TAG, "SUCCESS elapsed=${System.currentTimeMillis() - startMs}ms findings=${findings.size}")

            if (findings.isEmpty()) {
                throw IOException("Couldn't find any lab markers in that file — try a clearer photo or a different page.")
            }
            findings
        } catch (e: Exception) {
            Log.e(TAG, "FAILED after ${System.currentTimeMillis() - startMs}ms — rethrowing. Cause: $e")
            throw describeOnDeviceError(e)
        }
    }

    private fun renderPdfPages(context: Context, uri: Uri): List<ByteArray> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Couldn't open that file.")
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                return (0 until renderer.pageCount).map { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        val scale = MAX_LAB_PAGE_DIMENSION.toFloat() / maxOf(page.width, page.height)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        // PDF pages render onto a transparent bitmap; a JPEG has no
                        // alpha channel, so fill white first or it turns black.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap.toJpegBytes()
                    }
                }
            }
        }
    }

    private fun decodeImage(context: Context, uri: Uri): ByteArray {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Couldn't open that file.")
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("That doesn't look like an image or PDF Arokya can read.")
        val scale = MAX_LAB_PAGE_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
        val resized = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded
        return resized.toJpegBytes()
    }

    private fun Bitmap.toJpegBytes(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

/**
 * ============================================================
 *  FRIDGE VISION ENDPOINT — camera photo -> detected grocery items,
 *  judged against the user's diet plan. LIVE ONLY, same rule as Llm:
 *  no mock implementation. A failure must surface as a real error.
 * ============================================================
 */
interface FoodVision {
    /** Analyzes a JPEG photo and returns every food item it can identify. */
    suspend fun detectIngredients(
        jpegBytes: ByteArray,
        dietTags: List<String>,
        goal: String,
    ): List<ScannedItem>
}

/**
 * REAL ENDPOINT — sends the photo to the same OpenAI-compatible server as
 * [LocalServerLlm], using the vision `image_url` content block:
 *
 *   curl http://127.0.0.1:8080/v1/chat/completions \
 *     -d '{"messages":[{"role":"user","content":[
 *            {"type":"text","text":"..."},
 *            {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}
 *          ]}]}'
 *
 * Requires a vision-capable model (a GGUF loaded with its mmproj) on the
 * server — a text-only model will error, and that error is surfaced as-is.
 */
class LocalServerVision(
    private val baseUrl: String = "http://127.0.0.1:8080",
) : FoodVision {
    companion object { private const val TAG = "ArokyaVision" }

    override suspend fun detectIngredients(
        jpegBytes: ByteArray,
        dietTags: List<String>,
        goal: String,
    ): List<ScannedItem> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            Log.i(TAG, "POST $baseUrl/v1/chat/completions  bytes=${jpegBytes.size} diet=$dietTags goal=$goal")

            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val prompt = foodVisionPrompt(dietTags, goal)

            val requestBody = JSONObject().apply {
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$b64")
                                })
                            })
                        })
                    })
                })
            }

            val connection = URL("$baseUrl/v1/chat/completions")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 8_000
            // Vision inference (image encoding + generation) is much slower than
            // text-only on a phone CPU — give it real headroom.
            connection.readTimeout = 120_000

            connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            connection.disconnect()

            if (status !in 200..299) {
                throw IOException("Server returned $status: $responseText")
            }

            val answer = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            Log.i(TAG, "SUCCESS http=$status elapsed=${System.currentTimeMillis() - startMs}ms answer=\"$answer\"")

            parseScannedItems(answer)
        } catch (e: Exception) {
            Log.e(TAG, "FAILED after ${System.currentTimeMillis() - startMs}ms — rethrowing. Cause: $e")
            throw describeServerError(e)
        }
    }
}

/** Shared by every [FoodVision] backend — same prompt, same answer format. */
private fun foodVisionPrompt(dietTags: List<String>, goal: String): String =
    "You are looking at a photo of a fridge, pantry, or grocery haul for a " +
            "nutrition app. Identify every distinct food item visible. " +
            "The user's diet: ${dietTags.joinToString().ifBlank { "unspecified" }}; " +
            "goal: $goal.\n\n" +
            "Respond with EXACTLY one line per item, no headers, no numbering, " +
            "no extra commentary, in this exact pipe-separated format:\n" +
            "Name | Quantity you can see | yes-or-no does it fit their diet | " +
            "short note (why, or a tip)\n\n" +
            "If you cannot identify any food item, respond with exactly: NONE_DETECTED" + structuredLanguageDirective()

/** Shared by every [FoodVision] backend — turns the model's pipe-separated answer into items. */
private fun parseScannedItems(answer: String): List<ScannedItem> {
    if (answer.equals("NONE_DETECTED", ignoreCase = true)) {
        throw IOException("No food items recognized in that photo — try better lighting or get closer.")
    }

    val items = answer.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("|") }
        .mapNotNull { line ->
            val parts = line.split("|").map { it.trim() }
            if (parts.size < 4) return@mapNotNull null
            ScannedItem(
                name = parts[0],
                quantity = parts[1],
                fitsDiet = parts[2].startsWith("y", ignoreCase = true),
                note = parts[3],
            )
        }

    if (items.isEmpty()) {
        throw IOException("Couldn't parse the model's answer into a list: \"$answer\"")
    }
    return items
}

/**
 * REAL ENDPOINT — runs the same vision prompt fully on-device via LiteRT-LM
 * (see [LiteRtLmRuntime]). No network, no Termux server; the model and its
 * weights live in this process.
 */
class LiteRtLmVision(private val androidContext: Context) : FoodVision {
    companion object { private const val TAG = "ArokyaLiteRtLmVision" }

    override suspend fun detectIngredients(
        jpegBytes: ByteArray,
        dietTags: List<String>,
        goal: String,
    ): List<ScannedItem> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            Log.i(TAG, "sendMessage bytes=${jpegBytes.size} diet=$dietTags goal=$goal")

            val prompt = foodVisionPrompt(dietTags, goal)
            val engine = LiteRtLmRuntime.ensureEngine(androidContext)

            var tokens = -1
            val answer = engine.createConversation().use { conversation ->
                val reply = conversation.sendMessage(
                    Contents.of(
                        Content.ImageBytes(jpegBytes),
                        Content.Text(prompt),
                    )
                ).textContent().trim()
                tokens = conversation.tokenCountOrUnknown()
                reply
            }
            recordInference(startMs, tokens)

            Log.i(TAG, "SUCCESS elapsed=${System.currentTimeMillis() - startMs}ms answer=\"$answer\"")

            parseScannedItems(answer)
        } catch (e: Exception) {
            Log.e(TAG, "FAILED after ${System.currentTimeMillis() - startMs}ms — rethrowing. Cause: $e")
            throw describeOnDeviceError(e)
        }
    }
}

/**
 * ============================================================
 *  PREPARED-MEAL ENDPOINT — camera photo of a cooked dish -> macros +
 *  a diet-plan fit assessment. Separate from [FoodVision]: that one lists
 *  multiple raw ingredients, this one analyzes ONE finished meal. LIVE
 *  ONLY, same rule as everything else here.
 * ============================================================
 */
interface MealPhotoAnalyzer {
    /** Analyzes a photo of a prepared meal against [context]'s goal/diet/lab flags. */
    suspend fun analyzeMeal(jpegBytes: ByteArray, context: HealthContext): MealAnalysis
}

/** Thrown by [Ml.mealAnalyzer]'s placeholder if used before [Ml.useLiteRtLm] wires a real one. */
private class UnconfiguredMealAnalyzer : MealPhotoAnalyzer {
    override suspend fun analyzeMeal(jpegBytes: ByteArray, context: HealthContext): MealAnalysis =
        throw IllegalStateException("Ml.mealAnalyzer isn't wired yet — call Ml.useLiteRtLm(context) at app start.")
}

private fun mealAnalysisPrompt(context: HealthContext): String =
    "You are looking at a photo of a prepared, cooked meal for a nutrition app. " +
            "Identify the dish and estimate its nutrition.\n\n" +
            "The user's goal: ${context.goal}; diet: ${context.dietTags.joinToString().ifBlank { "unspecified" }}; " +
            "so far today: ${context.kcalToday} kcal, ${context.proteinToday} g protein." +
            labFlagsClause(context.labFindings) + "\n\n" +
            "Respond in EXACTLY this format, one field per line, no extra commentary:\n" +
            "NAME: <dish name>\n" +
            "CALORIES: <number> kcal\n" +
            "PROTEIN: <number> g\n" +
            "CARBS: <number> g\n" +
            "FAT: <number> g\n" +
            "FITS_GOAL: yes or no\n" +
            "SUMMARY: <one sentence overall nutritional summary>\n" +
            "RECOMMENDATION: <one or two sentences — how this fits their remaining daily " +
            "targets, and a portion adjustment or healthier alternative if it doesn't fit well>\n\n" +
            "If you cannot identify any food in the photo, respond with exactly: NONE_DETECTED" + structuredLanguageDirective()

/** Shared by [LiteRtLmMealAnalyzer] — turns the model's labeled-line answer into [MealAnalysis]. */
private fun parseMealAnalysis(answer: String): MealAnalysis {
    if (answer.trim().equals("NONE_DETECTED", ignoreCase = true)) {
        throw IOException("No food detected in that photo — try better lighting or get closer.")
    }

    val fields = answer.lines()
        .mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
        }
        .toMap()

    fun field(key: String) = fields[key].orEmpty()
    fun number(key: String) = field(key).filter { it.isDigit() }.toIntOrNull() ?: 0

    val name = field("NAME")
    if (name.isBlank()) {
        throw IOException("Couldn't identify a meal in that photo — try a clearer, closer shot.")
    }

    return MealAnalysis(
        mealName = name,
        kcal = number("CALORIES"),
        proteinG = number("PROTEIN"),
        carbsG = number("CARBS"),
        fatG = number("FAT"),
        fitsGoal = field("FITS_GOAL").startsWith("y", ignoreCase = true),
        summary = field("SUMMARY"),
        recommendation = field("RECOMMENDATION"),
    )
}

/**
 * REAL ENDPOINT — runs the meal-analysis prompt fully on-device via LiteRT-LM
 * (see [LiteRtLmRuntime]), the same engine used for fridge scans and chat.
 */
class LiteRtLmMealAnalyzer(private val androidContext: Context) : MealPhotoAnalyzer {
    companion object { private const val TAG = "ArokyaLiteRtLmMeal" }

    override suspend fun analyzeMeal(jpegBytes: ByteArray, context: HealthContext): MealAnalysis =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            try {
                Log.i(TAG, "sendMessage bytes=${jpegBytes.size} goal=${context.goal}")

                val prompt = mealAnalysisPrompt(context)
                val engine = LiteRtLmRuntime.ensureEngine(androidContext)

                var tokens = -1
                val answer = engine.createConversation().use { conversation ->
                    val reply = conversation.sendMessage(
                        Contents.of(
                            Content.ImageBytes(jpegBytes),
                            Content.Text(prompt),
                        )
                    ).textContent().trim()
                    tokens = conversation.tokenCountOrUnknown()
                    reply
                }
                recordInference(startMs, tokens)

                Log.i(TAG, "SUCCESS elapsed=${System.currentTimeMillis() - startMs}ms answer=\"$answer\"")

                parseMealAnalysis(answer)
            } catch (e: Exception) {
                Log.e(TAG, "FAILED after ${System.currentTimeMillis() - startMs}ms — rethrowing. Cause: $e")
                throw describeOnDeviceError(e)
            }
        }
}

/**
 * ============================================================
 *  CONVERSATION ENDPOINT — the model answers the user's actual
 *  message live. It gets the user's health context as background,
 *  but the reply itself is generated fresh every time, not a
 *  reworded template. <- SWAP POINT for an on-device SLM.
 * ============================================================
 */
/** One turn of the conversation, oldest first. The latest user message is last. */
data class ChatTurn(val fromUser: Boolean, val text: String)

interface Llm {
    /**
     * Continues the conversation in [history] (latest user message last),
     * using [context] as background only. Throws on any failure — callers
     * must surface a real error to the user, never substitute canned text
     * for a model reply.
     */
    suspend fun respond(history: List<ChatTurn>, context: HealthContext): String
}

/**
 * REAL ENDPOINT — talks to the llama.cpp (or any OpenAI-compatible)
 * server running in Termux on this same phone, e.g.:
 *
 *   curl http://127.0.0.1:8080/v1/chat/completions \
 *     -H "Content-Type: application/json" \
 *     -d '{"messages":[{"role":"system","content":"..."},
 *                       {"role":"user","content":"..."}]}'
 *
 * Cleartext to 127.0.0.1 is allowed by res/xml/network_security_config.xml.
 * Every call is a fresh request — the model sees the live question and the
 * current health context, and its wording is never pre-decided.
 */
class LocalServerLlm(
    private val baseUrl: String = "http://127.0.0.1:8080",
) : Llm {
    companion object { private const val TAG = "ArokyaLlm" }

    override suspend fun respond(history: List<ChatTurn>, context: HealthContext): String =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            try {
                val question = history.lastOrNull { it.fromUser }?.text ?: ""
                Log.i(TAG, "POST $baseUrl/v1/chat/completions  question=\"$question\" turns=${history.size}")

                val systemPrompt = coachSystemPrompt(context)
                val sanitized = sanitizeChatHistory(history)

                val requestBody = JSONObject().apply {
                    put("temperature", 0.7)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        sanitized.forEach { turn ->
                            put(JSONObject().apply {
                                put("role", if (turn.fromUser) "user" else "assistant")
                                put("content", turn.text)
                            })
                        }
                    })
                }

                val connection = URL("$baseUrl/v1/chat/completions")
                    .openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 8_000
                connection.readTimeout = 60_000

                connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream.bufferedReader().use { it.readText() }
                connection.disconnect()

                if (status !in 200..299) {
                    throw IOException("Server returned $status: $responseText")
                }

                val json = JSONObject(responseText)
                val answer = json
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                val model = json.optString("model", "?")
                val tokens = json.optJSONObject("usage")?.optInt("total_tokens") ?: -1
                Log.i(
                    TAG, "SUCCESS http=$status model=$model tokens=$tokens " +
                            "elapsed=${System.currentTimeMillis() - startMs}ms " +
                            "answer=\"$answer\""
                )
                answer
            } catch (e: Exception) {
                Log.e(
                    TAG, "FAILED after ${System.currentTimeMillis() - startMs}ms — " +
                            "rethrowing so the UI shows a real error. Cause: $e"
                )
                throw describeServerError(e)
            }
        }
}

/**
 * Shared by every free-text AI call site ([coachSystemPrompt] and Scan.kt's
 * recipe suggester) — only the non-Normal markers, phrased as an instruction
 * to warn, never as a diagnosis. Empty when there's nothing flagged.
 */
fun labFlagsClause(labFindings: List<LabFinding>): String {
    val flagged = labFindings.filter { it.flag != "Normal" }
    if (flagged.isEmpty()) return ""
    return " Lab flags to keep in mind (values only, not a diagnosis) — warn them if " +
            "they ask about or you suggest foods that would worsen these: " +
            flagged.joinToString { "${it.marker} ${it.flag}" } + "."
}

/**
 * Shared by every [Llm] backend. Deliberately SHORT: small models (1B-ish)
 * answer the question in front of them; a long stat-dump system prompt makes
 * them ignore the question and recite the stats instead.
 */
private fun coachSystemPrompt(context: HealthContext): String =
    "You are Arokya, a friendly nutrition coach. Answer the user's " +
            "latest message directly, in a few short sentences. " +
            "Their data, ONLY if they ask about it: " +
            "${context.stepsToday} steps today (usual ${context.stepBaseline}); " +
            "eaten ${context.kcalToday} kcal, ${context.proteinToday} g protein " +
            "in ${context.mealsLogged.size} meals; goal: ${context.goal}; " +
            "diet: ${context.dietTags.joinToString().ifBlank { "unspecified" }}; " +
            "pantry: ${context.pantry.joinToString { it.name }.ifBlank { "empty" }}." +
            labFlagsClause(context.labFindings) + languageDirective()

/**
 * Shared by every [Llm] backend. Gemma's chat template REQUIRES strict
 * user/assistant alternation starting with a user turn — consecutive
 * same-role turns (e.g. after an error bubble was dropped) cause a 400
 * Jinja error server-side, and would confuse an on-device template the
 * same way. Merge same-role runs and drop leading assistant turns.
 */
private fun sanitizeChatHistory(history: List<ChatTurn>): List<ChatTurn> {
    val sanitized = mutableListOf<ChatTurn>()
    for (turn in history.takeLast(8)) {
        when {
            sanitized.isEmpty() -> if (turn.fromUser) sanitized.add(turn)
            sanitized.last().fromUser == turn.fromUser -> {
                val prev = sanitized.removeAt(sanitized.size - 1)
                sanitized.add(ChatTurn(turn.fromUser, prev.text + "\n" + turn.text))
            }
            else -> sanitized.add(turn)
        }
    }
    return sanitized
}

/**
 * REAL ENDPOINT — runs the same coach conversation fully on-device via
 * LiteRT-LM (see [LiteRtLmRuntime]). Every call opens a fresh, short-lived
 * [com.google.ai.edge.litertlm.Conversation] seeded with the prior turns as
 * `initialMessages`, then sends the latest user message — this mirrors the
 * llama.cpp path's "resend the whole conversation every request" behaviour
 * rather than keeping one long-lived Conversation around.
 */
class LiteRtLmLlm(private val androidContext: Context) : Llm {
    companion object { private const val TAG = "ArokyaLiteRtLmLlm" }

    override suspend fun respond(history: List<ChatTurn>, context: HealthContext): String =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            try {
                val sanitized = sanitizeChatHistory(history)
                val lastUser = sanitized.lastOrNull { it.fromUser }?.text.orEmpty()
                val priorTurns = if (sanitized.isNotEmpty()) sanitized.dropLast(1) else sanitized

                Log.i(TAG, "sendMessage turns=${sanitized.size} question=\"$lastUser\"")

                val engine = LiteRtLmRuntime.ensureEngine(androidContext)
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(coachSystemPrompt(context)),
                    initialMessages = priorTurns.map {
                        if (it.fromUser) Message.user(it.text) else Message.model(it.text)
                    },
                )

                var tokens = -1
                val answer = engine.createConversation(conversationConfig).use { conversation ->
                    val reply = conversation.sendMessage(lastUser).textContent().trim()
                    tokens = conversation.tokenCountOrUnknown()
                    reply
                }
                recordInference(startMs, tokens)

                Log.i(TAG, "SUCCESS elapsed=${System.currentTimeMillis() - startMs}ms answer=\"$answer\"")
                answer
            } catch (e: Exception) {
                Log.e(TAG, "FAILED after ${System.currentTimeMillis() - startMs}ms — rethrowing. Cause: $e")
                throw describeOnDeviceError(e)
            }
        }
}

/**
 * ============================================================
 *  FREE-FORM IMAGE DESCRIPTION — the reel pipeline's per-frame eye.
 *  One frame in, a short factual description out. LIVE ONLY, same
 *  rule as everything else here.
 * ============================================================
 */
interface VisionDescriber {
    suspend fun describe(jpegBytes: ByteArray, prompt: String): String
}

/** Thrown by [Ml.describer]'s placeholder if used before [Ml.useLiteRtLm] wires a real one. */
private class UnconfiguredDescriber : VisionDescriber {
    override suspend fun describe(jpegBytes: ByteArray, prompt: String): String =
        throw IllegalStateException("Ml.describer isn't wired yet — call Ml.useLiteRtLm(context) at app start.")
}

/** REAL ENDPOINT — same LiteRT-LM engine as vision/chat (see [LiteRtLmRuntime]). */
class LiteRtLmDescriber(private val androidContext: Context) : VisionDescriber {
    companion object { private const val TAG = "ArokyaLiteRtLmFrame" }

    override suspend fun describe(jpegBytes: ByteArray, prompt: String): String =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            try {
                val engine = LiteRtLmRuntime.ensureEngine(androidContext)
                var tokens = -1
                val answer = engine.createConversation().use { conversation ->
                    val reply = conversation.sendMessage(
                        Contents.of(
                            Content.ImageBytes(jpegBytes),
                            Content.Text(prompt),
                        )
                    ).textContent().trim()
                    tokens = conversation.tokenCountOrUnknown()
                    reply
                }
                recordInference(startMs, tokens)
                Log.i(TAG, "SUCCESS elapsed=${System.currentTimeMillis() - startMs}ms answer=\"$answer\"")
                answer
            } catch (e: Exception) {
                Log.e(TAG, "FAILED after ${System.currentTimeMillis() - startMs}ms — rethrowing. Cause: $e")
                throw describeOnDeviceError(e)
            }
        }
}

/**
 * Single access point for every ML-backed endpoint the UI talks to.
 * Swap any field for a real implementation without touching call sites.
 *
 * NOTE: `llm` and `vision` are LIVE ONLY — there is deliberately no
 * fake/mock implementation for either. If the server is down or can't
 * see the photo, the UI must show a real error, never invented data.
 */
object Ml {
    var lab: LabReportAnalyzer = FakeLabAnalyzer()
    var vision: FoodVision = LocalServerVision()
    var llm: Llm = LocalServerLlm()
    var mealAnalyzer: MealPhotoAnalyzer = UnconfiguredMealAnalyzer()
    var describer: VisionDescriber = UnconfiguredDescriber()

    /**
     * Switches vision + chat + lab-report reading + meal analysis from the
     * llama.cpp/Termux server (and the fake lab analyzer) to the on-device
     * LiteRT-LM engine. Call once at app start (e.g. from
     * MainActivity.onCreate) with the application context. Leaves
     * LocalServerVision/LocalServerLlm/FakeLabAnalyzer untouched — flip back
     * by assigning `Ml.vision`/`Ml.llm`/`Ml.lab` to those instead.
     */
    fun useLiteRtLm(context: Context) {
        val app = context.applicationContext
        vision = LiteRtLmVision(app)
        llm = LiteRtLmLlm(app)
        lab = LiteRtLmLabAnalyzer(app)
        mealAnalyzer = LiteRtLmMealAnalyzer(app)
        describer = LiteRtLmDescriber(app)
    }
}