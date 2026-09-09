package com.macky.client.ui
 
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.macky.client.MainViewModel
import com.macky.client.ui.theme.*
import com.macky.client.webrtc.ConnectionQuality
import com.macky.client.webrtc.PeerState
import com.macky.client.webrtc.SignalingState
import com.macky.client.webrtc.models.InputEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.webrtc.SurfaceViewRenderer

@Composable
fun ConnectionQualityBars(quality: ConnectionQuality) {
    val barColor = when (quality) {
        ConnectionQuality.GOOD -> SuccessGreen
        ConnectionQuality.FAIR -> WarningYellow
        ConnectionQuality.POOR -> DangerRed
        ConnectionQuality.UNKNOWN -> TextSecondary
    }
    val activeBars = when (quality) {
        ConnectionQuality.GOOD -> 3
        ConnectionQuality.FAIR -> 2
        ConnectionQuality.POOR -> 1
        ConnectionQuality.UNKNOWN -> 1
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (activeBars >= 1) barColor else BorderColor)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (activeBars >= 2) barColor else BorderColor)
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(12.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (activeBars >= 3) barColor else BorderColor)
        )
    }
}

@Composable
fun ModifierKeyButton(
    label: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (isActive) AccentCyan.copy(alpha = 0.20f) else CardBgElevated,
        border = BorderStroke(1.dp, if (isActive) AccentCyan else BorderColor),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isActive) AccentCyan else TextPrimary,
                fontSize = 11.sp,
                fontFamily = MonoFont,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun KeyButton(
    label: String,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (isHighlighted) AccentCyan.copy(alpha = 0.15f) else DarkBg,
        border = BorderStroke(1.dp, if (isHighlighted) AccentCyan.copy(alpha = 0.5f) else BorderColor),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (isHighlighted) AccentCyan else TextPrimary,
                fontSize = 11.sp,
                fontFamily = MonoFont,
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun ShortcutChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = DarkBg,
        border = BorderStroke(1.dp, BorderColor),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 10.sp,
                fontFamily = MonoFont,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun ArrowKeyPad(
    modifier: Modifier = Modifier,
    onArrowClick: (Int) -> Unit
) {
    // 123: Left, 124: Right, 125: Down, 126: Up
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
    ) {
        Surface(
            onClick = { onArrowClick(123) },
            shape = RoundedCornerShape(4.dp),
            color = DarkBg,
            border = BorderStroke(1.dp, BorderColor),
            modifier = Modifier.size(width = 30.dp, height = 36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("◀", color = TextPrimary, fontSize = 11.sp)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Surface(
                onClick = { onArrowClick(126) },
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                color = DarkBg,
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.size(width = 32.dp, height = 17.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("▲", color = TextPrimary, fontSize = 9.sp)
                }
            }
            Surface(
                onClick = { onArrowClick(125) },
                shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp),
                color = DarkBg,
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.size(width = 32.dp, height = 17.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("▼", color = TextPrimary, fontSize = 9.sp)
                }
            }
        }

        Surface(
            onClick = { onArrowClick(124) },
            shape = RoundedCornerShape(4.dp),
            color = DarkBg,
            border = BorderStroke(1.dp, BorderColor),
            modifier = Modifier.size(width = 30.dp, height = 36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("▶", color = TextPrimary, fontSize = 11.sp)
            }
        }
    }
}



private enum class TwoFingerMode {
    UNDECIDED,
    ZOOM,
    PAN_LOCAL,
    SCROLL_MAC
}

/**
 * Maps a touch coordinate from the un-transformed video bounds [viewWidth, viewHeight]
 * back to the normalized [0.0, 1.0] coordinate space on the Mac desktop,
 * accounting for local viewport scale and pan offsets.
 */
fun mapTouchToNormalized(
    touchPos: Offset,
    viewWidth: Float,
    viewHeight: Float,
    scale: Float,
    pan: Offset
): Pair<Float, Float> {
    if (viewWidth <= 0f || viewHeight <= 0f) return Pair(0.5f, 0.5f)
    val s = if (scale > 0f) scale else 1f
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    val unprojectedX = centerX + (touchPos.x - centerX - pan.x) / s
    val unprojectedY = centerY + (touchPos.y - centerY - pan.y) / s
    val normX = (unprojectedX / viewWidth).coerceIn(0f, 1f)
    val normY = (unprojectedY / viewHeight).coerceIn(0f, 1f)
    return Pair(normX, normY)
}

@Composable
fun StreamScreen(
    viewModel: MainViewModel,
    roomCode: String,
    onDisconnect: () -> Unit
) {
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Enforce screen stays awake while viewing live stream
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    val context = LocalContext.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val isLowBandwidthMode by viewModel.isLowBandwidthMode.collectAsState()
    val fileTransferState by viewModel.fileTransferState.collectAsState()

    // File picker launcher for sending files to Mac
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.sendFile(it)
        }
    }

    // Clipboard sync listener: receive text from Mac and copy to local clipboard
    LaunchedEffect(Unit) {
        viewModel.clipboardReceived.collect { text ->
            val clip = ClipData.newPlainText("Acuity", text)
            clipboardManager.setPrimaryClip(clip)
            val preview = if (text.length > 24) text.take(24) + "…" else text
            Toast.makeText(context, "Clipboard from Mac: \"$preview\"", Toast.LENGTH_SHORT).show()
        }
    }

    val signalingState by viewModel.signalingState.collectAsState()
    val peerState by viewModel.peerState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val isInputReady by viewModel.isInputReady.collectAsState()
    val isWaitingForApproval by viewModel.isWaitingForApproval.collectAsState()
    val reconnectAttemptCount by viewModel.reconnectAttemptCount.collectAsState()

    var showControls by remember { mutableStateOf(true) }
    var controlsInteractionKey by remember { mutableLongStateOf(0L) }


    // Auto-hide controls after 3 seconds of inactivity
    LaunchedEffect(showControls, controlsInteractionKey) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

    // Zoom Lock State: When true (default), viewport zoom is locked and 2-finger gestures are 100% Mac scroll
    var isZoomLocked by remember { mutableStateOf(true) }
    val currentIsZoomLocked by rememberUpdatedState(isZoomLocked)

    // Part E: "Scroll here" lock at CURRENT zoom level (>1.05x)
    var isZoomedScrollLocked by remember { mutableStateOf(false) }
    val currentIsZoomedScrollLocked by rememberUpdatedState(isZoomedScrollLocked)

    // Trackpad sensitivity & stats overlay state
    var trackpadSensitivity by remember { mutableFloatStateOf(1.0f) }
    val currentTrackpadSensitivity by rememberUpdatedState(trackpadSensitivity)
    var showSensitivityDialog by remember { mutableStateOf(false) }
    var showStatsOverlay by remember { mutableStateOf(false) }

    // Local Viewport Transform: Pinch-to-Zoom (1x to 4x) & Pan
    val animScale = remember { Animatable(1f) }
    val animPanX = remember { Animatable(0f) }
    val animPanY = remember { Animatable(0f) }

    // Direct 1x Reset and Lock: Automatically glides back to 100% full view (1x centered) on locking
    val toggleZoomLock = {
        isZoomedScrollLocked = false
        if (!isZoomLocked || animScale.value > 1.05f) {
            isZoomLocked = true
            coroutineScope.launch {
                launch { animScale.animateTo(1f, tween(250, easing = FastOutSlowInEasing)) }
                launch { animPanX.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                launch { animPanY.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
            }
        } else {
            isZoomLocked = false
        }
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    // Double-tap tracking
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    // Keyboard Input State
    var showKeyboardDialog by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var isCmdActive by remember { mutableStateOf(false) }
    var isOptActive by remember { mutableStateOf(false) }
    var isCtrlActive by remember { mutableStateOf(false) }
    var isShiftActive by remember { mutableStateOf(false) }

    fun getActiveModifiers(): List<String>? {
        val list = mutableListOf<String>()
        if (isCmdActive) list.add("cmd")
        if (isOptActive) list.add("opt")
        if (isCtrlActive) list.add("ctrl")
        if (isShiftActive) list.add("shift")
        return if (list.isEmpty()) null else list
    }

    fun sendKey(code: Int) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val mods = getActiveModifiers()
        viewModel.sendInputEvent(InputEvent.keyCode(code, mods))
        isCmdActive = false
        isOptActive = false
        isCtrlActive = false
        isShiftActive = false
    }

    fun sendShortcut(code: Int, mods: List<String>) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        viewModel.sendInputEvent(InputEvent.keyCode(code, mods))
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
                if (showControls) {
                    controlsInteractionKey++
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        // Compute aspect ratio of the incoming desktop video stream
        val videoAspect = if (stats.width > 0 && stats.height > 0) {
            stats.width.toFloat() / stats.height.toFloat()
        } else {
            1470f / 956f // Default 16:10 MacBook ratio
        }

        val containerAspect = containerWidth.value / containerHeight.value

        // BASE STATE: Always 100% aspect-fit with zero source crop (letterboxed / pillarboxed)
        val (rendererWidth, rendererHeight) = if (containerAspect > videoAspect) {
            // Landscape: fit height, pillarbox width
            val h = containerHeight
            val w = (containerHeight.value * videoAspect).dp
            Pair(w, h)
        } else {
            // Portrait: fit width, letterbox height
            val w = containerWidth
            val h = (containerWidth.value / videoAspect).dp
            Pair(w, h)
        }

        // WebRTC Video Frame with Local Zoom Transform & Gesture Capture
        Box(
            modifier = Modifier
                .size(rendererWidth, rendererHeight)
                .clipToBounds(), // Clamps zoomed content inside the video display area
            contentAlignment = Alignment.Center
        ) {
            // Layer A: Visual Video Layer (scaled and panned via graphicsLayer)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = animScale.value
                        scaleY = animScale.value
                        translationX = animPanX.value
                        translationY = animPanY.value
                    }
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            viewModel.webRtcClient.attachRenderer(this)
                        }
                    },
                    update = { renderer ->
                        renderer.requestLayout()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Layer B: Gesture Capture Overlay (Raw un-transformed coordinates)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isInputReady) {
                        if (!isInputReady) return@pointerInput

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPos = down.position
                            val startTime = System.currentTimeMillis()
                            val viewWidth = size.width.toFloat()
                            val viewHeight = size.height.toFloat()

                            var isDragging = false
                            var isLongPressed = false
                            var isTwoFinger = false
                            var lastMoveTime = startTime

                            val longPressTimeoutMs = 650L

                            // Phase 1: Wait up to 650ms for:
                            // (a) Hold threshold -> Right Click (Secondary Click) with haptic buzz
                            // (b) Single lift -> Left Click (Tap)
                            // (c) Touch slop exceeded (> 8dp) -> Cancel hold timer & initiate Drag Start
                            // (d) Second finger down -> Cancel hold timer & switch to Two-Finger mode
                            val timedOut = try {
                                withTimeout(longPressTimeoutMs) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pointers = event.changes

                                        if (pointers.size >= 2) {
                                            isTwoFinger = true
                                            // Second finger down cancels any pending long-press
                                            return@withTimeout false
                                        }

                                        val change = pointers[0]
                                        if (!change.pressed) {
                                            // Finger released before 650ms: cancel long-press
                                            return@withTimeout false
                                        }

                                        val dist = (change.position - startPos).getDistance()
                                        if (dist > 8.dp.toPx()) {
                                            // Movement exceeded 8dp touch slop:
                                            // CANCEL long-press timer immediately and enter drag mode!
                                            isDragging = true
                                            val (nx, ny) = mapTouchToNormalized(
                                                startPos,
                                                viewWidth,
                                                viewHeight,
                                                animScale.value,
                                                Offset(animPanX.value, animPanY.value)
                                            )
                                            viewModel.sendInputEvent(InputEvent.dragStart(nx, ny))
                                            lastMoveTime = System.currentTimeMillis()
                                            change.consume()
                                            return@withTimeout false
                                        }
                                    }
                                    false
                                }
                            } catch (e: Exception) {
                                // 650ms hold threshold reached without moving beyond 8dp:
                                true
                            }

                            // Trigger Right-Click ONLY if 650ms timer actually expired
                            // AND movement never crossed the 8dp drag slop AND never used 2 fingers
                            if (timedOut && !isDragging && !isTwoFinger) {
                                isLongPressed = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val (nx, ny) = mapTouchToNormalized(
                                    startPos,
                                    viewWidth,
                                    viewHeight,
                                    animScale.value,
                                    Offset(animPanX.value, animPanY.value)
                                )
                                viewModel.sendInputEvent(InputEvent.rightTap(nx, ny))
                            }

                            // Phase 2: Two-Finger Gestures (Pinch-to-Zoom, Local Pan, or Mac Scroll)
                            if (isTwoFinger) {
                                val currentEvent = currentEvent
                                val initialPointers = currentEvent.changes
                                var p1Start = initialPointers.getOrNull(0)?.position ?: startPos
                                var p2Start = initialPointers.getOrNull(1)?.position ?: startPos
                                var initialDist = (p1Start - p2Start).getDistance()
                                val initialScale = animScale.value
                                val initialPan = Offset(animPanX.value, animPanY.value)

                                var twoFingerMode = TwoFingerMode.UNDECIDED

                                do {
                                    val event = awaitPointerEvent()
                                    val currentPointers = event.changes
                                    if (currentPointers.size >= 2) {
                                        val p1 = currentPointers[0]
                                        val p2 = currentPointers[1]

                                        // If initial distance was near zero (fingers landed asynchronously), re-sample base distance
                                        if (initialDist < 10f) {
                                            p1Start = p1.position
                                            p2Start = p2.position
                                            initialDist = (p1Start - p2Start).getDistance()
                                        }

                                        val currentDistance = (p1.position - p2.position).getDistance()
                                        val scaleFactor = if (initialDist > 20f) currentDistance / initialDist else 1f
                                        val scaleDelta = kotlin.math.abs(scaleFactor - 1f)
                                        val distanceChange = kotlin.math.abs(currentDistance - initialDist)

                                        val cumPan = ((p1.position - p1Start) + (p2.position - p2Start)) / 2f
                                        val cumPanDist = cumPan.getDistance()
                                        val perFramePan = (p1.positionChange() + p2.positionChange()) / 2f

                                        if (twoFingerMode == TwoFingerMode.UNDECIDED) {
                                            if (currentIsZoomLocked || currentIsZoomedScrollLocked) {
                                                // ZOOM IS LOCKED or SCROLL HERE IS ACTIVE:
                                                // Two-finger gestures strictly scroll Mac!
                                                // Zero risk of zoom or viewport sliding
                                                if (cumPanDist > 3.dp.toPx() || perFramePan.getDistance() > 1.dp.toPx()) {
                                                    twoFingerMode = TwoFingerMode.SCROLL_MAC
                                                }
                                            } else {
                                                // ZOOM IS UNLOCKED (Interactive Viewport Mode):
                                                // A true PINCH requires intentional finger separation change (> 8%)
                                                // that dominates the cumulative pan movement.
                                                if (scaleDelta > 0.08f && distanceChange > cumPanDist * 0.7f) {
                                                    twoFingerMode = TwoFingerMode.ZOOM
                                                } else if (cumPanDist > 6.dp.toPx()) {
                                                    if (animScale.value > 1.05f) {
                                                        twoFingerMode = TwoFingerMode.PAN_LOCAL
                                                    } else {
                                                        twoFingerMode = TwoFingerMode.SCROLL_MAC
                                                    }
                                                }
                                            }
                                        }

                                        when (twoFingerMode) {
                                            TwoFingerMode.ZOOM -> {
                                                val targetScale = (initialScale * scaleFactor).coerceIn(1f, 4f)
                                                val maxPanX = (viewWidth * (targetScale - 1f)) / 2f
                                                val maxPanY = (viewHeight * (targetScale - 1f)) / 2f
                                                val newPanX = (initialPan.x + (p1.position.x + p2.position.x) / 2f - (p1Start.x + p2Start.x) / 2f)
                                                    .coerceIn(-maxPanX, maxPanX)
                                                val newPanY = (initialPan.y + (p1.position.y + p2.position.y) / 2f - (p1Start.y + p2Start.y) / 2f)
                                                    .coerceIn(-maxPanY, maxPanY)

                                                coroutineScope.launch {
                                                    animScale.snapTo(targetScale)
                                                    animPanX.snapTo(newPanX)
                                                    animPanY.snapTo(newPanY)
                                                }
                                                p1.consume()
                                                p2.consume()
                                            }

                                            TwoFingerMode.PAN_LOCAL -> {
                                                val currentScale = animScale.value
                                                val maxPanX = (viewWidth * (currentScale - 1f)) / 2f
                                                val maxPanY = (viewHeight * (currentScale - 1f)) / 2f
                                                val newPanX = (animPanX.value + perFramePan.x).coerceIn(-maxPanX, maxPanX)
                                                val newPanY = (animPanY.value + perFramePan.y).coerceIn(-maxPanY, maxPanY)

                                                coroutineScope.launch {
                                                    animPanX.snapTo(newPanX)
                                                    animPanY.snapTo(newPanY)
                                                }
                                                p1.consume()
                                                p2.consume()
                                            }

                                            TwoFingerMode.SCROLL_MAC -> {
                                                if (kotlin.math.abs(perFramePan.x) > 0.3f || kotlin.math.abs(perFramePan.y) > 0.3f) {
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastMoveTime >= 16) { // ~60 Hz throttle
                                                        val (nx, ny) = mapTouchToNormalized(
                                                            p1.position,
                                                            viewWidth,
                                                            viewHeight,
                                                            animScale.value,
                                                            Offset(animPanX.value, animPanY.value)
                                                        )
                                                        viewModel.sendInputEvent(InputEvent.scroll(nx, ny, perFramePan.x * 1.5f * currentTrackpadSensitivity, perFramePan.y * 1.5f * currentTrackpadSensitivity))
                                                        lastMoveTime = now
                                                    }
                                                    p1.consume()
                                                    p2.consume()
                                                }
                                            }

                                            TwoFingerMode.UNDECIDED -> {
                                                // Waiting for gesture to cross thresholds
                                            }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })

                                // On pinch release: if pinched back below 1.08x, snap smoothly to 1x centered
                                if (animScale.value < 1.08f && animScale.value > 0.95f) {
                                    isZoomedScrollLocked = false
                                    coroutineScope.launch {
                                        launch { animScale.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
                                        launch { animPanX.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
                                        launch { animPanY.animateTo(0f, tween(200, easing = FastOutSlowInEasing)) }
                                    }
                                }
                            } else if (isDragging) {
                                // Phase 3: Single-Finger Drag continuation
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (change.pressed) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastMoveTime >= 16) { // ~60 Hz throttle
                                            val (nx, ny) = mapTouchToNormalized(
                                                change.position,
                                                viewWidth,
                                                viewHeight,
                                                animScale.value,
                                                Offset(animPanX.value, animPanY.value)
                                            )
                                            viewModel.sendInputEvent(InputEvent.dragMove(nx, ny))
                                            lastMoveTime = now
                                        }
                                        change.consume()
                                    } else {
                                        val (nx, ny) = mapTouchToNormalized(
                                            change.position,
                                            viewWidth,
                                            viewHeight,
                                            animScale.value,
                                            Offset(animPanX.value, animPanY.value)
                                        )
                                        viewModel.sendInputEvent(InputEvent.dragEnd(nx, ny))
                                        isDragging = false
                                        change.consume()
                                    }
                                } while (event.changes.any { it.pressed })
                            } else if (isLongPressed) {
                                // Phase 4: Right-click was triggered by hold; consume remaining events until lift
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            } else {
                                // Phase 5: Finger lifted before longPressTimeoutMs without dragging
                                val elapsed = System.currentTimeMillis() - startTime
                                if (elapsed < longPressTimeoutMs) {
                                    val now = System.currentTimeMillis()
                                    val isDoubleTap = now - lastTapTime < 300L && (startPos - lastTapPos).getDistance() < 35.dp.toPx()

                                    if (isDoubleTap && animScale.value > 1.05f) {
                                        // Zoomed in: Double-tap ALWAYS resets smoothly to 1x centered and locks scroll!
                                        lastTapTime = 0L
                                        isZoomLocked = true
                                        coroutineScope.launch {
                                            launch { animScale.animateTo(1f, tween(250, easing = FastOutSlowInEasing)) }
                                            launch { animPanX.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                                            launch { animPanY.animateTo(0f, tween(250, easing = FastOutSlowInEasing)) }
                                        }
                                    } else if (isDoubleTap && !currentIsZoomLocked) {
                                        // At 1x in Zoom Mode: Double-tap zooms in to 2.2x centered on tapped point
                                        lastTapTime = 0L
                                        val targetScale = 2.2f
                                        val maxPanX = (viewWidth * (targetScale - 1f)) / 2f
                                        val maxPanY = (viewHeight * (targetScale - 1f)) / 2f
                                        val targetPanX = ((viewWidth / 2f - startPos.x) * (targetScale - 1f)).coerceIn(-maxPanX, maxPanX)
                                        val targetPanY = ((viewHeight / 2f - startPos.y) * (targetScale - 1f)).coerceIn(-maxPanY, maxPanY)
                                        coroutineScope.launch {
                                            launch { animScale.animateTo(targetScale, tween(250, easing = FastOutSlowInEasing)) }
                                            launch { animPanX.animateTo(targetPanX, tween(250, easing = FastOutSlowInEasing)) }
                                            launch { animPanY.animateTo(targetPanY, tween(250, easing = FastOutSlowInEasing)) }
                                        }
                                    } else {
                                        // Single tap (or native Mac double-click when at 1x in Scroll Lock)
                                        lastTapTime = now
                                        lastTapPos = startPos
                                        val (nx, ny) = mapTouchToNormalized(
                                            startPos,
                                            viewWidth,
                                            viewHeight,
                                            animScale.value,
                                            Offset(animPanX.value, animPanY.value)
                                        )
                                        viewModel.sendInputEvent(InputEvent.tap(nx, ny))
                                    }
                                }
                            }
                        }
                    }
            )
        }

        // Overlay message when waiting for stream
        if (peerState != PeerState.CONNECTED && !isWaitingForApproval) {
            Card(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = AccentBlue,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = when {
                            signalingState == SignalingState.CONNECTING -> "Connecting to Signaling Server..."
                            signalingState == SignalingState.CONNECTED && peerState == PeerState.CONNECTING -> "Negotiating WebRTC with Mac..."
                            peerState == PeerState.FAILED -> "WebRTC Connection Failed"
                            else -> "Waiting for Host Stream..."
                        },
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ROOM: $roomCode",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = MonoFont
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, BorderColor),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Cancel Connection", fontSize = 12.sp)
                    }
                }
            }
        }

        // Part D: Host Approval Security Gate Overlay
        if (isWaitingForApproval) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, BorderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(AccentBlue.copy(alpha = 0.12f))
                                .border(1.dp, AccentBlue.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = "Security Approval",
                                tint = AccentBlue,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Awaiting Mac Authorization",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "A security authorization prompt appeared on your Mac.\nAuthorize this connection on your Mac to start streaming.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Technical Metadata Card
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkBg,
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("ROOM", color = TextSecondary, fontSize = 10.sp, fontFamily = MonoFont)
                                    Text(roomCode, color = AccentBlue, fontSize = 11.sp, fontFamily = MonoFont, fontWeight = FontWeight.Bold)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("DEVICE KEY", color = TextSecondary, fontSize = 10.sp, fontFamily = MonoFont)
                                    Text(
                                        viewModel.identityManager.deviceId.take(16) + "…",
                                        color = TextSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = MonoFont
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        CircularProgressIndicator(
                            color = AccentBlue,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        OutlinedButton(
                            onClick = onDisconnect,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Cancel Request", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Reconnecting Status Banner
        if (signalingState == SignalingState.RECONNECTING || reconnectAttemptCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(20.dp),
                color = CardBg,
                border = BorderStroke(1.dp, AccentBlue.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        color = AccentBlue,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reconnecting to Mac… (attempt #$reconnectAttemptCount)",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        fontFamily = MonoFont
                    )
                }
            }
        }

        // Top Status Bar: Floating semi-transparent dark status pill (auto-hides in 3s)
        AnimatedVisibility(
            visible = showControls && peerState == PeerState.CONNECTED,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 2 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Surface(
                onClick = {
                    showControls = !showControls
                    if (showControls) controlsInteractionKey++
                },
                shape = RoundedCornerShape(20.dp),
                color = CardBg.copy(alpha = 0.90f),
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusColor = when {
                        peerState == PeerState.CONNECTED -> SuccessGreen
                        signalingState == SignalingState.RECONNECTING -> WarningYellow
                        else -> DangerRed
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Text(
                        text = roomCode,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = MonoFont
                    )
                    Text("·", color = TextSecondary, fontSize = 12.sp)
                    ConnectionQualityBars(stats.quality)
                    Text(
                        text = "${stats.rttMs} ms",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = MonoFont
                    )
                    if (isLowBandwidthMode) {
                        Text("·", color = TextSecondary, fontSize = 12.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                "LOW-BW",
                                color = AccentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = MonoFont
                            )
                        }
                    }
                }
            }
        }

        // Floating File Transfer Progress Card
        AnimatedVisibility(
            visible = (fileTransferState.isActive || fileTransferState.isComplete || fileTransferState.error != null) && peerState == PeerState.CONNECTED,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp)
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg.copy(alpha = 0.95f)),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.widthIn(min = 260.dp, max = 340.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (fileTransferState.isSending) Icons.Outlined.Upload else Icons.Outlined.Download,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (fileTransferState.isSending) "SENDING TO MAC" else "RECEIVING FROM MAC",
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = MonoFont
                            )
                        }

                        if (fileTransferState.isActive) {
                            Text(
                                text = "Cancel",
                                color = DangerRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { viewModel.cancelFileTransfer() }
                            )
                        } else {
                            Text(
                                text = "Dismiss",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { viewModel.resetFileTransferState() }
                            )
                        }
                    }

                    Text(
                        text = fileTransferState.fileName,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )

                    if (fileTransferState.isActive) {
                        LinearProgressIndicator(
                            progress = { fileTransferState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = AccentCyan,
                            trackColor = BorderColor
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(fileTransferState.progress * 100).toInt()}%",
                                color = TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = MonoFont
                            )
                            if (fileTransferState.totalBytes > 0) {
                                val mb = fileTransferState.totalBytes / (1024f * 1024f)
                                Text(
                                    text = String.format("%.1f MB", mb),
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = MonoFont
                                )
                            }
                        }
                    } else if (fileTransferState.isComplete) {
                        Text(
                            text = if (fileTransferState.isSending) "Transfer finished!" else "Saved to ${fileTransferState.savedPath ?: "Downloads"}",
                            color = SuccessGreen,
                            fontSize = 11.sp,
                            fontFamily = MonoFont
                        )
                    } else if (fileTransferState.error != null) {
                        Text(
                            text = fileTransferState.error ?: "Transfer error",
                            color = DangerRed,
                            fontSize = 11.sp,
                            fontFamily = MonoFont
                        )
                    }
                }
            }
        }


        // Floating Stats Overlay (Toggled from Control Dock)
        AnimatedVisibility(
            visible = showStatsOverlay && showControls && !showKeyboardDialog && peerState == PeerState.CONNECTED,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        ) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CardBg.copy(alpha = 0.95f)),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.widthIn(min = 240.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "STREAM METRICS",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = MonoFont
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Resolution", color = TextSecondary, fontSize = 12.sp)
                        Text("${stats.width}x${stats.height}", color = TextPrimary, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Framerate", color = TextSecondary, fontSize = 12.sp)
                        Text("${stats.fps} FPS", color = TextPrimary, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Bitrate", color = TextSecondary, fontSize = 12.sp)
                        val bitrateStr = if (stats.bitrateKbps >= 1000) {
                            String.format("%.1f Mbps", stats.bitrateKbps / 1000f)
                        } else {
                            "${stats.bitrateKbps} kbps"
                        }
                        Text(bitrateStr, color = TextPrimary, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Latency (RTT)", color = TextSecondary, fontSize = 12.sp)
                        Text("${stats.rttMs} ms", color = TextPrimary, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Packets Lost", color = TextSecondary, fontSize = 12.sp)
                        Text("${stats.packetsLost}", color = if (stats.packetsLost > 0) WarningYellow else TextPrimary, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                }
            }
        }

        // Bottom Control Dock: Floating dark bar, minimum 44x44dp touch targets
        AnimatedVisibility(
            visible = showControls && !showKeyboardDialog && peerState == PeerState.CONNECTED,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CardBg,
                border = BorderStroke(1.dp, BorderColor)
            ) {
                Row(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { controlsInteractionKey++ }
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // --- Group 1: Core Navigation & Input ---
                    // 1. Keyboard Toggle (Min 44x44dp touch target)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBg)
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .clickable {
                                controlsInteractionKey++
                                showKeyboardDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Keyboard,
                            contentDescription = "Keyboard",
                            tint = AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 2. Display / Zoom Mode Toggle (Min 44x44dp touch target)
                    val isZoomed = animScale.value > 1.05f
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isZoomed) AccentCyan.copy(alpha = 0.15f) else DarkBg)
                            .border(1.dp, if (isZoomed) AccentCyan.copy(alpha = 0.5f) else BorderColor, RoundedCornerShape(8.dp))
                            .clickable {
                                controlsInteractionKey++
                                toggleZoomLock()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isZoomed) {
                            Text(
                                text = String.format("%.1fx", animScale.value),
                                color = AccentCyan,
                                fontSize = 12.sp,
                                fontFamily = MonoFont,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = if (isZoomLocked) Icons.Outlined.Lock else Icons.Outlined.ZoomIn,
                                contentDescription = "Display Mode",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 3. Trackpad Sensitivity Toggle (Min 44x44dp touch target)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBg)
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .clickable {
                                controlsInteractionKey++
                                showSensitivityDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Sensitivity",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Divider 1
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(BorderColor)
                    )

                    // --- Group 2: Performance & Stats ---
                    // 4. Low-Bandwidth Mode Toggle (Min 44x44dp touch target)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isLowBandwidthMode) AccentCyan.copy(alpha = 0.15f) else DarkBg)
                            .border(1.dp, if (isLowBandwidthMode) AccentCyan.copy(alpha = 0.5f) else BorderColor, RoundedCornerShape(8.dp))
                            .clickable {
                                controlsInteractionKey++
                                viewModel.toggleLowBandwidthMode()
                                val mode = if (!isLowBandwidthMode) "enabled (1.5 Mbps, 15 FPS)" else "disabled (Full Quality)"
                                Toast.makeText(context, "Low-bandwidth $mode", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NetworkCheck,
                            contentDescription = "Low-Bandwidth Mode",
                            tint = if (isLowBandwidthMode) AccentCyan else TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 5. Quality / Stats Toggle (Min 44x44dp touch target)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showStatsOverlay) AccentCyan.copy(alpha = 0.15f) else DarkBg)
                            .border(1.dp, if (showStatsOverlay) AccentCyan.copy(alpha = 0.5f) else BorderColor, RoundedCornerShape(8.dp))
                            .clickable {
                                controlsInteractionKey++
                                showStatsOverlay = !showStatsOverlay
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = "Stats",
                            tint = if (showStatsOverlay) AccentCyan else TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Divider 2
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(BorderColor)
                    )

                    // --- Group 3: Productivity Transfers ---
                    // 6. Send Clipboard to Mac (Min 44x44dp touch target)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBg)
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .clickable {
                                controlsInteractionKey++
                                val clipData = clipboardManager.primaryClip
                                val text = clipData?.getItemAt(0)?.text?.toString()
                                if (!text.isNullOrBlank()) {
                                    val sent = viewModel.sendClipboardText(text)
                                    if (sent) {
                                        Toast.makeText(context, "Clipboard sent to Mac", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Clipboard channel not ready", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Device clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPasteGo,
                            contentDescription = "Send Clipboard",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 7. Send File to Mac (Min 44x44dp touch target)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBg)
                            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                            .clickable {
                                controlsInteractionKey++
                                filePickerLauncher.launch("*/*")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FileUpload,
                            contentDescription = "Send File",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Divider 3
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(BorderColor)
                    )

                    // --- Group 4: Session Control ---
                    // 8. Disconnect Button (Min 44x44dp touch target, Red outline)
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBg)
                            .border(1.dp, DangerRed, RoundedCornerShape(8.dp))
                            .clickable { onDisconnect() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PowerSettingsNew,
                            contentDescription = "Disconnect",
                            tint = DangerRed,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

            }
        }

        // Bottom Mac Command Bar / Remote Keyboard Drawer
        AnimatedVisibility(
            visible = showKeyboardDialog && peerState == PeerState.CONNECTED,
            enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                color = CardBg.copy(alpha = 0.98f),
                border = BorderStroke(1.dp, BorderColor),
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* absorb taps so they do not fall through to the remote video track */ }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header row: drag bar / title / close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AccentCyan)
                            )
                            Text(
                                text = "MAC COMMAND & KEYBOARD",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = MonoFont,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // Close / Minimize button
                        Surface(
                            onClick = { showKeyboardDialog = false },
                            shape = CircleShape,
                            color = DarkBg,
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Close",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Real-time Text Input Field with direct Action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = keyboardText,
                            onValueChange = { keyboardText = it.take(500) },
                            placeholder = {
                                Text("Type text or paste URL to send...", color = TextSecondary, fontSize = 12.sp, fontFamily = MonoFont)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (keyboardText.isNotEmpty()) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val mods = getActiveModifiers()
                                        viewModel.sendInputEvent(InputEvent.keyText(keyboardText, mods))
                                        keyboardText = ""
                                        isCmdActive = false
                                        isOptActive = false
                                        isCtrlActive = false
                                        isShiftActive = false
                                    }
                                }
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = MonoFont,
                                fontSize = 13.sp,
                                color = Color.White
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = BorderColor,
                                focusedContainerColor = DarkBg,
                                unfocusedContainerColor = DarkBg,
                                cursorColor = AccentCyan
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        )

                        // Send button
                        Surface(
                            onClick = {
                                if (keyboardText.isNotEmpty()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val mods = getActiveModifiers()
                                    viewModel.sendInputEvent(InputEvent.keyText(keyboardText, mods))
                                    keyboardText = ""
                                    isCmdActive = false
                                    isOptActive = false
                                    isCtrlActive = false
                                    isShiftActive = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (keyboardText.isNotEmpty()) AccentCyan else DarkBg,
                            border = BorderStroke(1.dp, if (keyboardText.isNotEmpty()) AccentCyan else BorderColor),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Send ↵",
                                    color = if (keyboardText.isNotEmpty()) Color.Black else TextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = MonoFont,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Sticky Modifier Row: ⌘ CMD, ⌥ OPT, ⌃ CTRL, ⇧ SHIFT
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ModifierKeyButton(
                            label = "⌘ CMD",
                            isActive = isCmdActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isCmdActive = !isCmdActive
                        }
                        ModifierKeyButton(
                            label = "⌥ OPT",
                            isActive = isOptActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isOptActive = !isOptActive
                        }
                        ModifierKeyButton(
                            label = "⌃ CTRL",
                            isActive = isCtrlActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isCtrlActive = !isCtrlActive
                        }
                        ModifierKeyButton(
                            label = "⇧ SHIFT",
                            isActive = isShiftActive,
                            modifier = Modifier.weight(1f)
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            isShiftActive = !isShiftActive
                        }
                    }

                    // Quick macOS Function Keys Row: ESC, TAB, SPACE, DEL, RETURN
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeyButton(label = "ESC", modifier = Modifier.weight(1f)) { sendKey(53) }
                        KeyButton(label = "TAB ⇥", modifier = Modifier.weight(1.1f)) { sendKey(48) }
                        KeyButton(label = "SPACE", modifier = Modifier.weight(1.8f)) { sendKey(49) }
                        KeyButton(label = "⌫ DEL", modifier = Modifier.weight(1.2f)) { sendKey(51) }
                        KeyButton(label = "↵ RET", modifier = Modifier.weight(1.3f), isHighlighted = true) { sendKey(36) }
                    }

                    // macOS Shortcuts + Navigation row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Quick Action Chips scrollable row
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShortcutChip(label = "⌘ Space") { sendShortcut(49, listOf("cmd")) } // Spotlight
                            ShortcutChip(label = "⌘C") { sendShortcut(8, listOf("cmd")) } // Copy (macOS C = 8)
                            ShortcutChip(label = "⌘V") { sendShortcut(9, listOf("cmd")) } // Paste (macOS V = 9)
                            ShortcutChip(label = "⌘Z") { sendShortcut(6, listOf("cmd")) } // Undo (macOS Z = 6)
                            ShortcutChip(label = "⌘W") { sendShortcut(13, listOf("cmd")) } // Close Tab/Window (macOS W = 13)
                            ShortcutChip(label = "⌘Q") { sendShortcut(12, listOf("cmd")) } // Quit (macOS Q = 12)
                            ShortcutChip(label = "⌘Tab") { sendShortcut(48, listOf("cmd")) } // App Switcher
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Compact Arrow Keypad
                        ArrowKeyPad { code -> sendKey(code) }
                    }
                }
            }
        }

        // Trackpad Sensitivity Dialog Modal (High-End Technical Aesthetics)
        if (showSensitivityDialog) {
            AlertDialog(
                onDismissRequest = { showSensitivityDialog = false },
                containerColor = CardBg,
                shape = RoundedCornerShape(16.dp),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = AccentCyan.copy(alpha = 0.15f),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = AccentCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                "TRACKPAD SENSITIVITY",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = MonoFont,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                "Cursor acceleration multiplier",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Current multiplier display
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkBg,
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Current multiplier", color = TextSecondary, fontSize = 12.sp)
                                Text(
                                    "${String.format("%.2f", trackpadSensitivity)}x",
                                    color = AccentCyan,
                                    fontSize = 15.sp,
                                    fontFamily = MonoFont,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Presets
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(0.75f to "0.75x", 1.0f to "1.0x", 1.5f to "1.5x", 2.0f to "2.0x").forEach { (speed, label) ->
                                val isSelected = kotlin.math.abs(trackpadSensitivity - speed) < 0.05f
                                Surface(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        trackpadSensitivity = speed
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) AccentCyan.copy(alpha = 0.2f) else DarkBg,
                                    border = BorderStroke(1.dp, if (isSelected) AccentCyan else BorderColor),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) AccentCyan else TextPrimary,
                                            fontSize = 11.sp,
                                            fontFamily = MonoFont,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }

                        // Slider
                        Slider(
                            value = trackpadSensitivity,
                            onValueChange = { trackpadSensitivity = it },
                            valueRange = 0.5f..2.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = AccentCyan,
                                activeTrackColor = AccentCyan,
                                inactiveTrackColor = BorderColor
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSensitivityDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCyan,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Apply", fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = MonoFont)
                    }
                }
            )
        }
    }
}
