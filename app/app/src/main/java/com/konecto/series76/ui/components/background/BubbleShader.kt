package com.konecto.series76.ui.components.background

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of the Figma WGSL fragment shader "Burbujas violetas" (static, not animated).
 *  - [AGSL]: GPU version for Android 13+ (RuntimeShader). `fwidth` doesn't exist in AGSL, so the antialiasing width is
 *    computed from the pixel size in the shader's coordinate space (same value fwidth gives for this function).
 *  - [shade]: the same math in Kotlin for Android 8–12, rendered to a Bitmap off the main thread.
 */
object BubbleShader {

    /** Bubble layout table of the original shader (x, y offsets and relative radius). */
    private val LAYOUT = arrayOf(
        floatArrayOf(-0.51f, -0.19f, 1.12f), floatArrayOf(-0.18f, -0.05f, 0.78f), floatArrayOf(0.22f, 0.20f, 1.03f),
        floatArrayOf(0.54f, -0.10f, 0.86f), floatArrayOf(-0.04f, 0.55f, 0.66f), floatArrayOf(-0.39f, 0.48f, 0.54f),
        floatArrayOf(0.42f, 0.63f, 0.60f), floatArrayOf(0.06f, -0.29f, 0.72f), floatArrayOf(-0.30f, 0.83f, 0.44f),
        floatArrayOf(0.29f, 0.92f, 0.39f), floatArrayOf(-0.54f, 0.17f, 0.43f), floatArrayOf(0.56f, 0.34f, 0.47f),
    )

    const val AGSL: String = """
uniform float2 uResolution;
uniform float4 uBg0; uniform float4 uBg1; uniform float4 uBg2; uniform float4 uBg3;
uniform float4 uBgPos; uniform float uBgCount;
uniform float4 uBb0; uniform float4 uBb1; uniform float4 uBb2; uniform float4 uBb3;
uniform float4 uBbPos; uniform float uBbCount;
uniform float uTransitionPosition; uniform float uTransitionSoftness; uniform float uGradientAngle;
uniform float uBubbleCount; uniform float uBubbleSize; uniform float2 uBubbleOrigin; uniform float uUpperExtent;
uniform float uBubbleOpacity; uniform float uEdgeSoftness; uniform float uGlowIntensity;

float4 ramp(float t, float4 c0, float4 c1, float4 c2, float4 c3, float4 p, float n) {
  if (n < 1.5 || t <= p.x) { return c0; }
  if (n < 2.5) { if (t >= p.y) { return c1; } return mix(c0, c1, (t - p.x) / max(p.y - p.x, 1e-5)); }
  if (t <= p.y) { return mix(c0, c1, (t - p.x) / max(p.y - p.x, 1e-5)); }
  if (n < 3.5) { if (t >= p.z) { return c2; } return mix(c1, c2, (t - p.y) / max(p.z - p.y, 1e-5)); }
  if (t <= p.z) { return mix(c1, c2, (t - p.y) / max(p.z - p.y, 1e-5)); }
  if (t >= p.w) { return c3; }
  return mix(c2, c3, (t - p.z) / max(p.w - p.z, 1e-5));
}

float3 bubbleLayout(int i) {
  if (i == 0) { return float3(-0.51, -0.19, 1.12); }
  if (i == 1) { return float3(-0.18, -0.05, 0.78); }
  if (i == 2) { return float3(0.22, 0.20, 1.03); }
  if (i == 3) { return float3(0.54, -0.10, 0.86); }
  if (i == 4) { return float3(-0.04, 0.55, 0.66); }
  if (i == 5) { return float3(-0.39, 0.48, 0.54); }
  if (i == 6) { return float3(0.42, 0.63, 0.60); }
  if (i == 7) { return float3(0.06, -0.29, 0.72); }
  if (i == 8) { return float3(-0.30, 0.83, 0.44); }
  if (i == 9) { return float3(0.29, 0.92, 0.39); }
  if (i == 10) { return float3(-0.54, 0.17, 0.43); }
  return float3(0.56, 0.34, 0.47);
}

half4 main(float2 fragCoord) {
  float2 dims = max(uResolution, float2(1.0));
  float2 uv = fragCoord / dims;
  float transition = clamp(0.5 + (uv.y - uTransitionPosition * 0.01) / max(uTransitionSoftness * 0.01, 0.01), 0.0, 1.0);
  float4 result = ramp(transition, uBg0, uBg1, uBg2, uBg3, uBgPos, uBgCount);
  if (uBubbleOpacity > 0.0) {
    float minDim = max(min(dims.x, dims.y), 1.0);
    float2 space = dims / minDim;
    float2 p = uv * space;
    float theta = uGradientAngle * 0.01745329252;
    float2 axis = float2(cos(theta), sin(theta));
    float2 origin = uBubbleOrigin * 0.01;
    float region = uUpperExtent * 0.01;
    for (int i = 0; i < 12; i++) {
      if (float(i) >= uBubbleCount) { break; }
      float3 a = bubbleLayout(i);
      float2 centerUv = origin + float2(a.x, a.y * region);
      float radius = max(uBubbleSize * 0.01 * a.z, 0.001);
      float2 local = (p - centerUv * space) / radius;
      float dist = length(local);
      float aa = max(1.41421 / (minDim * radius), 0.001);
      float edge = max(uEdgeSoftness, aa);
      float disk = 1.0 - smoothstep(1.0 - edge, 1.0 + edge, dist);
      float gradientT = clamp(dot(local, axis) * 0.5 + 0.5, 0.0, 1.0);
      float4 bubble = ramp(gradientT, uBb0, uBb1, uBb2, uBb3, uBbPos, uBbCount);
      float outside = max(dist - 1.0, 0.0);
      float halo = exp(-outside * outside / 0.12) * (1.0 - disk) * uGlowIntensity * 0.20;
      float rimDistance = (dist - 0.88) / 0.10;
      float rim = exp(-rimDistance * rimDistance) * 0.10;
      float bodyAlpha = clamp(disk * uBubbleOpacity * bubble.a, 0.0, 1.0);
      float haloAlpha = clamp(halo * uBubbleOpacity * bubble.a, 0.0, 1.0);
      float haloOutAlpha = haloAlpha + result.a * (1.0 - haloAlpha);
      float3 haloPremul = bubble.rgb * haloAlpha + result.rgb * result.a * (1.0 - haloAlpha);
      float3 litBubble = clamp(bubble.rgb + float3(rim * uGlowIntensity), float3(0.0), float3(1.0));
      float outAlpha = bodyAlpha + haloOutAlpha * (1.0 - bodyAlpha);
      float3 straightRgb = (litBubble * bodyAlpha + haloPremul * (1.0 - bodyAlpha)) / max(outAlpha, 0.00001);
      result = float4(straightRgb, outAlpha);
    }
  }
  return half4(result.rgb * result.a, result.a);
}
"""

    /** Uniform values for [AGSL], shared by the GPU path (and handy to test). */
    fun uniforms(params: BubbleParams, width: Float, height: Float): Map<String, FloatArray> = buildMap {
        put("uResolution", floatArrayOf(width, height))
        putRamp("uBg", params.background)
        putRamp("uBb", params.bubbles)
        put("uTransitionPosition", floatArrayOf(params.transitionPosition))
        put("uTransitionSoftness", floatArrayOf(params.transitionSoftness))
        put("uGradientAngle", floatArrayOf(params.gradientAngle))
        put("uBubbleCount", floatArrayOf(params.bubbleCount.toFloat()))
        put("uBubbleSize", floatArrayOf(params.bubbleSize))
        put("uBubbleOrigin", floatArrayOf(params.bubbleOriginX, params.bubbleOriginY))
        put("uUpperExtent", floatArrayOf(params.upperExtent))
        put("uBubbleOpacity", floatArrayOf(params.bubbleOpacity))
        put("uEdgeSoftness", floatArrayOf(params.edgeSoftness))
        put("uGlowIntensity", floatArrayOf(params.glowIntensity))
    }

    private fun MutableMap<String, FloatArray>.putRamp(prefix: String, stops: List<RampStop>) {
        for (i in 0 until 4) {
            val s = stops.getOrElse(i) { stops.last() }
            put("$prefix$i", floatArrayOf(s.r, s.g, s.b, s.a))
        }
        put("${prefix}Pos", FloatArray(4) { stops.getOrElse(it) { stops.last() }.position })
        put("${prefix}Count", floatArrayOf(stops.size.toFloat()))
    }

    // ------------------------------------------------------------------ CPU version (same math)

    /** Color of pixel (x, y) of a [width]×[height] surface as a non-premultiplied ARGB int. */
    fun shade(params: BubbleParams, x: Int, y: Int, width: Int, height: Int): Int {
        val w = max(width.toFloat(), 1f)
        val h = max(height.toFloat(), 1f)
        val u = (x + 0.5f) / w
        val v = (y + 0.5f) / h
        val out = FloatArray(4)
        val transition = (0.5f + (v - params.transitionPosition * 0.01f) / max(params.transitionSoftness * 0.01f, 0.01f))
            .coerceIn(0f, 1f)
        ramp(params.background, transition, out)
        var r = out[0]; var g = out[1]; var b = out[2]; var a = out[3]
        if (params.bubbleOpacity > 0f) {
            val minDim = max(min(w, h), 1f)
            val sx = w / minDim
            val sy = h / minDim
            val px = u * sx
            val py = v * sy
            val theta = params.gradientAngle * 0.01745329252f
            val ax = cos(theta)
            val ay = sin(theta)
            val ox = params.bubbleOriginX * 0.01f
            val oy = params.bubbleOriginY * 0.01f
            val region = params.upperExtent * 0.01f
            val bubble = FloatArray(4)
            for (i in 0 until min(params.bubbleCount, LAYOUT.size)) {
                val l = LAYOUT[i]
                val cx = ox + l[0]
                val cy = oy + l[1] * region
                val radius = max(params.bubbleSize * 0.01f * l[2], 0.001f)
                val lx = (px - cx * sx) / radius
                val ly = (py - cy * sy) / radius
                val dist = sqrt(lx * lx + ly * ly)
                val aa = max(1.41421f / (minDim * radius), 0.001f)
                val edge = max(params.edgeSoftness, aa)
                val disk = 1f - smoothstep(1f - edge, 1f + edge, dist)
                val gradientT = ((lx * ax + ly * ay) * 0.5f + 0.5f).coerceIn(0f, 1f)
                ramp(params.bubbles, gradientT, bubble)
                val outside = max(dist - 1f, 0f)
                val halo = exp(-outside * outside / 0.12f) * (1f - disk) * params.glowIntensity * 0.20f
                val rimDistance = (dist - 0.88f) / 0.10f
                val rim = exp(-rimDistance * rimDistance) * 0.10f
                val bodyAlpha = (disk * params.bubbleOpacity * bubble[3]).coerceIn(0f, 1f)
                val haloAlpha = (halo * params.bubbleOpacity * bubble[3]).coerceIn(0f, 1f)
                val haloOutAlpha = haloAlpha + a * (1f - haloAlpha)
                val hr = bubble[0] * haloAlpha + r * a * (1f - haloAlpha)
                val hg = bubble[1] * haloAlpha + g * a * (1f - haloAlpha)
                val hb = bubble[2] * haloAlpha + b * a * (1f - haloAlpha)
                val lit = rim * params.glowIntensity
                val outAlpha = bodyAlpha + haloOutAlpha * (1f - bodyAlpha)
                val div = max(outAlpha, 0.00001f)
                r = (min(bubble[0] + lit, 1f) * bodyAlpha + hr * (1f - bodyAlpha)) / div
                g = (min(bubble[1] + lit, 1f) * bodyAlpha + hg * (1f - bodyAlpha)) / div
                b = (min(bubble[2] + lit, 1f) * bodyAlpha + hb * (1f - bodyAlpha)) / div
                a = outAlpha
            }
        }
        return (to8(a) shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }

    private fun to8(c: Float): Int = (c.coerceIn(0f, 1f) * 255f).roundToInt()

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun ramp(stops: List<RampStop>, t: Float, out: FloatArray) {
        val first = stops.first()
        val last = stops.last()
        val stop = when {
            stops.size == 1 || t <= first.position -> first
            t >= last.position -> last
            else -> null
        }
        if (stop != null) {
            out[0] = stop.r; out[1] = stop.g; out[2] = stop.b; out[3] = stop.a
            return
        }
        for (i in 0 until stops.size - 1) {
            val p0 = stops[i]
            val p1 = stops[i + 1]
            if (t <= p1.position) {
                val f = (t - p0.position) / max(p1.position - p0.position, 1e-5f)
                out[0] = p0.r + (p1.r - p0.r) * f
                out[1] = p0.g + (p1.g - p0.g) * f
                out[2] = p0.b + (p1.b - p0.b) * f
                out[3] = p0.a + (p1.a - p0.a) * f
                return
            }
        }
    }
}
