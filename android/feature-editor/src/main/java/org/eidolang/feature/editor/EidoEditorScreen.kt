package org.eidolang.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.canonical.EidogramParser
import org.eidolang.core.model.*
import org.eidolang.core.render.drawGlyph
import org.eidolang.feature.library.GlyphPalette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EidoEditorScreen(
    modifier: Modifier = Modifier,
    draftKey: String = "standalone",
    onSend: ((EidogramDocumentV1) -> Boolean)? = null,
    onClose: (() -> Unit)? = null,
    /** Label for the confirm action. The home flow saves to a catalogue rather than sending. */
    sendLabel: String = "Отправить",
    title: String? = null,
    /** File import/export belongs to the standalone editor, not to the composing flow. */
    showFileActions: Boolean = true,
    /** Rendered directly above the canvas, so the caption shares one screen with the drawing. */
    header: (@Composable () -> Unit)? = null,
    /**
     * What to open with when this key has no stored draft — editing or copying an existing
     * eidogram. A half-finished draft under the same key wins, so leaving mid-edit and coming back
     * does not silently discard the edits.
     */
    initialDocument: EidogramDocumentV1? = null,
    /** Gate on the confirm action, with the reason shown where the status line goes. */
    sendEnabled: Boolean = true,
    sendDisabledReason: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(draftKey) { mutableStateOf(EditorDraft()) }
    var loaded by remember(draftKey) { mutableStateOf(false) }
    LaunchedEffect(draftKey) {
        draft = DraftStore.loadInBackground(context, draftKey).getOrNull()
            ?: initialDocument?.let { EditorDraftFactory.fromDocument(it) }
            ?: EditorDraft()
        loaded = true
    }
    if (!loaded) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var multiSelect by remember { mutableStateOf(false) }
    // The glyph the next tap on empty canvas will place. Without it every figure landed in the
    // middle of the canvas and had to be dragged apart afterwards.
    // Empty on purpose: the editor opens in move-and-select, not in stamp-a-shape-wherever-you-
    // touch. It used to open with a red circle armed, so the first tap on the canvas — including a
    // tap meant to look at something — left a circle behind. You now say what you want to place
    // before it starts appearing.
    var pen by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var gestureBase by remember(draftKey) {
        mutableStateOf<Pair<List<EidogramAction>, List<List<EidogramAction>>>?>(null)
    }

    val snapshot = remember(draft.actions) { draft.snapshot }
    // Read by the long-lived gesture handler so it never has to be rebuilt on every edit.
    val liveSnapshot = rememberUpdatedState(snapshot)
    val liveSelection = rememberUpdatedState(draft.selectedIds)

    fun persistDraft(next: EditorDraft) {
        val pending = DraftStore.saveInBackground(context, next, draftKey)
        scope.launch {
            pending.await().onFailure {
                status = "Ошибка сохранения черновика: ${it.message}"
            }
        }
    }

    fun setDraft(next: EditorDraft) {
        val previous = draft
        draft = next
        // Selection is not part of the saved document. A moving pointer only updates memory;
        // serialising and encrypting the entire history on every event used to block the UI.
        if (gestureBase == null && previous.actions !== next.actions) {
            persistDraft(next)
        }
    }

    DisposableEffect(draftKey) {
        onDispose {
            if (gestureBase != null) DraftStore.saveInBackground(context, draft, draftKey)
        }
    }

    fun command(c: EditorCommand) = setDraft(EditorReducer.reduce(draft, c, recordUndo = gestureBase == null))

    val liveCommand = rememberUpdatedState<(EditorCommand) -> Unit> { c -> command(c) }
    val liveMulti = rememberUpdatedState(multiSelect)
    // Placing reads the current pen, which the palette changes underneath the gesture loop.
    val livePlace = rememberUpdatedState<(Int, Int) -> Unit> { x, y ->
        // No pen means the tap was not asking for a shape. Clearing the selection is what a tap on
        // empty canvas means everywhere else, and `AddGlyph("")` would fail `requireGlyph` anyway.
        if (pen.isEmpty()) command(EditorCommand.Select(emptySet()))
        else command(EditorCommand.AddGlyph(pen, x, y))
    }
    val livePen = rememberUpdatedState(pen)
    val haptics = LocalHapticFeedback.current
    val liveHaptics = rememberUpdatedState<() -> Unit> {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    // Drawing a line is a mode you switch on, not something a drag silently means.
    //
    // It first shipped as "drag from empty canvas with a line pen draws a line", which quietly took
    // the drag and the pinch away from everything else — you could no longer resize or rotate while
    // a line was the pen, and a tap that wandered a few pixels left a stub behind.
    var lineMode by remember { mutableStateOf(false) }
    val liveLineMode = rememberUpdatedState(lineMode)
    /**
     * Collapse one continuous gesture into one undo step.
     *
     * A drag mutates on every pointer event. Capture the initial state and suppress intermediate
     * undo entries, then commit one entry when the finger lifts. Besides making one Undo reverse
     * one gesture, this avoids retaining full action-list copies for all its pointer frames.
     */
    val liveGestureBegin = rememberUpdatedState<() -> Unit> {
        if (gestureBase == null) gestureBase = draft.actions to draft.undo
    }
    val liveGestureEnd = rememberUpdatedState<() -> Unit> {
        val base = gestureBase
        gestureBase = null
        // Read the state directly: multiple pointer events can arrive before recomposition
        // updates a rememberUpdatedState holder, including the final event of a drag.
        if (base != null && base.first !== draft.actions) {
            draft = draft.copy(undo = base.second + listOf(base.first), redo = emptyList())
            persistDraft(draft)
        }
    }
    // The line being dragged out right now. Not part of the document until the finger lifts.
    var lineFrom by remember { mutableStateOf<Offset?>(null) }
    var lineTo by remember { mutableStateOf<Offset?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use {
                    it.write(EidogramCanonical.documentJson(draft.document).toByteArray(Charsets.UTF_8))
                }
            }.onSuccess {
                status = "Эйдограмма экспортирована"
            }.onFailure {
                status = "Ошибка экспорта: ${it.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .let(EidogramParser::parseCanonical)
                    .let(EditorDraftFactory::fromDocument)
            }.onSuccess {
                setDraft(it)
                multiSelect = false
                status = "Эйдограмма импортирована"
            }.onFailure {
                status = "Импорт отклонён: ${it.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title ?: if (onSend == null) "Эйдограмма" else "Новая эйдограмма",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (onClose != null) {
                        TextButton(onClick = onClose) { Text("←") }
                    }
                },
                actions = {
                    // Clearing goes through the reducer as select-all + delete, so it lands on the
                    // undo stack as one step. Rebuilding an empty EditorDraft would have been
                    // shorter and would have thrown the undo history away with it — an unrecoverable
                    // button next to the one that sends.
                    TextButton(
                        onClick = {
                            val all = snapshot.instances.mapTo(mutableSetOf()) { it.instanceId }
                            setDraft(
                                EditorReducer.reduce(
                                    EditorReducer.reduce(draft, EditorCommand.Select(all)),
                                    EditorCommand.DeleteSelected,
                                )
                            )
                            multiSelect = false
                            status = "Очищено"
                        },
                        enabled = snapshot.instances.isNotEmpty(),
                    ) { Text("Очистить") }
                    if (onSend != null) {
                        TextButton(
                            onClick = {
                                if (snapshot.instances.isEmpty()) {
                                    status = "Пустую эйдограмму отправить нельзя"
                                } else {
                                    runCatching { onSend(draft.document) }
                                        .onSuccess { accepted ->
                                            if (accepted) {
                                                gestureBase = null
                                                DraftStore.clearInBackground(context, draftKey)
                                                draft = EditorDraft()
                                                status = "Сообщение сохранено"
                                                onClose?.invoke()
                                            } else status = "Сообщение не принято"
                                        }
                                        .onFailure { status = "Ошибка отправки: ${it.message}" }
                                }
                            },
                            enabled = snapshot.instances.isNotEmpty() && sendEnabled,
                        ) { Text(sendLabel) }
                    }
                    if (showFileActions) {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Открыть") }
                    }
                    if (showFileActions) TextButton(onClick = {
                        val h = EidogramCanonical.contentHash(draft.document).take(12)
                        exportLauncher.launch("eidogram-$h.json")
                    }) { Text("Экспорт") }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 6.dp) {
                // Choosing from the palette only sets the pen. It used to also restyle whatever
                // was selected, and since placing a glyph selects it, picking a dot right after
                // drawing a triangle turned that triangle into a dot instead of drawing a new one.
                // Fixed height, whatever the family needs.
                //
                // Circles show colour + size, lines show slope + length + thickness, outlines show
                // one row — so the bar changed height on every family switch and the canvas above it
                // resized under the drawing. A drawing surface that moves when you pick a tool is
                // unusable; anything that does not fit scrolls inside the bar instead.
                GlyphPalette(
                    pen = pen,
                    onPen = {
                        pen = it
                        // Picking "Линии" *is* saying you want to draw one. Behind a separate
                        // toggle it was undiscoverable: the natural move — choose the family, drag
                        // across the canvas — did nothing, because the mode was still off. The
                        // toggle stays so it can be turned off; it just is not the way in.
                        lineMode = LineDrawing.isLine(it)
                    },
                    modifier = Modifier
                        .height(168.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        modifier = modifier,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            header?.invoke()
            EditorToolbar(
                selectedCount = draft.selectedIds.size,
                multiSelect = multiSelect,
                canUngroup = snapshot.instances.any { it.instanceId in draft.selectedIds && it.groupId != null },
                onToggleMulti = {
                    multiSelect = !multiSelect
                    if (!multiSelect && draft.selectedIds.size > 1) command(EditorCommand.Select(emptySet()))
                },
                on = ::command,
                canUndo = draft.undo.isNotEmpty(),
                canRedo = draft.redo.isNotEmpty(),
                lineMode = lineMode,
                canDrawLine = LineDrawing.isLine(pen),
                onToggleLineMode = { lineMode = !lineMode },
            )
            // Reserved whether or not there is anything to say. As a `status?.let { Text(...) }`
            // this row appeared and vanished with every action and shifted the canvas under the
            // drawing by its own height — the same defect as the resizing palette, in a place where
            // it looked like a harmless conditional.
            Text(
                status
                    ?: sendDisabledReason?.takeIf { !sendEnabled }
                    ?: when {
                        lineMode -> "Проведите пальцем между двумя точками"
                        pen.isEmpty() -> "Выберите фигуру внизу, чтобы ставить её нажатием"
                        else -> "Долгое нажатие ставит фигуру поверх другой"
                    },
                Modifier.fillMaxWidth().height(18.dp).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("eidogramCanvas")
                    .background(Color(0xFFF3F3F3))
                    // Tap and drag in one gesture loop.
                    //
                    // As two separate pointerInput modifiers they competed for the same pointer and
                    // the drag never won: whichever sat closer to the node consumed the gesture
                    // first. Deciding here — moved past touch slop means drag, released on the spot
                    // means tap — removes the race entirely.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            fun fpX(v: Float) = (v / size.width * ProtocolLineage.CANVAS_W_FP).toInt()
                            fun fpY(v: Float) = (v / size.height * ProtocolLineage.CANVAS_H_FP).toInt()

                            val startX = fpX(down.position.x)
                            val startY = fpY(down.position.y)
                            val grabbed = EidogramHitTest.hitTest(liveSnapshot.value, startX, startY)
                            // Dragging from empty canvas with a line selected draws that line
                            // between the two points, at whatever angle and length the drag has.
                            var drawingLine =
                                liveLineMode.value && grabbed == null && LineDrawing.isLine(livePen.value)
                            var dragging = false
                            var last = down.position
                            var lastPressed = 1
                            // Long press places the pen where the finger is, whatever is already
                            // there. Overlap was impossible without it: a tap inside a circle can
                            // only mean one thing, and it meant "select the circle", so a triangle
                            // could never be put inside one.
                            //
                            // A gesture rather than a tenth toolbar button. Nine controls at 36dp
                            // already fill the row on a 393dp screen; a tenth wraps the toolbar onto
                            // a second line and takes that height off the canvas permanently.
                            val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                            // Two flags, not one. `longPressFired` stops the deadline being asked
                            // about a second time; `placedByLongPress` records that something was
                            // really put down, and only that may suppress the tap on lift.
                            //
                            // Collapsing them span the CPU flat out: with no pen there was nothing
                            // to place, so the branch `continue`d without marking anything, the next
                            // iteration saw the deadline still passed and returned null again, and
                            // the loop never suspended for as long as the finger stayed down.
                            var longPressFired = false
                            var placedByLongPress = false

                            while (true) {
                                // Waiting with a deadline, not just reading events: a finger held
                                // perfectly still produces no pointer events at all, so a loop that
                                // only reacts to movement would never notice a long press.
                                val event = if (!dragging && !longPressFired) {
                                    val remaining = longPressAt - android.os.SystemClock.uptimeMillis()
                                    if (remaining <= 0) null
                                    else withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                } else {
                                    awaitPointerEvent()
                                }
                                if (event == null) {
                                    longPressFired = true
                                    // No pen: the press means nothing here, so it must neither buzz
                                    // nor place, and the gesture carries on as an ordinary one.
                                    if (livePen.value.isNotEmpty()) {
                                        placedByLongPress = true
                                        // Tell the finger. Without it a slow tap on an existing
                                        // shape silently stamps a copy on top of it and reads as a
                                        // glitch; the buzz separates "held" from "tapped".
                                        liveHaptics.value()
                                        // Holding still is not drawing a line; and the glyph just
                                        // placed is the selection, so sliding on from here moves it.
                                        drawingLine = false
                                        livePlace.value(startX, startY)
                                        lineFrom = null
                                        lineTo = null
                                    }
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: run { liveGestureEnd.value(); lineFrom = null; lineTo = null; null } ?: break
                                if (!change.pressed) {
                                    val from = lineFrom
                                    val to = lineTo
                                    lineFrom = null
                                    lineTo = null
                                    // Below this the drag is a slip of the finger, not a line. Without
                                    // it every tap in line mode left a stub on the canvas.
                                    val long = from != null && to != null &&
                                        (to - from).getDistance() > viewConfiguration.touchSlop * 3
                                    if (dragging && drawingLine && long) {
                                        from!!; to!!
                                        // Position maps per axis, but a glyph's own size maps by
                                        // min(sx, sy) and its rotation happens in pixels — so the
                                        // angle and length have to be measured in pixels too, or a
                                        // line on a non-square canvas misses its own endpoints.
                                        val unit = min(size.width, size.height) / 1_000_000f
                                        val dx = to.x - from.x
                                        val dy = to.y - from.y
                                        val basePx = LineDrawing.baseLengthFp(livePen.value) * unit
                                        liveCommand.value(
                                            EditorCommand.AddGlyph(
                                                LineDrawing.penFor(livePen.value),
                                                fpX((from.x + to.x) / 2f),
                                                fpY((from.y + to.y) / 2f),
                                                rotationMdeg = (Math.toDegrees(atan2(dy, dx).toDouble()) * 1000)
                                                    .toInt().coerceIn(-360_000, 360_000),
                                                scaleXFp = (hypot(dx, dy) / basePx * 1_000_000f)
                                                    .toInt().coerceIn(10_000, 10_000_000),
                                            )
                                        )
                                    } else if (!dragging && !drawingLine && !placedByLongPress) {
                                        if (grabbed == null) {
                                            livePlace.value(startX, startY)
                                        } else {
                                            liveCommand.value(
                                                EditorCommand.Select(
                                                    if (liveMulti.value)
                                                        EditorSelection.toggle(liveSnapshot.value, liveSelection.value, grabbed)
                                                    else
                                                        EditorSelection.single(liveSnapshot.value, grabbed)
                                                )
                                            )
                                        }
                                    }
                                    liveGestureEnd.value()
                                    break
                                }
                                // Second finger down: this is a pinch, not a drag. Handled here
                                // rather than in a separate detector, because the loop consumes the
                                // pointer and a competing detector would never see it.
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) {
                                    // A pinch outranks a half-drawn line: drop the draft and let the
                                    // usual scale/rotate handling below run.
                                    lineFrom = null
                                    lineTo = null
                                    dragging = true
                                    // The event right after a finger arrives or leaves has no usable
                                    // previous position for it, so the ratio it reports is garbage.
                                    // Applying that was what made the figure balloon off the canvas.
                                    if (pressed != lastPressed) {
                                        lastPressed = pressed
                                        last = change.position
                                        continue
                                    }
                                    if (liveSelection.value.isNotEmpty()) {
                                        liveGestureBegin.value()
                                        // Clamp per event: a pinch is a sequence of small steps, and
                                        // anything larger is noise rather than intent.
                                        val zoom = event.calculateZoom().coerceIn(0.92f, 1.08f)
                                        if (kotlin.math.abs(zoom - 1f) > 0.004f) {
                                            liveCommand.value(
                                                EditorCommand.ScaleSelected((zoom * 1_000_000).toInt())
                                            )
                                        }
                                        // Deadband: sliding two fingers apart reports a fraction of a
                                        // degree every frame, and summing that visibly skews the shape.
                                        val rotation = event.calculateRotation()
                                        if (kotlin.math.abs(rotation) > 0.35f) {
                                            liveCommand.value(
                                                EditorCommand.RotateSelected((rotation * 1000).toInt())
                                            )
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    last = change.position
                                    continue
                                }
                                lastPressed = pressed
                                if (!dragging &&
                                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                                ) {
                                    dragging = true
                                    if (grabbed != null && grabbed !in liveSelection.value) {
                                        liveCommand.value(
                                            EditorCommand.Select(EditorSelection.single(liveSnapshot.value, grabbed))
                                        )
                                    }
                                }
                                if (dragging && drawingLine) {
                                    if (lineFrom == null) lineFrom = down.position
                                    lineTo = change.position
                                    change.consume()
                                    last = change.position
                                    continue
                                }
                                if (dragging && liveSelection.value.isNotEmpty()) {
                                    liveGestureBegin.value()
                                    val d = change.position - last
                                    liveCommand.value(
                                        EditorCommand.TranslateSelected(fpX(d.x), fpY(d.y))
                                    )
                                    change.consume()
                                }
                                last = change.position
                            }
                        }
                    }
            ) {
                snapshot.instances.forEach { drawGlyph(it, selected = it.instanceId in draft.selectedIds) }
                val from = lineFrom
                val to = lineTo
                if (from != null && to != null) {
                    drawLine(
                        Color(0xFF007AFF),
                        from, to,
                        strokeWidth = LineDrawing.thicknessFp(pen) *
                            (min(size.width, size.height) / 1_000_000f),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EditorToolbar(
    selectedCount: Int,
    multiSelect: Boolean,
    canUngroup: Boolean,
    onToggleMulti: () -> Unit,
    on: (EditorCommand) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    lineMode: Boolean,
    canDrawLine: Boolean,
    onToggleLineMode: () -> Unit,
) {
    val hasSelection = selectedCount > 0
    // Wrapping, not scrolling.
    //
    // This was one horizontally scrolling row, so every control past the fourth sat off the right
    // edge with nothing to suggest it was there. "Удалить" and "Группа" had been implemented and
    // working the whole time and simply could not be reached — they were reported as missing and as
    // broken respectively. A toolbar whose contents grow release by release must wrap.
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToolIcon(IconUndo, "Отменить", canUndo) { on(EditorCommand.Undo) }
        ToolIcon(IconRedo, "Вернуть", canRedo) { on(EditorCommand.Redo) }
        ToolIcon(IconSelect, "Выбор", true, active = multiSelect, onClick = onToggleMulti)
        if (selectedCount > 0) {
            Text(
                "$selectedCount",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        // Always present, greyed out when the pen is not a line. Shown conditionally it was the
        // ninth control only some of the time, the row wrapped when it appeared, and the canvas
        // lost a row's height the moment you picked "Линии" — the very jumping this layout exists
        // to prevent. Nine at 36dp is what fits one row on a 393dp screen; a tenth will not.
        ToolIcon(IconLine, "Рисовать линию", canDrawLine, active = lineMode, onClick = onToggleLineMode)
        ToolIcon(IconGroup, "Группа", selectedCount >= 2) { on(EditorCommand.GroupSelected) }
        ToolIcon(IconUngroup, "Разгруппировать", canUngroup) { on(EditorCommand.UngroupSelected) }
        ToolIcon(IconSendBackward, "Ниже", hasSelection) { on(EditorCommand.SendBackward) }
        ToolIcon(IconBringForward, "Выше", hasSelection) { on(EditorCommand.BringForward) }
        ToolIcon(IconDelete, "Удалить", hasSelection) { on(EditorCommand.DeleteSelected) }
    }
}

/**
 * Drawing a line between two arbitrary points out of a catalogue that has neither.
 *
 * `EidoGlyphCatalogV1` is frozen: eight fixed slopes, three lengths. But a line at an arbitrary
 * angle and length is just one of those under a transform, and the document format already carries
 * a rotation and a scale per instance. So a dragged line is the longest pose000 stick of the chosen
 * thickness, rotated to the drag's angle and stretched to its length — no new glyph, no change to
 * `catalog_hash`, and old eidograms keep reading.
 *
 * The longest stick is always the base so the stretch stays inside the format's 0.01x..10x range;
 * starting from the short one, a line across the canvas would need eleven times its length.
 */
internal object LineDrawing {
    fun isLine(pen: String) = pen.startsWith("stick.")

    /** The chosen colour and thickness kept, slope and length surrendered to the drag. */
    fun penFor(pen: String): String {
        val parts = pen.split('.')
        return "stick.${parts.getOrElse(1) { "black" }}.pose000.l.${parts.getOrElse(4) { "normal" }}"
    }

    private fun capsule(pen: String) =
        EidoGlyphCatalogV1.requireGlyph(penFor(pen)).render as GlyphRenderSpec.Capsule

    fun baseLengthFp(pen: String): Int = capsule(pen).lengthFp

    fun thicknessFp(pen: String): Int = capsule(pen).thicknessFp
}

/**
 * A toolbar control: pictogram only, with the name behind a long press.
 *
 * The labels were words, and by the ninth control they had pushed the useful half of the toolbar
 * off the screen. A tooltip keeps the name discoverable without spending the width on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } },
        state = rememberTooltipState(),
    ) {
        FilledIconToggleButton(
            checked = active,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier.size(36.dp).testTag("tool:$name"),
        ) {
            Icon(icon, contentDescription = name, modifier = Modifier.size(20.dp))
        }
    }
}
