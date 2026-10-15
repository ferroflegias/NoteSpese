package com.expensepereport.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

@Composable
fun CropImageDialog(
    sourceUri: Uri,
    onCropSuccess: (Uri) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(sourceUri) {
        bitmap = loadOrientedBitmap(context, sourceUri)
    }

    val loadedBitmap = bitmap
    if (loadedBitmap == null) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.padding(16.dp)
            ) {
                Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    var cropLeft by remember { mutableFloatStateOf(0.05f) }
    var cropTop by remember { mutableFloatStateOf(0.05f) }
    var cropRight by remember { mutableFloatStateOf(0.95f) }
    var cropBottom by remember { mutableFloatStateOf(0.95f) }

    var activeHandle by remember { mutableStateOf<Handle?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "✂️ Seleziona area ricevuta",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(8.dp))

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val containerWidth = constraints.maxWidth.toFloat()
                    val containerHeight = constraints.maxHeight.toFloat()

                    val imageBitmap = remember(loadedBitmap) { loadedBitmap.asImageBitmap() }

                    val bitmapAspect = loadedBitmap.width.toFloat() / loadedBitmap.height.toFloat()
                    val containerAspect = containerWidth / containerHeight

                    val drawWidth: Float
                    val drawHeight: Float
                    val offsetX: Float
                    val offsetY: Float

                    if (bitmapAspect > containerAspect) {
                        drawWidth = containerWidth
                        drawHeight = containerWidth / bitmapAspect
                        offsetX = 0f
                        offsetY = (containerHeight - drawHeight) / 2f
                    } else {
                        drawHeight = containerHeight
                        drawWidth = containerHeight * bitmapAspect
                        offsetX = (containerWidth - drawWidth) / 2f
                        offsetY = 0f
                    }

                    val leftPx = offsetX + cropLeft * drawWidth
                    val topPx = offsetY + cropTop * drawHeight
                    val rightPx = offsetX + cropRight * drawWidth
                    val bottomPx = offsetY + cropBottom * drawHeight

                    val currentLeftPx by rememberUpdatedState(leftPx)
                    val currentTopPx by rememberUpdatedState(topPx)
                    val currentRightPx by rememberUpdatedState(rightPx)
                    val currentBottomPx by rememberUpdatedState(bottomPx)
                    val currentDrawWidth by rememberUpdatedState(drawWidth)
                    val currentDrawHeight by rememberUpdatedState(drawHeight)

                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { touchOffset ->
                                        val handleRadius = 90f
                                        val x = touchOffset.x
                                        val y = touchOffset.y

                                        val l = currentLeftPx
                                        val t = currentTopPx
                                        val r = currentRightPx
                                        val b = currentBottomPx

                                        activeHandle = when {
                                            (x - l)*(x - l) + (y - t)*(y - t) <= handleRadius * handleRadius -> Handle.TOP_LEFT
                                            (x - r)*(x - r) + (y - t)*(y - t) <= handleRadius * handleRadius -> Handle.TOP_RIGHT
                                            (x - l)*(x - l) + (y - b)*(y - b) <= handleRadius * handleRadius -> Handle.BOTTOM_LEFT
                                            (x - r)*(x - r) + (y - b)*(y - b) <= handleRadius * handleRadius -> Handle.BOTTOM_RIGHT
                                            x in l..r && y in t..b -> Handle.CENTER
                                            else -> null
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val dw = currentDrawWidth
                                        val dh = currentDrawHeight
                                        if (dw <= 0f || dh <= 0f) return@detectDragGestures

                                        val dxRel = dragAmount.x / dw
                                        val dyRel = dragAmount.y / dh

                                        val minSize = 0.1f

                                        when (activeHandle) {
                                            Handle.TOP_LEFT -> {
                                                cropLeft = min(cropRight - minSize, max(0f, cropLeft + dxRel))
                                                cropTop = min(cropBottom - minSize, max(0f, cropTop + dyRel))
                                            }
                                            Handle.TOP_RIGHT -> {
                                                cropRight = max(cropLeft + minSize, min(1f, cropRight + dxRel))
                                                cropTop = min(cropBottom - minSize, max(0f, cropTop + dyRel))
                                            }
                                            Handle.BOTTOM_LEFT -> {
                                                cropLeft = min(cropRight - minSize, max(0f, cropLeft + dxRel))
                                                cropBottom = max(cropTop + minSize, min(1f, cropBottom + dyRel))
                                            }
                                            Handle.BOTTOM_RIGHT -> {
                                                cropRight = max(cropLeft + minSize, min(1f, cropRight + dxRel))
                                                cropBottom = max(cropTop + minSize, min(1f, cropBottom + dyRel))
                                            }
                                            Handle.CENTER -> {
                                                val w = cropRight - cropLeft
                                                val h = cropBottom - cropTop

                                                var newLeft = cropLeft + dxRel
                                                var newTop = cropTop + dyRel

                                                if (newLeft < 0f) newLeft = 0f
                                                if (newTop < 0f) newTop = 0f
                                                if (newLeft + w > 1f) newLeft = 1f - w
                                                if (newTop + h > 1f) newTop = 1f - h

                                                cropLeft = newLeft
                                                cropTop = newTop
                                                cropRight = newLeft + w
                                                cropBottom = newTop + h
                                            }
                                            null -> {}
                                        }
                                    },
                                    onDragEnd = { activeHandle = null },
                                    onDragCancel = { activeHandle = null }
                                )
                            }
                    ) {
                        drawImage(
                            image = imageBitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(drawWidth.toInt(), drawHeight.toInt())
                        )

                        val path = Path().apply {
                            addRect(Rect(0f, 0f, size.width, size.height))
                            addRect(Rect(leftPx, topPx, rightPx, bottomPx))
                            fillType = PathFillType.EvenOdd
                        }
                        drawPath(path, Color.Black.copy(alpha = 0.6f))

                        drawRect(
                            color = Color.Green,
                            topLeft = Offset(leftPx, topPx),
                            size = Size(rightPx - leftPx, bottomPx - topPx),
                            style = Stroke(width = 3.dp.toPx())
                        )

                        val handleRadius = 14.dp.toPx()
                        drawCircle(Color.Green, radius = handleRadius, center = Offset(leftPx, topPx))
                        drawCircle(Color.Green, radius = handleRadius, center = Offset(rightPx, topPx))
                        drawCircle(Color.Green, radius = handleRadius, center = Offset(leftPx, bottomPx))
                        drawCircle(Color.Green, radius = handleRadius, center = Offset(rightPx, bottomPx))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Annulla", color = Color.White)
                    }

                    Button(
                        onClick = {
                            try {
                                val origW = loadedBitmap.width
                                val origH = loadedBitmap.height

                                val x = (cropLeft * origW).toInt().coerceIn(0, origW - 1)
                                val y = (cropTop * origH).toInt().coerceIn(0, origH - 1)
                                val w = ((cropRight - cropLeft) * origW).toInt().coerceIn(1, origW - x)
                                val h = ((cropBottom - cropTop) * origH).toInt().coerceIn(1, origH - y)

                                val croppedBitmap = Bitmap.createBitmap(loadedBitmap, x, y, w, h)

                                val outputFile = File.createTempFile("cropped_rec_", ".jpg", context.cacheDir).apply {
                                    createNewFile()
                                    deleteOnExit()
                                }
                                val fos = FileOutputStream(outputFile)
                                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                                fos.flush()
                                fos.close()

                                val croppedUri = Uri.fromFile(outputFile)
                                onCropSuccess(croppedUri)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                onDismiss()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("✂️ Conferma")
                    }
                }
            }
        }
    }
}

private fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (bitmap == null) return null

        val exifStream = context.contentResolver.openInputStream(uri)
        val exif = exifStream?.let { ExifInterface(it) }
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        ) ?: ExifInterface.ORIENTATION_UNDEFINED
        exifStream?.close()

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }

        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private enum class Handle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
}
