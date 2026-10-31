package com.dtn.mesh.ui.chats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dtn.mesh.settings.SettingsRepository
import com.dtn.mesh.ui.theme.DtnColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Chat wallpapers: solid presets, a drawn doodle pattern, or a gallery photo. */
object ChatWallpapers {
    data class Preset(val key: String, val label: String, val color: Color)

    const val DOODLE = "DOODLE"
    const val PHOTO = SettingsRepository.THEME_PHOTO

    val solids = listOf(
        Preset(SettingsRepository.DEFAULT_THEME, "Dark Slate (default)", DtnColors.Background),
        Preset("SLATE", "Midnight Navy", Color(0xFF0E1A2E)),
        Preset("FOREST", "Forest", Color(0xFF0D1B16)),
        Preset("AMBER", "Espresso", Color(0xFF1C1713)),
        Preset("MIDNIGHT", "Charcoal", Color(0xFF111214)),
    )

    /** Fill colour of [theme] (the doodle draws on the default colour; a photo shows it while loading). */
    fun baseColor(theme: String): Color = solids.firstOrNull { it.key == theme }?.color ?: DtnColors.Background

    private const val MAX_SIDE_PX = 1600

    /** Decode [uri] downscaled and save it as an app-private JPEG. @return the new file. */
    fun importPhoto(context: Context, uri: Uri): File {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("cannot open $uri")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE_PX) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("decode failed")
        val scale = MAX_SIDE_PX.toFloat() / max(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val out = File(context.filesDir, "chat_wallpaper_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return out
    }
}

/** The saved wallpaper photo, decoded off the main thread (null while loading / if missing). */
@Composable
fun rememberWallpaperPhoto(path: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}

/** Draws the chat wallpaper behind the content. [photo] is used when [theme] is the photo one. */
fun Modifier.chatWallpaper(theme: String, photo: ImageBitmap?): Modifier = drawBehind {
    drawRect(ChatWallpapers.baseColor(theme))
    when (theme) {
        ChatWallpapers.DOODLE -> drawDoodles()
        ChatWallpapers.PHOTO -> if (photo != null) {
            drawCropped(photo)
            drawRect(Color.Black.copy(alpha = 0.45f)) // keeps bubbles and text readable
        }
    }
}

/** Centre-crop [img] to fill the whole area. */
private fun DrawScope.drawCropped(img: ImageBitmap) {
    val scale = max(size.width / img.width, size.height / img.height)
    val srcW = (size.width / scale).roundToInt().coerceIn(1, img.width)
    val srcH = (size.height / scale).roundToInt().coerceIn(1, img.height)
    drawImage(
        img,
        srcOffset = IntOffset((img.width - srcW) / 2, (img.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
}

/** A faint repeating doodle: chat bubbles, radio waves, dots and envelopes on a grid. */
private fun DrawScope.drawDoodles() {
    val ink = Color.White.copy(alpha = 0.06f)
    val cell = 64.dp.toPx()
    val stroke = Stroke(width = 1.5.dp.toPx())
    val cols = (size.width / cell).toInt() + 1
    val rows = (size.height / cell).toInt() + 1
    for (r in 0..rows) for (c in 0..cols) {
        // Stagger every other row and vary the motif per cell so it doesn't read as a grid.
        val x = c * cell + if (r % 2 == 1) cell / 2 else 0f
        val y = r * cell
        val s = cell * 0.32f
        val center = Offset(x + cell / 2, y + cell / 2)
        when ((r * 7 + c * 3) % 4) {
            0 -> { // chat bubble with a tail
                val tl = Offset(center.x - s, center.y - s * 0.7f)
                drawRoundRect(ink, tl, Size(s * 2, s * 1.3f), CornerRadius(s * 0.4f), style = stroke)
                val tail = Path().apply {
                    moveTo(tl.x + s * 0.4f, tl.y + s * 1.3f)
                    lineTo(tl.x + s * 0.2f, tl.y + s * 1.8f)
                    lineTo(tl.x + s * 0.9f, tl.y + s * 1.3f)
                }
                drawPath(tail, ink, style = stroke)
            }
            1 -> for (i in 1..3) { // radio waves
                val rad = s * 0.45f * i
                drawArc(ink, -135f, 90f, false, Offset(center.x - rad, center.y - rad + s * 0.6f), Size(rad * 2, rad * 2), style = stroke)
            }
            2 -> { // three dots
                for (i in -1..1) drawCircle(ink, s * 0.18f, Offset(center.x + i * s * 0.6f, center.y))
            }
            else -> { // envelope
                val tl = Offset(center.x - s, center.y - s * 0.65f)
                drawRect(ink, tl, Size(s * 2, s * 1.3f), style = stroke)
                val flap = Path().apply {
                    moveTo(tl.x, tl.y); lineTo(center.x, center.y); lineTo(tl.x + s * 2, tl.y)
                }
                drawPath(flap, ink, style = stroke)
            }
        }
    }
}
