package com.arokya.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arokya.app.data.Store
import com.arokya.app.engine.DayBudget
import com.arokya.app.engine.NutritionTargets
import com.arokya.app.ml.InferenceStat
import com.arokya.app.ml.InferenceStats
import com.arokya.app.ml.ReelProgress
import com.arokya.app.ml.ReelRecipe
import com.arokya.app.ml.analyzeFoodVideo
import com.arokya.app.ml.analyzeReelLink
import com.arokya.app.ml.cachedReel
import com.arokya.app.ml.reelSourceKey
import com.arokya.app.ml.saveReel
import com.arokya.app.ui.theme.Ar
import java.net.URLEncoder

/**
 * REEL -> RECIPE. Where a shared food video lands.
 *
 * The progress here is REAL, unlike the cosmetic step lists elsewhere: each
 * thumbnail appears the moment that frame goes to the model, so the user
 * watches the app actually watch their video.
 *
 * End state is the bifurcation: COOK IT (steps + log it as a meal) or ORDER IT
 * (hand the dish name to Swiggy). The lighter-version card appears only when
 * the ENGINE flags the dish as heavy for this user's day.
 */
@Composable
fun ReelAnalysisScreen(
    videoUri: Uri?,
    sharedText: String?,
    onBack: () -> Unit,
    onLogged: () -> Unit,
) {
    var uri by remember(videoUri) { mutableStateOf(videoUri) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { picked -> if (picked != null) uri = picked }

    Column(
        Modifier.fillMaxSize().background(Ar.Cream).verticalScroll(rememberScrollState())
    ) {
        ArHeader("Reel to recipe", onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp)) {
            when {
                uri != null -> ReelPipeline(
                    source = ReelSource.LocalVideo(uri!!),
                    onLogged = onLogged,
                    onPickAnother = { picker.launch("video/*") },
                )
                sharedText != null && com.arokya.app.ml.InstagramFetch.looksLikeReel(sharedText) ->
                    ReelPipeline(
                        source = ReelSource.InstagramLink(sharedText),
                        onLogged = onLogged,
                        onPickAnother = { picker.launch("video/*") },
                    )
                sharedText != null -> InstagramLinkExplainer(sharedText) { picker.launch("video/*") }
                else -> PickVideoPrompt { picker.launch("video/*") }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ---------------------------------------------------------------------------
//  Link-only share: be honest about what Instagram hands over.
// ---------------------------------------------------------------------------

@Composable
private fun InstagramLinkExplainer(sharedText: String, onPick: () -> Unit) {
    val isInstagram = sharedText.contains("instagram.com", ignoreCase = true)
    ArCard {
        Text(
            if (isInstagram) "Instagram shared a link, not the video"
            else "That share didn't include a video",
            fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ar.Navy,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isInstagram)
                "Instagram's share button sends only the reel's web address. Reading " +
                        "the video from that address would mean sending your activity to " +
                        "Instagram's servers — and everything in Arokya runs on this phone.\n\n" +
                        "To analyze the reel:\n" +
                        "1. In Instagram, use ⋯ → Download (or screen-record the reel)\n" +
                        "2. Share the saved video to Arokya — or pick it below"
            else
                "Share a video file (a downloaded reel, a screen recording, or any " +
                        "clip from your gallery) and Arokya will turn it into a recipe.",
            fontSize = 13.5.sp, color = Ar.Slate, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        ArPrimaryButton("Pick the saved video", onClick = onPick)
    }
}

@Composable
private fun PickVideoPrompt(onPick: () -> Unit) {
    ArCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(Ar.TealChipBg, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.VideoLibrary, null, tint = Ar.Teal, modifier = Modifier.size(21.dp)) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Turn a food video into a recipe", fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                Text("Reels, shorts, or anything in your gallery.",
                    fontSize = 12.sp, color = Ar.Muted)
            }
        }
        Spacer(Modifier.height(14.dp))
        ArPrimaryButton("Choose a video", onClick = onPick)
    }
}

// ---------------------------------------------------------------------------
//  The pipeline view: real progress -> recipe page.
// ---------------------------------------------------------------------------

/** What the pipeline is running on. */
private sealed interface ReelSource {
    data class LocalVideo(val uri: Uri) : ReelSource
    data class InstagramLink(val text: String) : ReelSource
}

@Composable
private fun ReelPipeline(source: ReelSource, onLogged: () -> Unit, onPickAnother: () -> Unit) {
    val context = LocalContext.current

    var recipe by remember(source) { mutableStateOf<ReelRecipe?>(null) }
    var stat by remember(source) { mutableStateOf<InferenceStat?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var stage by remember(source) { mutableStateOf("Starting…") }
    val frames = remember(source) { mutableStateListOf<Bitmap>() }
    var retryKey by remember(source) { mutableIntStateOf(0) }

    // For a link, the fetch touches Instagram. Show that plainly, once.
    val isLink = source is ReelSource.InstagramLink
    // True when this result came from the cache, not a fresh run.
    var fromCache by remember(source) { mutableStateOf(false) }

    val sourceKey = remember(source) {
        when (source) {
            is ReelSource.LocalVideo -> reelSourceKey(null, source.uri)
            is ReelSource.InstagramLink -> reelSourceKey(source.text, null)
        }
    }

    // retryKey == 0 uses the cache; "Analyze again" bumps it to force a re-run.
    LaunchedEffect(source, retryKey) {
        recipe = null; error = null; frames.clear(); fromCache = false

        // Fast path: a saved recipe for this exact reel — no CPU, no network.
        if (retryKey == 0) {
            val saved = cachedReel(sourceKey)
            if (saved != null) {
                recipe = saved
                fromCache = true
                return@LaunchedEffect
            }
        }

        stage = if (isLink) "Contacting Instagram…" else "Reading the video…"
        val onProgress: (ReelProgress) -> Unit = { progress ->
            when (progress) {
                is ReelProgress.FetchingFromInstagram -> stage = "Fetching the reel from Instagram…"
                is ReelProgress.Preparing -> stage = "Reading the video…"
                is ReelProgress.Frame -> {
                    frames.add(progress.thumbnail)
                    stage = "Watching frame ${progress.index} of ${progress.total}…"
                }
                is ReelProgress.WritingRecipe -> stage = "Writing the recipe…"
            }
        }
        try {
            val fresh = when (source) {
                is ReelSource.LocalVideo ->
                    analyzeFoodVideo(context, source.uri, Store.buildContext(), onProgress)
                is ReelSource.InstagramLink ->
                    analyzeReelLink(context, source.text, Store.buildContext(), onProgress)
            }
            recipe = fresh
            stat = InferenceStats.last
            // Save so re-opening the same reel is instant. Timestamp comes from
            // the UI thread (the ml layer stays clock-free).
            saveReel(sourceKey, fresh, System.currentTimeMillis())
        } catch (e: Exception) {
            error = e.message ?: "Couldn't analyze that reel."
        }
    }

    // One-time disclosure banner for the network path.
    if (isLink) {
        Row(
            Modifier.fillMaxWidth()
                .background(Ar.OrangeChipBg, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.PriorityHigh, null, tint = Ar.Orange, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "Fetching the video contacts Instagram's servers — the one time Arokya " +
                        "leaves your phone. The analysis itself still runs on-device.",
                fontSize = 11.sp, color = Ar.Slate, lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    // ---- captured frames: evidence the app is really watching ----
    if (frames.isNotEmpty()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            frames.forEach { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 74.dp, height = 98.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    when {
        error != null -> ArCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PriorityHigh, null, tint = Ar.Orange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Couldn't finish", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
            }
            Spacer(Modifier.height(8.dp))
            Text(error!!, fontSize = 13.sp, color = Ar.Slate, lineHeight = 19.sp)
            Spacer(Modifier.height(14.dp))
            ArPrimaryButton("Try again") { retryKey++ }
            Spacer(Modifier.height(8.dp))
            // On the link path, the reliable escape hatch is the file share.
            ArSecondaryButton(
                if (isLink) "Download the reel & pick it instead" else "Pick a different video",
                onClick = onPickAnother,
            )
        }

        recipe == null -> ArCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Ar.Teal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stage, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ar.Navy)
                    Text(
                        "Each frame is a full on-device vision pass — a minute or two on CPU.",
                        fontSize = 11.5.sp, color = Ar.Muted, lineHeight = 16.sp,
                    )
                }
            }
        }

        else -> RecipeResult(recipe!!, stat, fromCache, { retryKey++ }, onLogged)
    }
}

// ---------------------------------------------------------------------------
//  The recipe page.
// ---------------------------------------------------------------------------

@Composable
private fun RecipeResult(
    r: ReelRecipe,
    stat: InferenceStat?,
    fromCache: Boolean,
    onReanalyze: () -> Unit,
    onLogged: () -> Unit,
) {
    val context = LocalContext.current
    var cooking by remember { mutableStateOf(false) }

    // Saved-recipe banner: this reel was analyzed before, so this is instant.
    if (fromCache) {
        Row(
            Modifier.fillMaxWidth()
                .background(Ar.TealChipBg, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Check, null, tint = Ar.Teal, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text("Saved recipe — shown instantly", fontSize = 12.sp,
                fontWeight = FontWeight.Medium, color = Ar.Navy, modifier = Modifier.weight(1f))
            Text("Analyze again", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = Ar.Teal, modifier = Modifier.clickable { onReanalyze() })
        }
        Spacer(Modifier.height(12.dp))
    }

    // ENGINE decisions: is the dish heavy for THIS user, and does it fit today?
    val isHigh = NutritionTargets.isHighCalorie(r.kcal, Store.profile)
    val remaining = DayBudget.remaining(
        NutritionTargets.forProfile(Store.profile), Store.meals.toList(),
    )

    // ---- dish card ----
    ArCard {
        Text("FROM YOUR VIDEO", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            color = Ar.Purple, letterSpacing = 1.2.sp,
            modifier = Modifier.background(Ar.PurpleChipBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp))
        Spacer(Modifier.height(10.dp))
        Text(r.dishName, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ar.Navy, lineHeight = 27.sp)
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ArChip("${r.kcal} kcal / serving", Ar.Blue, Ar.BlueChipBg)
            ArChip("${r.proteinG} g protein", Ar.Teal, Ar.TealChipBg)
            ArChip("${r.carbsG} g carbs", Ar.Amber, Ar.AmberChipBg)
            ArChip("${r.fatG} g fat", Ar.Orange, Ar.OrangeChipBg)
        }
        if (r.summary.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(r.summary, fontSize = 13.5.sp, color = Ar.Slate, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (r.kcal <= remaining.kcal) "Fits today — ${remaining.kcal} kcal still open."
            else "Heavy for today — ${r.kcal - remaining.kcal.coerceAtLeast(0)} kcal past what's left.",
            fontSize = 12.sp,
            color = if (r.kcal <= remaining.kcal) Ar.Teal else Ar.Orange,
            fontWeight = FontWeight.Medium,
        )
    }

    if (stat != null) {
        Spacer(Modifier.height(10.dp))
        InferenceChip(stat)
    }

    // ---- THE FORK: cook it or order it ----
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ForkOption(
            icon = Icons.Filled.Restaurant,
            title = "Cook it",
            sub = "Steps + log it",
            selected = cooking,
            modifier = Modifier.weight(1f),
        ) { cooking = true }
        ForkOption(
            icon = Icons.Filled.DeliveryDining,
            title = "Order it",
            sub = "Find on Swiggy",
            selected = false,
            modifier = Modifier.weight(1f),
        ) { orderOnSwiggy(context, r.dishName) }
    }

    // ---- lighter version: shown only when the ENGINE flags the dish ----
    if (isHigh && r.lighterName != null) {
        Spacer(Modifier.height(14.dp))
        ArCard {
            Text("LIGHTER TAKE", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                color = Ar.Orange, letterSpacing = 1.2.sp,
                modifier = Modifier.background(Ar.OrangeChipBg, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "One serving is over a third of your day's budget, so here's the same " +
                        "dish, lighter:",
                fontSize = 12.sp, color = Ar.Muted, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(r.lighterName, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold,
                    color = Ar.Navy, modifier = Modifier.weight(1f))
                if (r.lighterKcal > 0) {
                    ArChip("${r.lighterKcal} kcal", Ar.Teal, Ar.TealChipBg)
                }
            }
            if (r.lighterSwaps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                r.lighterSwaps.forEach { swap ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Text("↺", fontSize = 12.sp, color = Ar.Teal)
                        Spacer(Modifier.width(7.dp))
                        Text(swap, fontSize = 13.sp, color = Ar.Slate, lineHeight = 18.sp)
                    }
                }
            }
        }
    }

    // ---- ingredients ----
    Spacer(Modifier.height(14.dp))
    ArCard {
        Text("INGREDIENTS", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            color = Ar.Muted, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        r.ingredients.forEach { ingredient ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(5.dp).background(Ar.Teal, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(ingredient, fontSize = 14.sp, color = Ar.Navy, lineHeight = 20.sp,
                    modifier = Modifier.weight(1f))
                val inPantry = Store.pantry.any { p ->
                    ingredient.contains(p.name, ignoreCase = true)
                }
                if (inPantry) Text("in pantry", fontSize = 10.5.sp, color = Ar.Teal)
            }
        }
    }

    // ---- steps ----
    Spacer(Modifier.height(12.dp))
    ArCard {
        Text("HOW TO MAKE IT", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            color = Ar.Muted, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
        r.steps.forEachIndexed { index, step ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(22.dp).background(Ar.TealChipBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ar.Teal)
                }
                Spacer(Modifier.width(10.dp))
                Text(step, fontSize = 14.sp, color = Ar.Navy, lineHeight = 21.sp)
            }
        }
    }

    // ---- cook mode: after "Cook it", logging the meal is the natural end ----
    if (cooking) {
        Spacer(Modifier.height(16.dp))
        LogMealButton(
            mealName = r.dishName,
            kcal = r.kcal,
            proteinG = r.proteinG,
            carbsG = r.carbsG,
            fatG = r.fatG,
            label = "I made this — log it",
            onLogged = onLogged,
        )
    }
}

@Composable
private fun ForkOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    sub: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .background(if (selected) Ar.Teal else Ar.CardBg, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (selected) androidx.compose.ui.graphics.Color.White else Ar.Teal,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(7.dp))
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) androidx.compose.ui.graphics.Color.White else Ar.Navy)
        Text(sub, fontSize = 11.sp,
            color = if (selected) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f) else Ar.Muted)
    }
}

/**
 * Hands the dish to Swiggy's search. The https URL opens the Swiggy app when
 * installed (app links) and the website otherwise — no fragile custom scheme.
 */
private fun orderOnSwiggy(context: android.content.Context, dish: String) {
    val url = "https://www.swiggy.com/search?query=" + URLEncoder.encode(dish, "UTF-8")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser or Swiggy app available", Toast.LENGTH_SHORT).show()
    }
}
