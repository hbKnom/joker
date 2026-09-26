package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.ujhhgtg.wekit.utils.WeLogger
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "HomeSidePanelCardBackground"

/**
 * Absolute ceiling for a single decoded background, independent of the view size. The host WeChat
 * process only gets a 512 MB heap, so a pathological "view is huge" measurement (or a stray
 * multi-thousand-pixel target) must never turn into an equally huge decode.
 */
private const val HOME_SIDE_PANEL_BACKGROUND_MAX_DECODED_EDGE_PX = 4096

/**
 * Byte budget of the decoded-bitmap cache. Entries are evicted LRU-style, so a handful of card
 * backgrounds can stay resident without ever approaching the host heap limit.
 */
private const val HOME_SIDE_PANEL_BACKGROUND_CACHE_BYTES = 6 * 1024 * 1024

/**
 * Paints a user supplied card background image.
 *
 * The image is decoded with [BitmapFactory] using `inJustDecodeBounds` + `inSampleSize` sized to
 * the exact pixel box this composable is measured with, so the full resolution file is never held
 * in memory. Decoding happens on [Dispatchers.IO] and the result is remembered per
 * (path, width, height), so scrolling or recomposing never re-decodes the same file.
 *
 * A null [file] (no image configured, missing file, or a failed resolve) and an [alphaPercent] of
 * 0 both render nothing at all, which is what keeps the default card appearance untouched.
 */
@Composable
internal fun HomeSidePanelCardBackgroundImage(
    file: Path?,
    alphaPercent: Int,
    modifier: Modifier = Modifier,
) {
    val alpha = alphaPercent.coerceIn(
        HOME_SIDE_PANEL_BACKGROUND_ALPHA_MIN,
        HOME_SIDE_PANEL_BACKGROUND_ALPHA_MAX,
    ) / HOME_SIDE_PANEL_BACKGROUND_ALPHA_MAX.toFloat()
    if (file == null || alpha <= 0f) return
    val path = file.toString()
    // Callers pass an exact size (matchParentSize inside the card, or a fixed preview box), so the
    // measured constraints are the real on-screen pixels the bitmap has to be downsampled for.
    BoxWithConstraints(modifier = modifier) {
        val targetWidth = constraints.maxWidth
        val targetHeight = constraints.maxHeight
        // produceState is a remember keyed on (path, width, height): the same image at the same
        // size keeps its decoded frame across recompositions and LazyColumn scroll frames.
        val frame by produceState<ImageBitmap?>(null, path, targetWidth, targetHeight) {
            value = HomeSidePanelCardBackgroundDecoder.decode(path, targetWidth, targetHeight)
        }
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = alpha,
            )
        }
    }
}

internal object HomeSidePanelCardBackgroundDecoder {

    private val cache = object : LruCache<String, Bitmap>(HOME_SIDE_PANEL_BACKGROUND_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            value.allocationByteCount.coerceAtLeast(1)
    }

    suspend fun decode(path: String, targetWidth: Int, targetHeight: Int): ImageBitmap? {
        if (targetWidth <= 0 || targetHeight <= 0) return null
        val key = cacheKey(path, targetWidth, targetHeight)
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) return cached.asImageBitmap()
        val bitmap = withContext(Dispatchers.IO) {
            try {
                decodeSampled(path, targetWidth, targetHeight)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                WeLogger.w(TAG, "failed to decode card background '$path'", error)
                null
            }
        } ?: return null
        cache.put(key, bitmap)
        return bitmap.asImageBitmap()
    }

    private fun cacheKey(path: String, targetWidth: Int, targetHeight: Int): String =
        "$path@${targetWidth}x$targetHeight"

    private fun decodeSampled(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        // Bounds pass: reads only the header, never allocates the bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(sourceWidth, sourceHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return null
        return applyExifOrientation(path, decoded)
    }

    /**
     * Largest power-of-two [BitmapFactory.Options.inSampleSize] that still decodes at or above the
     * requested display size, so the decoder (not a later scale) does the shrinking and the result
     * is never softer than the view.
     */
    private fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        while (
            sourceWidth / sample > HOME_SIDE_PANEL_BACKGROUND_MAX_DECODED_EDGE_PX ||
            sourceHeight / sample > HOME_SIDE_PANEL_BACKGROUND_MAX_DECODED_EDGE_PX
        ) {
            sample *= 2
        }
        return sample
    }

    /**
     * BitmapFactory ignores EXIF, so a photo taken in portrait would otherwise be painted on its
     * side. Rotation/flip is baked into a new bitmap and the pre-rotation pixels are released
     * immediately because nothing else references them yet.
     */
    private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        val oriented = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (oriented !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return oriented
    }
}
