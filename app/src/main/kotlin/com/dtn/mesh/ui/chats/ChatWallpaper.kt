package com.dtn.mesh.ui.chats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
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

/**
 * Chat wallpapers: solid presets, the built-in doodle pattern, a doodle the user drew
 * (`DoodleEditorPage`), or a gallery photo cropped to the chat window (`WallpaperCropPage`).
 */
object ChatWallpapers {
    data class Preset(val key: String, val label: String, val color: Color)

    const val DOODLE = "DOODLE"
    const val PHOTO = SettingsRepository.THEME_PHOTO
    /** The doodle the user drew, saved as an image. */
    const val DRAWN = SettingsRepository.THEME_DRAWN

    /**
     * Size of the chat's wallpaper area the last time a chat was laid out (px), so the crop
     * frame and the doodle canvas match the chat window. In memory only; null until a chat opens.
     */
    @Volatile var lastChatAreaPx: IntSize? = null

    /**
     * Width ÷ height of the chat window, always portrait (a landscape chat is still drawn
     * centre-cropped). Before any chat was opened: the screen minus the status and top bars.
     */
    fun chatAreaAspect(context: Context): Float {
        lastChatAreaPx?.takeIf { it.width > 0 && it.height > 0 }?.let {
            return kotlin.math.min(it.width, it.height).toFloat() / max(it.width, it.height)
        }
        val m = context.resources.displayMetrics
        val short = kotlin.math.min(m.widthPixels, m.heightPixels).toFloat()
        val long = max(m.widthPixels, m.heightPixels) - 88f * m.density // status bar + chat top bar
        return if (short > 0 && long > short) short / long else 9f / 16f
    }

    val solids = listOf(
        Preset(SettingsRepository.DEFAULT_THEME, "Dark Slate (default)", DtnColors.Background),
        Preset("SLATE", "Midnight Navy", Color(0xFF0E1A2E)),
        Preset("FOREST", "Forest", Color(0xFF0D1B16)),
        Preset("AMBER", "Espresso", Color(0xFF1C1713)),
        Preset("MIDNIGHT", "Charcoal", Color(0xFF111214)),
    )

    /** Fill colour of [theme] (the doodle draws on the default colour; a photo shows it while loading). */
    fun baseColor(theme: String): Color = solids.firstOrNull { it.key == theme }?.color ?: DtnColors.Background

    /** Long side of a saved wallpaper image. */
    const val MAX_SIDE_PX = 1600

    /** Long side of the photo shown on the crop page (room to zoom in before it gets soft). */
    const val CROP_SOURCE_MAX_SIDE_PX = 2400

    /**
     * Decode the picked photo for the crop page: upright (EXIF orientation applied), software
     * pixels, long side ≤ [maxSide]. Android 9+ uses ImageDecoder (also reads HEIC); older
     * versions use BitmapFactory. Throws when the photo can't be opened or decoded.
     */
    fun decodeForCrop(context: Context, uri: Uri, maxSide: Int = CROP_SOURCE_MAX_SIDE_PX): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val k = maxSide.toFloat() / max(w, h)
                    if (k < 1f) decoder.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
            if (decoded != null) return decoded // else try BitmapFactory below
        }
        // inJustDecodeBounds makes decodeStream return null by design: read the size from the options.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: error("cannot open the photo")).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = (resolver.openInputStream(uri) ?: error("cannot open the photo")).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("decode failed")
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        val upright = if (degrees == 0) decoded else rotate(decoded, degrees).also { if (it !== decoded) decoded.recycle() }
        val k = maxSide.toFloat() / max(upright.width, upright.height)
        return if (k < 1f) {
            Bitmap.createScaledBitmap(upright, (upright.width * k).roundToInt(), (upright.height * k).roundToInt(), true)
                .also { if (it !== upright) upright.recycle() }
        } else upright
    }

    /** [src] turned clockwise by [degrees] (a new bitmap; [src] is left as it is). */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Save [bitmap] (long side scaled down to ≤ [MAX_SIDE_PX]) as an app-private image named
     * `<prefix>_<time>`: JPEG for photos, PNG for drawn doodles (sharp lines). [bitmap] is not
     * recycled. @return the new file.
     */
    fun saveWallpaper(context: Context, bitmap: Bitmap, prefix: String, png: Boolean = false): File {
        val k = MAX_SIDE_PX.toFloat() / max(bitmap.width, bitmap.height)
        val out = if (k < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * k).roundToInt().coerceAtLeast(1),
                (bitmap.height * k).roundToInt().coerceAtLeast(1), true)
        } else bitmap
        val file = File(context.filesDir, "${prefix}_${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
        try {
            file.outputStream().use {
                val ok = if (png) out.compress(Bitmap.CompressFormat.PNG, 100, it)
                else out.compress(Bitmap.CompressFormat.JPEG, 90, it)
                check(ok) { "compress failed" }
            }
        } catch (t: Throwable) {
            file.delete()
            throw t
        } finally {
            if (out !== bitmap) out.recycle()
        }
        return file
    }
}

/**
 * Pan / zoom math of the crop page (pure). The photo ([imgW] × [imgH] px) is drawn centred on a
 * fixed frame ([frameW] × [frameH] px, the chat window's shape) at `base × zoom`, where `base`
 * is the smallest scale that covers the frame, then moved by ([offsetX], [offsetY]) px. It
 * always covers the frame: zoom ≥ 1 and the offsets are clamped.
 */
data class CropTransform(val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    companion object {
        const val MAX_ZOOM = 5f

        fun baseScale(imgW: Int, imgH: Int, frameW: Float, frameH: Float): Float =
            max(frameW / imgW, frameH / imgH)
    }

    fun scale(imgW: Int, imgH: Int, frameW: Float, frameH: Float): Float = baseScale(imgW, imgH, frameW, frameH) * zoom

    /** This transform with zoom in 1..[MAX_ZOOM] and the photo still covering the frame. */
    fun clamped(imgW: Int, imgH: Int, frameW: Float, frameH: Float): CropTransform {
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val s = baseScale(imgW, imgH, frameW, frameH) * z
        val maxX = max(0f, (imgW * s - frameW) / 2f)
        val maxY = max(0f, (imgH * s - frameH) / 2f)
        return CropTransform(z, offsetX.coerceIn(-maxX, maxX), offsetY.coerceIn(-maxY, maxY))
    }

    /**
     * One pinch / drag step: zoom by [zoomChange] around the fingers' centre ([cx], [cy] px from
     * the frame's centre), then move by ([panX], [panY]); clamped.
     */
    fun gesture(cx: Float, cy: Float, panX: Float, panY: Float, zoomChange: Float,
                imgW: Int, imgH: Int, frameW: Float, frameH: Float): CropTransform {
        val base = baseScale(imgW, imgH, frameW, frameH)
        val newZoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val ratio = newZoom / zoom // = new scale ÷ old scale
        // Keep the photo point under the fingers under the fingers.
        val nx = cx - (cx - offsetX) * ratio + panX
        val ny = cy - (cy - offsetY) * ratio + panY
        if (base <= 0f || !base.isFinite()) return this
        return CropTransform(newZoom, nx, ny).clamped(imgW, imgH, frameW, frameH)
    }

    /** The part of the photo inside the frame, in photo px: left, top, width, height. */
    fun cropRect(imgW: Int, imgH: Int, frameW: Float, frameH: Float): IntArray {
        val t = clamped(imgW, imgH, frameW, frameH)
        val s = t.scale(imgW, imgH, frameW, frameH)
        val w = (frameW / s).roundToInt().coerceIn(1, imgW)
        val h = (frameH / s).roundToInt().coerceIn(1, imgH)
        val left = ((-frameW / 2f - t.offsetX) / s + imgW / 2f).roundToInt().coerceIn(0, imgW - w)
        val top = ((-frameH / 2f - t.offsetY) / s + imgH / 2f).roundToInt().coerceIn(0, imgH - h)
        return intArrayOf(left, top, w, h)
    }
}

/** The largest [aspect] (width ÷ height) box that fits in [maxW] × [maxH]. */
fun fitAspect(maxW: Float, maxH: Float, aspect: Float): Size =
    if (maxW / maxH > aspect) Size(maxH * aspect, maxH) else Size(maxW, maxW / aspect)

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

/**
 * Draws the chat wallpaper behind the content. [photo] is the saved image when [theme] is the
 * photo or the drawn-doodle one (a drawn doodle is shown exactly as drawn, without the scrim).
 */
fun Modifier.chatWallpaper(theme: String, photo: ImageBitmap?): Modifier = drawBehind {
    drawRect(ChatWallpapers.baseColor(theme))
    when (theme) {
        ChatWallpapers.DOODLE -> drawDoodles()
        ChatWallpapers.PHOTO -> if (photo != null) {
            drawCropped(photo)
            drawRect(Color.Black.copy(alpha = 0.45f)) // keeps bubbles and text readable
        }
        ChatWallpapers.DRAWN -> if (photo != null) drawCropped(photo)
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
