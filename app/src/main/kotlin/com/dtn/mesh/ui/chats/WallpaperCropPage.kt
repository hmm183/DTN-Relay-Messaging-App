package com.dtn.mesh.ui.chats

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Behind the photo on the crop page. */
private val CropBackdrop = Color(0xFF0B0F17)

/**
 * Crop wallpaper (`wallpaper/crop/{uri}`, from Settings › Chat Appearance › Photo from gallery):
 * the picked photo under a fixed frame shaped like the chat window. Pinch to zoom, drag to move,
 * double-tap to reset, Rotate turns it 90°. "Set as wallpaper" saves the framed part
 * ([DtnViewModel.setChatWallpaperPhoto]) and returns to Settings. Any photo size or ratio works:
 * the frame always stays covered by the photo.
 */
@Composable
fun WallpaperCropPage(viewModel: DtnViewModel, photoUri: Uri, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val aspect = remember { ChatWallpapers.chatAreaAspect(context) }
    var quarterTurns by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }

    // null while loading; a failure closes the page with a message.
    val original by produceState<Result<Bitmap>?>(initialValue = null, photoUri) {
        value = withContext(Dispatchers.IO) { runCatching { ChatWallpapers.decodeForCrop(context, photoUri) } }
    }
    val source by produceState<Bitmap?>(initialValue = null, original, quarterTurns) {
        val base = original?.getOrNull()
        value = if (base == null) null else withContext(Dispatchers.Default) { ChatWallpapers.rotate(base, quarterTurns * 90) }
    }
    LaunchedEffect(original) {
        if (original?.isFailure == true) {
            viewModel.showMessage("Couldn't open that photo")
            onBack()
        }
    }
    val image = remember(source) { source?.asImageBitmap() }
    var transform by remember(source) { mutableStateOf(CropTransform()) }
    var frame by remember { mutableStateOf(Size.Zero) }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            InnerTopBar("Crop wallpaper", onBack = onBack, subtitle = "Pinch to zoom · drag to move", actions = {
                IconButton(onClick = { quarterTurns = (quarterTurns + 1) % 4 }, enabled = source != null && !saving) {
                    Icon(DtnIcons.RotateRight, contentDescription = "Rotate 90 degrees", tint = DtnColors.Primary)
                }
            })
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(DtnColors.Background).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("The frame is the shape of your chat window. Chats dim the photo a little so messages stay readable.",
                    style = DtnType.Meta, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("Reset", onClick = { transform = CropTransform() }, enabled = source != null && !saving,
                        modifier = Modifier.weight(1f))
                    PrimaryButton(if (saving) "Saving…" else "Set as wallpaper", enabled = source != null && !saving && frame != Size.Zero,
                        onClick = {
                            val src = source ?: return@PrimaryButton
                            saving = true
                            val crop = transform.cropRect(src.width, src.height, frame.width, frame.height)
                            viewModel.setChatWallpaperPhoto(src, crop) { ok ->
                                saving = false
                                if (ok) onDone()
                            }
                        }, modifier = Modifier.weight(1.6f))
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(CropBackdrop)) {
            val margin = with(LocalDensity.current) { 20.dp.toPx() }
            val fit = fitAspect(
                (constraints.maxWidth - 2 * margin).coerceAtLeast(1f),
                (constraints.maxHeight - 2 * margin).coerceAtLeast(1f),
                aspect,
            )
            SideEffect { if (frame != fit) frame = fit }
            val img = image
            if (img == null) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                val w = img.width
                val h = img.height
                val borderPx = with(LocalDensity.current) { 2.dp.toPx() }
                Canvas(
                    Modifier.fillMaxSize()
                        .semantics { contentDescription = "Photo under the crop frame. Pinch to zoom, drag to move." }
                        .pointerInput(img, fit) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                transform = transform.gesture(
                                    centroid.x - size.width / 2f, centroid.y - size.height / 2f,
                                    pan.x, pan.y, zoom, w, h, fit.width, fit.height,
                                )
                            }
                        }
                        .pointerInput(img) { detectTapGestures(onDoubleTap = { transform = CropTransform() }) },
                ) {
                    val t = transform.clamped(w, h, fit.width, fit.height)
                    val s = t.scale(w, h, fit.width, fit.height)
                    val dw = w * s
                    val dh = h * s
                    drawImage(
                        img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(w, h),
                        dstOffset = IntOffset((size.width / 2f + t.offsetX - dw / 2f).roundToInt(),
                            (size.height / 2f + t.offsetY - dh / 2f).roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    // Dim everything outside the frame.
                    val fl = (size.width - fit.width) / 2f
                    val ft = (size.height - fit.height) / 2f
                    val dim = Color.Black.copy(alpha = 0.6f)
                    drawRect(dim, Offset.Zero, Size(size.width, ft))
                    drawRect(dim, Offset(0f, ft + fit.height), Size(size.width, size.height - ft - fit.height))
                    drawRect(dim, Offset(0f, ft), Size(fl, fit.height))
                    drawRect(dim, Offset(fl + fit.width, ft), Size(size.width - fl - fit.width, fit.height))
                    // Rule-of-thirds guides and the frame.
                    val guide = Color.White.copy(alpha = 0.35f)
                    for (i in 1..2) {
                        val x = fl + fit.width * i / 3f
                        val y = ft + fit.height * i / 3f
                        drawLine(guide, Offset(x, ft), Offset(x, ft + fit.height), strokeWidth = 1f)
                        drawLine(guide, Offset(fl, y), Offset(fl + fit.width, y), strokeWidth = 1f)
                    }
                    drawRect(Color.White, Offset(fl, ft), Size(fit.width, fit.height), style = Stroke(width = borderPx))
                }
            }
            if (saving) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
