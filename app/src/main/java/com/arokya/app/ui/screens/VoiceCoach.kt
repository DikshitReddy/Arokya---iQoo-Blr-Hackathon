package com.arokya.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arokya.app.data.Store
import com.arokya.app.ml.ChatTurn
import com.arokya.app.ml.InferenceStat
import com.arokya.app.ml.InferenceStats
import com.arokya.app.ml.Ml
import com.arokya.app.ml.TraceStep
import com.arokya.app.ml.mightNeedTools
import com.arokya.app.ml.respondWithTools
import com.arokya.app.speech.Speaker
import com.arokya.app.speech.VoiceInput
import com.arokya.app.ui.theme.Ar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice coach with LIVE speech:
 *   tap mic  -> real on-device recognition starts (VoiceInput)
 *   speak    -> your words stream into the draft bubble as you talk
 *   tap stop -> recognition finalizes, the query goes to the pipeline
 *
 * PIPELINE: VoiceInput (real ASR) -> Ml.llm.respond() answers live
 * (<- swap point for the on-device SLM) -> Speaker says it aloud.
 *
 * STRICT no-mock rule: every coach bubble is the model server's verbatim
 * reply. If the server can't be reached, the chat shows an explicit
 * error message — never canned text pretending to be an answer.
 */

private enum class CoachState { Idle, Listening, Thinking, Speaking, Paused }

private data class ChatMsg(
    val text: String,
    val fromUser: Boolean,
    val showAvatar: Boolean = false,
    /** Measured cost of the inference that produced THIS bubble. */
    val stat: InferenceStat? = null,
    /** The tool-calling trace behind this answer, if any (thinking/tool steps). */
    val trace: List<TraceStep> = emptyList(),
)

/** Cycled under the thinking bubble while the model works. Cosmetic pacing only. */
private val THINKING_PHRASES = listOf(
    "Reading your question…",
    "Checking your day so far…",
    "Weighing it against your goal…",
    "Cooking up an answer…",
    "Putting it into words…",
)

@Composable
fun VoiceCoachScreen(
    onBack: () -> Unit,
    onMeal: () -> Unit,
    /** When true (opened via the tile / assist gesture / shortcut), start the
     *  mic immediately so it feels like a voice assistant, not a chat screen. */
    autoStartListening: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(CoachState.Idle) }
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var typed by remember { mutableStateOf("") }
    var liveDraft by remember { mutableStateOf<String?>(null) }
    // Live trace of the current tool-calling turn, shown under the thinking bubble.
    val liveTrace = remember { mutableStateListOf<TraceStep>() }
    // True after the coach asked a question (e.g. "what time?") — keeps the next
    // reply on the tool path so it finishes what it started.
    var awaitingToolReply by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val speaker = remember {
        Speaker(context).apply {
            onStart = { state = CoachState.Speaking }
            onDone = { state = CoachState.Idle }
        }
    }
    val voice = remember { VoiceInput(context) }
    DisposableEffect(Unit) {
        onDispose {
            speaker.shutdown()
            voice.destroy()
        }
    }

    // Keep the newest thing in view — including the thinking bubble, so the
    // user sees the model start working rather than an unchanged screen.
    LaunchedEffect(messages.size, liveDraft, state) {
        val extra = (if (liveDraft != null) 1 else 0) +
                (if (state == CoachState.Thinking) 1 else 0)
        val last = messages.size + extra - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    fun ask(question: String) {
        if (question.isBlank() || state == CoachState.Thinking) return
        messages.add(ChatMsg(question, fromUser = true))
        state = CoachState.Thinking
        liveTrace.clear()
        scope.launch {
            val history = messages
                .filter { !it.text.startsWith("\u26a0") }
                .map { ChatTurn(it.fromUser, it.text) }
            val ctx = Store.buildContext()
            try {
                // Date/time and reminder intents run the tool loop; plain
                // nutrition questions stay on the fast single-shot path. Once the
                // coach has asked a follow-up (awaitingToolReply), the reply stays
                // on the tool path even if it's just "7 am".
                if (awaitingToolReply || mightNeedTools(question)) {
                    val turn = respondWithTools(
                        androidContext = context,
                        history = history.dropLast(1),
                        userMessage = question,
                        context = ctx,
                        nowMillis = System.currentTimeMillis(),
                        onStep = { step -> liveTrace.add(step) },
                    )
                    awaitingToolReply = turn.awaitingReply
                    messages.add(
                        ChatMsg(
                            turn.answer, fromUser = false, showAvatar = true,
                            stat = InferenceStats.last, trace = turn.trace,
                        )
                    )
                    liveTrace.clear()
                    state = if (speaker.say(turn.answer)) CoachState.Speaking else CoachState.Idle
                } else {
                    val phrased = Ml.llm.respond(history, ctx)
                    messages.add(
                        ChatMsg(
                            phrased, fromUser = false, showAvatar = true,
                            stat = InferenceStats.last,
                        )
                    )
                    state = if (speaker.say(phrased)) CoachState.Speaking else CoachState.Idle
                }
            } catch (e: Exception) {
                messages.add(
                    ChatMsg(
                        "\u26a0 ${e.message ?: "The on-device model didn't respond."}",
                        fromUser = false, showAvatar = true,
                    )
                )
                liveTrace.clear()
                state = CoachState.Idle
            }
        }
    }

    // ---- LIVE listening: start real recognition ----
    fun beginListening() {
        if (!voice.available) {
            // No speech service on this device: report it honestly, never fake input.
            Toast.makeText(context, "Live speech unavailable on this device \u2014 type instead", Toast.LENGTH_SHORT).show()
            return
        }
        state = CoachState.Listening
        liveDraft = ""
        voice.start(object : VoiceInput.Listener {
            override fun onPartial(text: String) {
                liveDraft = text
            }
            override fun onFinal(text: String) {
                liveDraft = null
                ask(text)
            }
            override fun onError(message: String) {
                liveDraft = null
                state = CoachState.Idle
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        })
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginListening()
        else Toast.makeText(context, "Microphone permission needed to talk", Toast.LENGTH_SHORT).show()
    }

    /** Explicit playback controls, shown only while there's speech to control. */
    fun togglePause() {
        when (state) {
            CoachState.Speaking -> { speaker.pause(); state = CoachState.Paused }
            CoachState.Paused ->
                state = if (speaker.resume()) CoachState.Speaking else CoachState.Idle
            else -> Unit
        }
    }

    fun stopSpeaking() {
        speaker.stop()
        state = CoachState.Idle
    }

    fun startListening() {
        val has = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (has) beginListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Opened hands-free (tile / assist / shortcut): greet and start listening
    // once, as soon as the screen is ready. Runs only on the very first
    // composition so returning to the screen doesn't re-trigger it.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (autoStartListening && !autoStarted) {
            autoStarted = true
            delay(250) // let the screen settle before grabbing the mic
            startListening()
        }
    }

    fun tapMic() {
        when (state) {
            CoachState.Listening -> voice.stop()   // tap again = stop & finalize
            // Barging in: talking over a spoken answer cuts it off and starts
            // listening in one tap, rather than being silently ignored.
            CoachState.Speaking, CoachState.Paused -> {
                speaker.stop()
                startListening()
            }
            CoachState.Idle -> startListening()
            CoachState.Thinking -> Unit // the model is mid-answer; nothing to interrupt yet
        }
    }

    Column(Modifier.fillMaxSize().background(Ar.Cream)) {

        // ---- Header ----
        Surface(color = Ar.CardBg, shadowElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ar.Navy)
                }
                Image(
                    painter = painterResource(com.arokya.app.R.drawable.arokya_icon),
                    contentDescription = "Arokya",
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Arokya", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ar.Navy)
                    Text(
                        when (state) {
                            CoachState.Idle -> "On-device"
                            CoachState.Listening -> "Listening\u2026 tap the mic to stop"
                            CoachState.Thinking -> "Thinking\u2026"
                            CoachState.Speaking -> "Speaking\u2026"
                            CoachState.Paused -> "Paused"
                        },
                        fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Ar.Teal
                    )
                }
            }
        }

        // ---- Conversation ----
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages) { m ->
                if (m.fromUser) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Bubble(m.text, bg = Color(0xFFEFEFF2), italic = false)
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        if (m.showAvatar) {
                            Image(
                                painter = painterResource(com.arokya.app.R.drawable.arokya_icon),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp).clip(CircleShape),
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(38.dp))
                        }
                        Column {
                            // The reasoning behind this answer — hidden by default,
                            // like Claude's collapsed thinking. A quiet line, not a
                            // chat bubble.
                            if (m.trace.isNotEmpty()) {
                                ThoughtDisclosure(m.trace)
                                Spacer(Modifier.height(6.dp))
                            }
                            Bubble(m.text, bg = Ar.TealChipBg, italic = true)
                            if (m.stat != null) {
                                Spacer(Modifier.height(8.dp))
                                InferenceChip(m.stat)
                            }
                        }
                    }
                }
            }

            // ---- Live draft: your words appearing as you speak ----
            if (liveDraft != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Bubble(
                            text = if (liveDraft!!.isBlank()) "Listening\u2026"
                            else liveDraft!! + " \u258f",
                            bg = Color(0xFFEFEFF2).copy(alpha = 0.7f),
                            italic = true
                        )
                    }
                }
            }

            // ---- The model is working: a live bubble instead of dead air ----
            if (state == CoachState.Thinking) {
                // While tools are running, the thinking bubble shows the CURRENT
                // step as a quiet one-liner (Claude-style), not a stack of bubbles.
                item { ThinkingBubble(liveTrace.lastOrNull()) }
            }
        }

        // ---- Playback controls: only while there's speech to control ----
        if (state == CoachState.Speaking || state == CoachState.Paused) {
            Surface(color = Ar.TealChipBg) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.VolumeUp, null, tint = Ar.Teal,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state == CoachState.Speaking) "Arokya is speaking" else "Paused",
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ar.Navy,
                        modifier = Modifier.weight(1f),
                    )
                    PlaybackChip(
                        icon = if (state == CoachState.Speaking) Icons.Filled.Pause
                        else Icons.Filled.PlayArrow,
                        label = if (state == CoachState.Speaking) "Pause" else "Resume",
                        onClick = { togglePause() },
                    )
                    Spacer(Modifier.width(8.dp))
                    PlaybackChip(
                        icon = Icons.Filled.Stop,
                        label = "Stop",
                        onClick = { stopSpeaking() },
                    )
                }
            }
        }

        // ---- Input row ----
        Surface(color = Ar.CardBg, shadowElevation = 6.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type instead", color = Ar.Muted, fontSize = 14.sp) },
                    shape = RoundedCornerShape(999.dp),
                    singleLine = true,
                    enabled = state != CoachState.Listening,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFF2F2F4),
                        unfocusedContainerColor = Color(0xFFF2F2F4),
                        disabledContainerColor = Color(0xFFF2F2F4),
                        focusedBorderColor = Ar.Teal.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(10.dp))

                // MIC and SEND are separate controls on purpose. They used to
                // share one button whose meaning flipped based on whether the
                // text field happened to be empty — so tapping the mic with a
                // half-typed message sent it instead of listening, and there
                // was no way to stop Arokya talking.
                val micBusy = state == CoachState.Thinking
                Box(
                    Modifier
                        .size(52.dp)
                        .background(
                            when {
                                state == CoachState.Listening -> Ar.Orange
                                state == CoachState.Speaking || state == CoachState.Paused -> Ar.Purple
                                micBusy -> Ar.Teal.copy(alpha = 0.35f)
                                else -> Ar.Teal
                            },
                            CircleShape
                        )
                        .clickable(enabled = !micBusy) { tapMic() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        when (state) {
                            CoachState.Listening -> Icons.Filled.Stop
                            else -> Icons.Filled.Mic
                        },
                        contentDescription = when (state) {
                            CoachState.Listening -> "Stop listening"
                            CoachState.Speaking, CoachState.Paused -> "Interrupt and speak"
                            else -> "Speak"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                val canSend = typed.isNotBlank() && state != CoachState.Thinking &&
                        state != CoachState.Listening
                Box(
                    Modifier
                        .size(52.dp)
                        .background(if (canSend) Ar.Navy else Ar.Muted.copy(alpha = 0.35f), CircleShape)
                        .clickable(enabled = canSend) {
                            val q = typed
                            typed = ""
                            ask(q)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, bg: Color, italic: Boolean) {
    Text(
        text,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        color = Ar.Navy,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        modifier = Modifier
            .widthIn(max = 292.dp)
            .background(bg, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/**
 * Shown while [Ml.llm] is generating. The three pulsing dots are a real
 * "still working" signal; the rotating caption underneath is cosmetic pacing
 * (a single model call reports no sub-progress), so it never claims a step
 * actually finished.
 */
@Composable
private fun ThinkingBubble(currentStep: TraceStep? = null) {
    var phraseIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1800)
            phraseIndex = (phraseIndex + 1) % THINKING_PHRASES.size
        }
    }

    // When a tool step is live, show THAT (it's real); otherwise the cosmetic
    // rotating phrase for a plain single-shot answer.
    val caption = currentStep?.let { stepCaption(it) } ?: THINKING_PHRASES[phraseIndex]

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Image(
            painter = painterResource(com.arokya.app.R.drawable.arokya_icon),
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Row(
                Modifier
                    .background(Ar.TealChipBg, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { i ->
                    TypingDot(index = i)
                    if (i != 2) Spacer(Modifier.width(5.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                caption,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                color = Ar.Muted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).widthIn(max = 240.dp),
            )
        }
    }
}

/** A live tool step, phrased as a short present-tense status line. */
private fun stepCaption(step: TraceStep): String = when (step.kind) {
    TraceStep.Kind.Thinking -> "Thinking…"
    TraceStep.Kind.ToolCall -> when {
        step.text.startsWith("get_health_data") -> "Reading your health data…"
        step.text.startsWith("get_datetime") -> "Checking the date & time…"
        else -> "Setting your reminder…"
    }
    TraceStep.Kind.ToolResult -> "Reading the result…"
    TraceStep.Kind.Reminder -> "Reminder scheduled…"
}

/** One dot of the three-dot typing indicator, staggered by [index]. */
@Composable
private fun TypingDot(index: Int) {
    val transition = rememberInfiniteTransition(label = "typing$index")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = index * 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha$index",
    )
    Box(
        Modifier
            .size(7.dp)
            .background(Ar.Teal.copy(alpha = alpha), CircleShape)
    )
}
/** One control in the speech playback bar — pause/resume or stop. */
@Composable
private fun PlaybackChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .background(Ar.CardBg, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = Ar.Teal, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ar.Teal)
    }
}

/**
 * Hidden-by-default reasoning, in the style of Claude's collapsed thinking: a
 * quiet muted line ("Thought for a moment · N steps") that expands to the real
 * trace on tap. Deliberately NOT a chat bubble — it sits above the answer as an
 * unobtrusive disclosure.
 */
@Composable
private fun ThoughtDisclosure(steps: List<TraceStep>) {
    var expanded by remember { mutableStateOf(false) }
    val setReminder = steps.any { it.kind == TraceStep.Kind.Reminder }

    Column(Modifier.widthIn(max = 292.dp)) {
        Row(
            Modifier.clickable { expanded = !expanded }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (setReminder) "Thought · reminder set" else "Thought for a moment",
                fontSize = 11.sp, fontStyle = FontStyle.Italic, color = Ar.Muted,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (expanded) "▾" else "▸",
                fontSize = 10.sp, color = Ar.Muted,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .background(Color(0xFFF2F2F4), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                steps.forEach { TraceRow(it) }
            }
        }
    }
}

@Composable
private fun TraceRow(step: TraceStep) {
    val (label, color) = when (step.kind) {
        TraceStep.Kind.Thinking -> "thinking" to Ar.Muted
        TraceStep.Kind.ToolCall -> "tool call" to Ar.Blue
        TraceStep.Kind.ToolResult -> "result" to Ar.Teal
        TraceStep.Kind.Reminder -> "reminder set" to Ar.Orange
    }
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label.uppercase(),
            fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = color,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .padding(top = 1.dp)
                .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp)
                .widthIn(min = 62.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(step.text, fontSize = 11.5.sp, color = Ar.Navy, lineHeight = 16.sp)
    }
}
