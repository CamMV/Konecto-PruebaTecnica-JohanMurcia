package com.konecto.series76.ui.components.background

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Purple bubble background of the mockup. GPU (AGSL) on Android 13+, otherwise the same function rendered on the CPU
 * into a cached bitmap, in parallel stripes on Dispatchers.Default (never on the main thread).
 */
@Composable
fun BubbleBackground(params: BubbleParams, modifier: Modifier = Modifier) {
    key(params) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslBubbles(params, modifier)
        else BitmapBubbles(params, modifier)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslBubbles(params: BubbleParams, modifier: Modifier) {
    val shader = remember { RuntimeShader(BubbleShader.AGSL) }
    Box(
        modifier.drawWithCache {
            BubbleShader.uniforms(params, size.width, size.height).forEach { (name, v) ->
                when (v.size) {
                    1 -> shader.setFloatUniform(name, v[0])
                    2 -> shader.setFloatUniform(name, v[0], v[1])
                    3 -> shader.setFloatUniform(name, v[0], v[1], v[2])
                    else -> shader.setFloatUniform(name, v[0], v[1], v[2], v[3])
                }
            }
            val brush = ShaderBrush(shader)
            onDrawBehind { drawRect(brush) }
        },
    )
}

@Composable
private fun BitmapBubbles(params: BubbleParams, modifier: Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, params, size) {
        if (size != IntSize.Zero) value = BubbleBitmapCache.get(params, size)
    }
    Box(
        modifier
            .onSizeChanged { size = it }
            .drawBehind {
                val image = bitmap
                if (image != null) {
                    drawImage(
                        image,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
                        filterQuality = FilterQuality.Low,
                    )
                } else {
                    // Until the bitmap is ready: just the background ramp (instant).
                    val stops = params.background.map { it.position to Color(it.r, it.g, it.b, it.a) }.toTypedArray()
                    drawRect(Brush.verticalGradient(*stops))
                }
            },
    )
}

/** Renders at half resolution (the image is soft, upscaling is invisible) and caches per size + preset. */
private object BubbleBitmapCache {
    private const val SCALE = 0.5f
    private val cache = LruCache<Pair<BubbleParams, IntSize>, ImageBitmap>(6)

    suspend fun get(params: BubbleParams, size: IntSize): ImageBitmap {
        val key = params to size
        cache.get(key)?.let { return it }
        val rendered = render(params, (size.width * SCALE).toInt().coerceAtLeast(1), (size.height * SCALE).toInt().coerceAtLeast(1))
        cache.put(key, rendered)
        return rendered
    }

    private suspend fun render(params: BubbleParams, w: Int, h: Int): ImageBitmap = withContext(Dispatchers.Default) {
        val pixels = IntArray(w * h)
        val stripes = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val rowsPerStripe = (h + stripes - 1) / stripes
        coroutineScope {
            (0 until stripes).map { s ->
                async {
                    val from = s * rowsPerStripe
                    val to = minOf(h, from + rowsPerStripe)
                    for (y in from until to) for (x in 0 until w) pixels[y * w + x] = BubbleShader.shade(params, x, y, w, h)
                }
            }.awaitAll()
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
