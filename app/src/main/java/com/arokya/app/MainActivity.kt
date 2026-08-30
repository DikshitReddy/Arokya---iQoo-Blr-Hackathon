package com.arokya.app

import android.Manifest
import android.net.Uri
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.arokya.app.data.MealAnalysis
import com.arokya.app.data.ScannedItem
import com.arokya.app.data.Store
import com.arokya.app.ml.InferenceStat
import com.arokya.app.ml.Ml
import com.arokya.app.nudge.Nudge
import com.arokya.app.reminder.Reminders
import com.arokya.app.sensor.StepSensor
import com.arokya.app.ui.screens.*
import com.arokya.app.ui.theme.Ar
import com.arokya.app.ui.theme.ArokyaTheme
import kotlinx.coroutines.launch

/**
 * Navigation map:
 *  splash -> onboarding -> permissions -> profile -> goals -> home
 *
 *  Three bottom-bar tabs (home · meals · activity) plus a raised camera
 *  action that opens the scan flow full-screen. Everything else is a
 *  sub-screen pushed on top, with the bar hidden.
 */
object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val PERMISSIONS = "permissions"
    const val PROFILE = "profile"
    const val GOALS = "goals"

    // ---- bottom-bar tabs ----
    const val HOME = "home"
    const val MEALS = "meals"
    const val ACTIVITY = "activity"

    const val VOICE = "voice"
    const val SCAN = "scan"
    const val ANALYZING = "analyzing"
    const val SCAN_RESULTS = "scan_results"
    const val MEAL = "meal"
    const val MEAL_ANALYSIS = "meal_analysis"
    const val EDIT_PROFILE = "edit_profile"
    const val PANTRY = "pantry"
    const val WHY = "why"
    const val RAN = "ran"

    /** Where the proactive nudge lands: a planned meal for the current slot. */
    const val PLAN = "plan"

    /** Where a shared food video lands: the reel-to-recipe pipeline. */
    const val REEL = "reel"

    /** The user's set reminders. */
    const val REMINDERS = "reminders"

    /** The meal diary / daily calendar of everything logged. */
    const val DIARY = "diary"
}

/** Routes that show the bottom navigation bar. */
private val TAB_ROUTES = mapOf(
    Routes.HOME to ArTab.Home,
    Routes.MEALS to ArTab.Meals,
    Routes.ACTIVITY to ArTab.Activity,
)

class MainActivity : ComponentActivity() {

    /** Set when a notification asks us to open straight to a tab. */
    private var openTabRequest by mutableStateOf<String?>(null)

    /** Set when the proactive nudge asks us to open straight to the meal plan. */
    private var openPlanRequest by mutableStateOf(false)

    /** Set when the tile / assist gesture / shortcut asks to open straight into voice. */
    private var openVoiceListening by mutableStateOf(false)

    /** A video or link shared into Arokya via the system share sheet. */
    private var sharedVideo by mutableStateOf<Uri?>(null)
    private var sharedText by mutableStateOf<String?>(null)
    private var openReelRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Vision + chat + lab reading run fully on-device via LiteRT-LM. See
        // ml/MlEndpoints.kt to flip back to the llama.cpp/Termux server
        // (Ml.vision/Ml.llm/Ml.lab = LocalServer*()/FakeLabAnalyzer()).
        Ml.useLiteRtLm(applicationContext)
        // Reads profile/lab findings/meals/pantry from the on-device DB before
        // any screen needs them (see data/Repos.kt, data/db/).
        lifecycleScope.launch { Store.load(applicationContext) }

        // Recurring proactive checkpoint. KEEP, so re-opening the app doesn't
        // reset a schedule that's already counting down — but it DOES restart
        // one that "Not now" cancelled. See nudge/Nudge.kt.
        Nudge.schedule(applicationContext)

        // Real pedometer. No-ops without ACTIVITY_RECOGNITION; the Activity tab
        // prompts for it and calls start() again once granted.
        StepSensor.start(applicationContext)

        // Re-arm any reminders the user set before an app kill (AlarmManager
        // forgets them). Boot is handled separately by BootReceiver.
        Reminders.rescheduleAll(applicationContext)

        openTabRequest = intent?.getStringExtra(Nudge.EXTRA_OPEN_TAB)
        openPlanRequest = intent?.getBooleanExtra(Nudge.EXTRA_OPEN_PLAN, false) == true
        openVoiceListening = readVoiceRequest(intent)
        handleShareIntent(intent)

        setContent {
            ArokyaTheme {
                val nav = rememberNavController()
                // Scan results are passed in memory (simplest reliable way)
                var lastScan by remember { mutableStateOf<List<ScannedItem>>(emptyList()) }
                var lastMealAnalysis by remember { mutableStateOf<MealAnalysis?>(null) }
                var pendingCapture by remember { mutableStateOf<Pair<ScanTab, ByteArray>?>(null) }
                // Measured cost of the inference behind the current result.
                var lastStat by remember { mutableStateOf<InferenceStat?>(null) }

                // The proactive nudge is the whole point of the app, and on
                // Android 13+ every post is silently dropped without this.
                // Asking here (rather than only behind the Home demo button)
                // means a user who never presses that button still gets nudges.
                if (Build.VERSION.SDK_INT >= 33) {
                    val notifPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* granted or not, the schedule is already armed */ }
                    LaunchedEffect(Unit) {
                        if (!Nudge.canPost(applicationContext)) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                val backStackEntry by nav.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                val currentTab = TAB_ROUTES[currentRoute]

                // Notification tap -> jump to the tab it points at. Held until
                // the app has actually settled on Home, so a cold start from a
                // notification still runs splash/onboarding first.
                LaunchedEffect(openTabRequest, currentRoute) {
                    if (openTabRequest == Nudge.TAB_MEALS && currentRoute == Routes.HOME) {
                        nav.switchTab(Routes.MEALS)
                        openTabRequest = null
                    }
                }

                // Same hold-until-Home rule as the tab deep link above: a cold
                // start from the nudge still runs splash/onboarding first, then
                // lands on the plan.
                LaunchedEffect(openPlanRequest, currentRoute) {
                    if (openPlanRequest && currentRoute == Routes.HOME) {
                        nav.navigate(Routes.PLAN)
                        openPlanRequest = false
                    }
                }

                // A share can arrive while the app is on ANY screen (or cold).
                // Wait only for the onboarding flow to finish, then open the
                // reel pipeline wherever the user is.
                val setupRoutes = setOf(
                    Routes.SPLASH, Routes.ONBOARDING, Routes.PERMISSIONS,
                    Routes.PROFILE, Routes.GOALS,
                )
                LaunchedEffect(openReelRequest, currentRoute) {
                    if (openReelRequest && currentRoute != null && currentRoute !in setupRoutes) {
                        nav.navigate(Routes.REEL)
                        openReelRequest = false
                    }
                }

                // Hands-free voice: opened via tile / assist / shortcut. Same
                // wait-for-onboarding rule, then jump straight into the voice
                // screen, which auto-starts the mic (see startListening arg).
                LaunchedEffect(openVoiceListening, currentRoute) {
                    if (openVoiceListening && currentRoute != null && currentRoute !in setupRoutes
                        && currentRoute != Routes.VOICE) {
                        nav.navigate(Routes.VOICE)
                    }
                }

                // Cream fills the whole window (including behind the bars);
                // safeDrawingPadding keeps every screen's content clear of the
                // status bar, camera cutout, nav bar, and the keyboard.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Ar.Cream)
                        .safeDrawingPadding()
                ) {
                    Scaffold(
                        containerColor = Ar.Cream,
                        // The parent Box already applied safeDrawingPadding —
                        // letting Scaffold add window insets again would
                        // double-pad the bottom.
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            if (currentTab != null) {
                                ArBottomNav(
                                    selected = currentTab,
                                    onSelect = { tab ->
                                        when (tab) {
                                            ArTab.Home -> nav.switchTab(Routes.HOME)
                                            ArTab.Meals -> nav.switchTab(Routes.MEALS)
                                            ArTab.Activity -> nav.switchTab(Routes.ACTIVITY)
                                            // Scan launches its flow via onCamera; never routed here.
                                            ArTab.Scan -> nav.navigate(Routes.SCAN)
                                        }
                                    },
                                    onCamera = { nav.navigate(Routes.SCAN) },
                                )
                            }
                        },
                    ) { innerPadding ->
                        NavHost(
                            navController = nav,
                            startDestination = Routes.SPLASH,
                            modifier = Modifier.padding(innerPadding),
                        ) {

                            composable(Routes.SPLASH) {
                                // Wait for BOTH the splash animation and the DB read
                                // before deciding where to go — otherwise a returning
                                // user can be sent back through onboarding just because
                                // their saved profile hadn't loaded yet.
                                val storeLoaded by Store.loaded
                                var animationDone by remember { mutableStateOf(false) }
                                SplashScreen(onDone = { animationDone = true })
                                LaunchedEffect(storeLoaded, animationDone) {
                                    if (storeLoaded && animationDone) {
                                        val next =
                                            if (Store.onboarded.value) Routes.HOME else Routes.ONBOARDING
                                        nav.navigate(next) {
                                            popUpTo(Routes.SPLASH) { inclusive = true }
                                        }
                                    }
                                }
                            }

                            composable(Routes.ONBOARDING) {
                                OnboardingScreen(onDone = { nav.navigate(Routes.PERMISSIONS) })
                            }

                            composable(Routes.PERMISSIONS) {
                                PermissionsScreen(onDone = { nav.navigate(Routes.PROFILE) })
                            }

                            // Profile is the LAST onboarding step now — the
                            // "What matters to you?" goal screen was removed, so
                            // finishing the profile completes onboarding.
                            composable(Routes.PROFILE) {
                                ProfileDetailsScreen(onDone = {
                                    nav.navigate(Routes.HOME) {
                                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                                    }
                                })
                            }

                            // ---------------- TAB: Home ----------------
                            composable(Routes.HOME) {
                                HomeScreen(
                                    onTalk = { nav.navigate(Routes.VOICE) },
                                    onScan = { nav.navigate(Routes.SCAN) },
                                    onPantry = { nav.navigate(Routes.PANTRY) },
                                    onActivity = { nav.switchTab(Routes.ACTIVITY) },
                                    onMeals = { nav.switchTab(Routes.MEALS) },
                                    onWhyThis = { nav.navigate(Routes.WHY) },
                                    onProfile = { nav.navigate(Routes.EDIT_PROFILE) },
                                    onReminders = { nav.navigate(Routes.REMINDERS) },
                                    onDiary = { nav.navigate(Routes.DIARY) },
                                )
                            }

                            // ---------------- TAB: Meals ----------------
                            composable(Routes.MEALS) {
                                MealPlanScreen(
                                    onScanMeal = { nav.navigate(Routes.SCAN) },
                                    onVideoPicked = { uri ->
                                        sharedVideo = uri
                                        sharedText = null
                                        nav.navigate(Routes.REEL)
                                    },
                                    onFridgeToMeal = {
                                        // No fresh scan on this path — the meal
                                        // screen falls back to the pantry.
                                        lastScan = emptyList()
                                        nav.navigate(Routes.MEAL)
                                    },
                                    onWhyThis = { nav.navigate(Routes.WHY) },
                                )
                            }

                            // ---------------- TAB: Activity ----------------
                            composable(Routes.ACTIVITY) {
                                ActivityScreen()
                            }

                            composable(Routes.VOICE) {
                                VoiceCoachScreen(
                                    onBack = { nav.popBackStack() },
                                    onMeal = {
                                        lastScan = emptyList()
                                        nav.navigate(Routes.MEAL)
                                    },
                                    autoStartListening = openVoiceListening,
                                )
                                // Consume the request once we've arrived, so
                                // going Back doesn't relaunch the mic.
                                LaunchedEffect(Unit) { openVoiceListening = false }
                            }

                            composable(Routes.SCAN) {
                                ScanScreen(
                                    onBack = { nav.popBackStack() },
                                    onCaptured = { mode, jpeg ->
                                        pendingCapture = mode to jpeg
                                        nav.navigate(Routes.ANALYZING)
                                    },
                                )
                            }

                            composable(Routes.ANALYZING) {
                                pendingCapture?.let { (mode, jpeg) ->
                                    AnalyzingScreen(
                                        mode = mode,
                                        jpegBytes = jpeg,
                                        onMealAnalyzed = { analysis, stat ->
                                            lastMealAnalysis = analysis
                                            lastStat = stat
                                            nav.navigate(Routes.MEAL_ANALYSIS) {
                                                popUpTo(Routes.ANALYZING) { inclusive = true }
                                            }
                                        },
                                        onResults = { items, stat ->
                                            lastScan = items
                                            lastStat = stat
                                            nav.navigate(Routes.SCAN_RESULTS) {
                                                popUpTo(Routes.ANALYZING) { inclusive = true }
                                            }
                                        },
                                        onBack = { nav.popBackStack() },
                                    )
                                }
                            }

                            composable(Routes.SCAN_RESULTS) {
                                ScanResultsScreen(
                                    items = lastScan,
                                    stat = lastStat,
                                    onBack = { nav.popBackStack() },
                                    onSuggest = { edited ->
                                        lastScan = edited
                                        nav.navigate(Routes.MEAL)
                                    },
                                )
                            }

                            composable(Routes.MEAL) {
                                MealSuggestionScreen(
                                    items = lastScan,
                                    onBack = { nav.popBackStack() },
                                    onWhyThis = { nav.navigate(Routes.WHY) },
                                    // Logged meals live on the Meals tab.
                                    onChooseMeal = { nav.switchTab(Routes.MEALS) },
                                    onScanAgain = { nav.navigate(Routes.SCAN) },
                                )
                            }

                            composable(Routes.MEAL_ANALYSIS) {
                                lastMealAnalysis?.let { analysis ->
                                    MealAnalysisResultsScreen(
                                        analysis = analysis,
                                        stat = lastStat,
                                        onBack = { nav.popBackStack() },
                                        onScanAgain = { nav.navigate(Routes.SCAN) },
                                        onLogged = { nav.switchTab(Routes.MEALS) },
                                    )
                                }
                            }

                            composable(Routes.REMINDERS) {
                                RemindersScreen(onBack = { nav.popBackStack() })
                            }

                            composable(Routes.DIARY) {
                                MealDiaryScreen(onBack = { nav.popBackStack() })
                            }

                            composable(Routes.REEL) {
                                ReelAnalysisScreen(
                                    videoUri = sharedVideo,
                                    sharedText = sharedText,
                                    onBack = {
                                        if (!nav.popBackStack()) nav.switchTab(Routes.HOME)
                                    },
                                    onLogged = { nav.switchTab(Routes.MEALS) },
                                )
                            }

                            composable(Routes.PLAN) {
                                NextMealPlanScreen(
                                    onBack = {
                                        // Opened cold from a notification there's
                                        // nothing to pop back to, so land on Home.
                                        if (!nav.popBackStack()) nav.switchTab(Routes.HOME)
                                    },
                                    onLogged = { nav.switchTab(Routes.MEALS) },
                                    onScanInstead = { nav.navigate(Routes.SCAN) },
                                )
                            }

                            composable(Routes.EDIT_PROFILE) {
                                EditProfileScreen(
                                    onBack = { nav.popBackStack() },
                                    onWhereThisRan = { nav.navigate(Routes.RAN) },
                                )
                            }

                            composable(Routes.PANTRY) {
                                PantryScreen(onBack = { nav.popBackStack() })
                            }

                            composable(Routes.WHY) {
                                WhyThisScreen(onBack = { nav.popBackStack() })
                            }

                            composable(Routes.RAN) {
                                WhereThisRanScreen(onBack = { nav.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    /** The activity is singleTop, so a notification tap while running lands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTabRequest = intent.getStringExtra(Nudge.EXTRA_OPEN_TAB)
        openPlanRequest = intent.getBooleanExtra(Nudge.EXTRA_OPEN_PLAN, false)
        openVoiceListening = readVoiceRequest(intent)
        handleShareIntent(intent)
    }

    /** A share-sheet arrival: a video runs the pipeline, bare text gets explained. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        when {
            intent.type?.startsWith("video/") == true -> {
                sharedVideo = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                sharedText = null
                openReelRequest = sharedVideo != null
            }
            intent.type == "text/plain" -> {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                sharedVideo = null
                openReelRequest = sharedText != null
            }
        }
    }

    /**
     * True when Arokya was opened to talk, hands-free — via the Quick Settings
     * tile, the launcher shortcut (our own extra), or the system assist gesture
     * (ACTION_ASSIST), which fires when Arokya is the device's assistant app.
     */
    private fun readVoiceRequest(intent: Intent?): Boolean {
        if (intent == null) return false
        // Tile sets a real boolean; the manifest shortcut can only set a string.
        val fromExtra = intent.getBooleanExtra(EXTRA_START_LISTENING, false) ||
                intent.getStringExtra(EXTRA_START_LISTENING) == "true"
        return fromExtra || intent.action == Intent.ACTION_ASSIST
    }

    companion object {
        const val EXTRA_START_LISTENING = "arokya.start_listening"
    }
}

/**
 * Switches bottom-bar tabs without stacking them: each tab keeps its own
 * scroll/state, and back from any tab returns to Home rather than walking
 * through every tab the user has visited.
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
