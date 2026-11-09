package com.dtn.mesh.ui.chats

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.ConfirmDialog
import com.dtn.mesh.ui.components.DtnFilterChip
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * One finger stroke of a doodle. [points] = x0, y0, x1, y1, … as fractions (0..1) of the canvas
 * width / height and [width] a fraction of the canvas width, so it draws the same at any size.
 * An [eraser] stroke paints with the background colour.
 */
class DoodleStroke(val color: Int, val width: Float, val eraser: Boolean, val points: FloatArray)

/** Pens, brush sizes and backgrounds of the doodle editor. */
object DoodlePalette {
    val pens = listOf(
        "Navy" to Color(0xFF0F1B33), "White" to Color(0xFFFFFFFF), "Blue" to Color(0xFF1769FF),
        "Red" to Color(0xFFE5484D), "Green" to Color(0xFF22A447), "Orange" to Color(0xFFF59E0B),
        "Purple" to Color(0xFF7C5CFF), "Pink" to Color(0xFFE5539A),
    )
    /** Thin, medium, thick (fraction of the canvas width). */
    val widths = listOf("Thin" to 0.008f, "Medium" to 0.018f, "Thick" to 0.04f)
    val backgrounds = listOf(
        "Light" to Color(0xFFF4F7FC), "White" to Color(0xFFFFFFFF), "Sky" to Color(0xFFE8F0FF),
        "Mint" to Color(0xFFE7F6EC), "Navy" to Color(0xFF0E1A2E), "Charcoal" to Color(0xFF111214),
    )
}

/** Long side of the saved doodle image (px). */
private const val DOODLE_LONG_SIDE_PX = ChatWallpapers.MAX_SIDE_PX

/** Strokes survive rotation / process death: 4 saveable values per stroke. */
private val StrokesSaver = listSaver<SnapshotStateList<DoodleStroke>, Any>(
    save = { list -> list.flatMap { listOf(it.color, it.width, it.eraser, it.points) } },
    restore = { flat ->
        flat.chunked(4).map { DoodleStroke(it[0] as Int, it[1] as Float, it[2] as Boolean, it[3] as FloatArray) }
            .toMutableStateList()
    },
)

/**
 * Draw a doodle (`wallpaper/doodle`, from Settings › Chat Appearance › Draw your own doodle):
 * a canvas shaped like the chat window, 8 pens, 3 sizes, an eraser, 6 backgrounds, Undo and
 * Clear. "Set as wallpaper" saves it as an image ([DtnViewModel.setChatDoodle]) and returns.
 */
@Composable
fun DoodleEditorPage(viewModel: DtnViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val scope = rememberCoroutineScope()
    val aspect = remember { ChatWallpapers.chatAreaAspect(context) }
    val strokes = rememberSaveable(saver = StrokesSaver) { mutableStateListOf<DoodleStroke>() }
    val current = remember { mutableStateListOf<Offset>() } // the stroke being drawn (fractions)
    var pen by rememberSaveable { mutableIntStateOf(2) }
    var widthIndex by rememberSaveable { mutableIntStateOf(1) }
    var background by rememberSaveable { mutableIntStateOf(0) }
    var eraser by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val bg = DoodlePalette.backgrounds[background].second

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            InnerTopBar("Draw a doodle", onBack = onBack, subtitle = "Draw with your finger", actions = {
                IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                    enabled = strokes.isNotEmpty() && !saving) {
                    Icon(DtnIcons.Undo, contentDescription = "Undo", tint = if (strokes.isNotEmpty()) DtnColors.Primary else DtnColors.TextHint)
                }
                IconButton(onClick = { confirmClear = true }, enabled = strokes.isNotEmpty() && !saving) {
                    Icon(DtnIcons.Delete, contentDescription = "Clear the doodle",
                        tint = if (strokes.isNotEmpty()) DtnColors.RedText else DtnColors.TextHint)
                }
            })
        },
        bottomBar = {
            Surface(color = DtnColors.Card, border = BorderStroke(1.dp, DtnColors.CardBorder)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    ToolLabel("Pen")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DoodlePalette.pens.forEachIndexed { i, (name, color) ->
                            ColorDot(color, name, selected = !eraser && pen == i, shape = CircleShape) { pen = i; eraser = false }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DoodlePalette.widths.forEachIndexed { i, (name, _) ->
                            DtnFilterChip(name, selected = widthIndex == i, onClick = { widthIndex = i })
                        }
                        DtnFilterChip("Eraser", selected = eraser, onClick = { eraser = !eraser })
                    }
                    Spacer(Modifier.height(4.dp))
                    ToolLabel("Background")
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DoodlePalette.backgrounds.forEachIndexed { i, (name, color) ->
                            ColorDot(color, "$name background", selected = background == i, shape = RoundedCornerShape(8.dp)) { background = i }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(if (saving) "Saving…" else "Set as wallpaper", enabled = !saving, modifier = Modifier.fillMaxWidth(), onClick = {
                        saving = true
                        val snapshot = strokes.toList()
                        val bgArgb = bg.toArgb()
                        scope.launch {
                            val bitmap = runCatching {
                                withContext(Dispatchers.Default) { renderDoodle(snapshot, bgArgb, aspect) }
                            }.getOrNull()
                            if (bitmap == null) {
                                saving = false
                                viewModel.showMessage("Couldn't save the doodle")
                                return@launch
                            }
                            viewModel.setChatDoodle(bitmap) { ok ->
                                saving = false
                                if (ok) onDone()
                            }
                        }
                    })
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(DtnColors.Track), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val margin = with(density) { 16.dp.toPx() }
            val fit = fitAspect(
                (constraints.maxWidth - 2 * margin).coerceAtLeast(1f),
                (constraints.maxHeight - 2 * margin).coerceAtLeast(1f),
                aspect,
            )
            val canvasShape = RoundedCornerShape(12.dp)
            Canvas(
                Modifier.size(with(density) { fit.width.toDp() }, with(density) { fit.height.toDp() })
                    .clip(canvasShape).border(1.dp, DtnColors.CardBorder, canvasShape).clipToBounds()
                    .semantics { contentDescription = "Doodle canvas. Draw with one finger." }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            fun frac(p: Offset) = Offset(
                                (p.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                (p.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                            )
                            current.clear()
                            current.add(frac(down.position))
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                current.add(frac(change.position))
                                change.consume()
                            }
                            if (current.isNotEmpty() && !saving) {
                                val pts = FloatArray(current.size * 2)
                                current.forEachIndexed { i, p -> pts[2 * i] = p.x; pts[2 * i + 1] = p.y }
                                strokes.add(DoodleStroke(DoodlePalette.pens[pen].second.toArgb(),
                                    DoodlePalette.widths[widthIndex].second, eraser, pts))
                            }
                            current.clear()
                        }
                    },
            ) {
                drawRect(bg)
                strokes.forEach { s -> drawStroke(s.points, if (s.eraser) bg else Color(s.color), s.width) }
                if (current.isNotEmpty()) {
                    val pts = FloatArray(current.size * 2)
                    current.forEachIndexed { i, p -> pts[2 * i] = p.x; pts[2 * i + 1] = p.y }
                    drawStroke(pts, if (eraser) bg else DoodlePalette.pens[pen].second, DoodlePalette.widths[widthIndex].second)
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "Clear the doodle?",
            text = "Everything you drew on this canvas is removed. Your current chat wallpaper doesn't change.",
            confirmLabel = "Clear",
            onConfirm = { strokes.clear(); confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ToolLabel(text: String) {
    Text(text, style = DtnType.Meta.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp), modifier = Modifier.padding(bottom = 6.dp))
}

/** A 36 dp colour swatch (48 dp touch target); a blue ring when [selected]. */
@Composable
private fun ColorDot(color: Color, label: String, selected: Boolean, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(role = Role.RadioButton, onClickLabel = "Choose $label", onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).clip(shape).background(color)
                .border(if (selected) 3.dp else 1.dp, if (selected) DtnColors.Primary else DtnColors.CardBorder, shape),
        )
    }
}

/** One stroke on the editor canvas: a dot for a tap, else a round-capped line through the points. */
private fun DrawScope.drawStroke(points: FloatArray, color: Color, widthFraction: Float) {
    val w = (widthFraction * size.width).coerceAtLeast(1f)
    val n = points.size / 2
    if (n == 0) return
    if (n == 1) {
        drawCircle(color, w / 2f, Offset(points[0] * size.width, points[1] * size.height))
        return
    }
    val path = Path().apply {
        moveTo(points[0] * size.width, points[1] * size.height)
        for (i in 1 until n) lineTo(points[2 * i] * size.width, points[2 * i + 1] * size.height)
    }
    drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * The doodle as an image of the chat window's shape ([aspect] = width ÷ height, long side
 * [DOODLE_LONG_SIDE_PX]), drawn exactly like the editor canvas.
 */
internal fun renderDoodle(strokes: List<DoodleStroke>, backgroundArgb: Int, aspect: Float): Bitmap {
    val (w, h) = if (aspect <= 1f) {
        (DOODLE_LONG_SIDE_PX * aspect).roundToInt().coerceAtLeast(1) to DOODLE_LONG_SIDE_PX
    } else {
        DOODLE_LONG_SIDE_PX to (DOODLE_LONG_SIDE_PX / aspect).roundToInt().coerceAtLeast(1)
    }
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(backgroundArgb)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { s ->
        paint.color = if (s.eraser) backgroundArgb else s.color
        val sw = (s.width * w).coerceAtLeast(1f)
        val n = s.points.size / 2
        if (n == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(s.points[0] * w, s.points[1] * h, sw / 2f, paint)
        } else if (n > 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = sw
            val path = android.graphics.Path().apply {
                moveTo(s.points[0] * w, s.points[1] * h)
                for (i in 1 until n) lineTo(s.points[2 * i] * w, s.points[2 * i + 1] * h)
            }
            canvas.drawPath(path, paint)
        }
    }
    return bitmap
}
