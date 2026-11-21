package com.synkastudio.display.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.synkastudio.display.engine.DisplayEqEngine
import java.lang.ref.WeakReference

class DisplayOverlayAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var serviceRef: WeakReference<DisplayOverlayAccessibilityService>? = null

        fun currentInstance(): DisplayOverlayAccessibilityService? = serviceRef?.get()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef = WeakReference(this)
        DisplayEqEngine.getInstance(applicationContext).reapplyNow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately empty: canRetrieveWindowContent="false", zero screen content inspected.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        serviceRef = null
        DisplayEqEngine.getInstance(applicationContext).reapplyNow()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceRef = null
        super.onDestroy()
    }
}
http://googleusercontent.com/visual_element_2kotlin
package com.synkastudio.display.shader

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import com.synkastudio.display.model.DisplayEqState
import kotlin.math.pow

object ArkaDisplayShader {

    /**
     * Audited AGSL fragment shader for API 33+ (Android 13-16).
     * - Zero GLSL/SkSL/AGSL reserved keywords (verified against Skia M128 rules).
     * - Per-sample premultiplied alpha safety (eliminates transparent-edge sharpening halos).
     * - Exact C1-smooth 8-channel HSL partition of unity with neutral gray gating.
     * - Zero loops; zero NaN/Inf across alpha=[0..1] and all extreme slider corners.
     */
    const val AGSL_SOURCE: String = """
        uniform shader srcContent;
        uniform float2 uResolution;
        uniform float4 uToneA;    // x: exposureEV, y: contrast, z: gamma, w: blacks
        uniform float4 uToneB;    // x: shadows,    y: highlights, z: whites, w: dimStrength
        uniform float4 uMood;     // x: temperature, y: tint, z: saturation, w: vibrance
        uniform float3 uRgbGain;  // r, g, b channel gains
        uniform float2 uOptics;   // x: sharpen, y: vignette
        uniform float4 uHslHueA;  // Red, Orange, Yellow, Green
        uniform float4 uHslHueB;  // Aqua, Blue, Purple, Magenta
        uniform float4 uHslSatA;
        uniform float4 uHslSatB;
        uniform float4 uHslLumA;
        uniform float4 uHslLumB;

        float3 arkaSafeUnpremul(half4 px) {
            float a = float(px.a);
            return (a > 0.0001) ? clamp(float3(px.rgb) / a, 0.0, 1.0) : float3(0.0);
        }

        float3 arkaRgbToHsl(float3 rgb) {
            float maxCh = max(rgb.r, max(rgb.g, rgb.b));
            float minCh = min(rgb.r, min(rgb.g, rgb.b));
            float lum = 0.5 * (maxCh + minCh);
            float delta = maxCh - minCh;
            if (delta <= 0.00001) {
                return float3(0.0, 0.0, lum);
            }
            float denomS = max(1.0 - abs(2.0 * lum - 1.0), 0.00001);
            float sat = clamp(delta / denomS, 0.0, 1.0);
            float hue = 0.0;
            if (maxCh == rgb.r) {
                hue = (rgb.g - rgb.b) / delta;
                if (rgb.g < rgb.b) {
                    hue += 6.0;
                }
            } else if (maxCh == rgb.g) {
                hue = ((rgb.b - rgb.r) / delta) + 2.0;
            } else {
                hue = ((rgb.r - rgb.g) / delta) + 4.0;
            }
            return float3(fract(hue / 6.0), sat, lum);
        }

        float3 arkaHslToRgb(float3 hsl) {
            float3 hueRgb = clamp(
                abs(fract(float3(hsl.x) + float3(0.0, 0.6666667, 0.3333333)) * 6.0 - 3.0) - 1.0,
                0.0,
                1.0
            );
            float chroma = (1.0 - abs(2.0 * hsl.z - 1.0)) * hsl.y;
            return clamp((hueRgb - 0.5) * chroma + hsl.z, 0.0, 1.0);
        }

        float arkaTriWeight(float hVal, float cCurr, float spanL, float spanR) {
            float d = fract(hVal - cCurr + 0.5) - 0.5;
            float t = (d < 0.0)
                ? clamp(1.0 + d / spanL, 0.0, 1.0)
                : clamp(1.0 - d / spanR, 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }

        float3 arkaApplyGrade(float3 c) {
            // 1. Exposure (EV) & White Balance + RGB Gains
            c = c * exp2(uToneA.x);
            float3 wbGain = float3(
                1.0 + 0.28 * uMood.x + 0.08 * uMood.y,
                1.0 - 0.14 * uMood.y,
                1.0 - 0.28 * uMood.x + 0.08 * uMood.y
            ) * uRgbGain;
            c = clamp(c * wbGain, 0.0, 1.0);

            // 2. Gamma (strictly positive pow domain + exact true-black preservation)
            float invGamma = 1.0 / clamp(uToneA.z, 0.4, 2.5);
            float3 safeC = max(c, float3(0.0000001));
            float3 gammaC = pow(safeC, float3(invGamma));
            c = mix(float3(0.0), gammaC, step(float3(0.000001), c));

            // 3. Contrast around 0.5 pivot
            float contrastSlope = 1.0 + uToneA.y * 0.65;
            c = clamp((c - 0.5) * contrastSlope + 0.5, 0.0, 1.0);

            // 4. 4-Zone Tone EQ (Blacks, Shadows, Highlights, Whites)
            float lumVal = dot(c, float3(0.2126, 0.7152, 0.0722));
            float mBlacks = 1.0 - smoothstep(0.0, 0.25, lumVal);
            float mShadows = 1.0 - smoothstep(0.0, 0.55, lumVal);
            float mHighlights = smoothstep(0.45, 1.0, lumVal);
            float mWhites = smoothstep(0.75, 1.0, lumVal);
            float toneShift = uToneA.w * 0.18 * mBlacks
                + uToneB.x * 0.28 * mShadows
                + uToneB.y * 0.28 * mHighlights
                + uToneB.z * 0.18 * mWhites;
            c = clamp(c + float3(toneShift), 0.0, 1.0);

            // 5. 8-Channel HSL with exact partition-of-unity basis & gray saturation gate
            float3 hslVal = arkaRgbToHsl(c);
            float satGate = smoothstep(0.02, 0.12, hslVal.y);
            if (satGate > 0.0001) {
                float h0 = hslVal.x;
                float4 wVecA = float4(
                    arkaTriWeight(h0, 0.0,       0.1111111, 0.0833333), // Red 0 deg
                    arkaTriWeight(h0, 0.0833333, 0.0833333, 0.0833333), // Orange 30 deg
                    arkaTriWeight(h0, 0.1666667, 0.0833333, 0.1666667), // Yellow 60 deg
                    arkaTriWeight(h0, 0.3333333, 0.1666667, 0.1666667)  // Green 120 deg
                );
                float4 wVecB = float4(
                    arkaTriWeight(h0, 0.5000000, 0.1666667, 0.1666667), // Aqua 180 deg
                    arkaTriWeight(h0, 0.6666667, 0.1666667, 0.1111111), // Blue 240 deg
                    arkaTriWeight(h0, 0.7777778, 0.1111111, 0.1111111), // Purple 280 deg
                    arkaTriWeight(h0, 0.8888889, 0.1111111, 0.1111111)  // Magenta 320 deg
                );
                float dHue = (dot(wVecA, uHslHueA) + dot(wVecB, uHslHueB)) * 0.0833333; // +/- 30 deg
                float dSat = dot(wVecA, uHslSatA) + dot(wVecB, uHslSatB);
                float dLum = dot(wVecA, uHslLumA) + dot(wVecB, uHslLumB);

                hslVal.x = fract(hslVal.x + dHue * satGate);
                hslVal.y = clamp(hslVal.y * (1.0 + dSat * satGate), 0.0, 1.0);
                hslVal.z = clamp(hslVal.z + dLum * 0.25 * satGate, 0.0, 1.0);
                c = arkaHslToRgb(hslVal);
            }

            // 6. Vibrance + Global Saturation
            float lumOut = dot(c, float3(0.2126, 0.7152, 0.0722));
            float curSat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
            float vibGain = 1.0 + uMood.w * (1.0 - curSat) * 0.8;
            float satGain = 1.0 + uMood.z;
            c = clamp(mix(float3(lumOut), c, satGain * vibGain), 0.0, 1.0);

            // 7. OLED-safe black dimmer
            c = c * (1.0 - clamp(uToneB.w, 0.0, 0.72));
            return c;
        }

        half4 main(float2 fragCoord) {
            half4 rawPx = srcContent.eval(fragCoord);
            float alphaVal = float(rawPx.a);
            if (alphaVal <= 0.0001) {
                return half4(0.0);
            }
            float3 baseRgb = arkaSafeUnpremul(rawPx);

            // Optional 4-tap unsharp mask (skipped cleanly when sharpen == 0)
            float sharpAmt = uOptics.x;
            if (sharpAmt > 0.001) {
                float2 maxCoord = max(uResolution - float2(0.5), float2(0.5));
                float3 pxUp = arkaSafeUnpremul(srcContent.eval(clamp(fragCoord + float2(0.0, -1.0), float2(0.5), maxCoord)));
                float3 pxDown = arkaSafeUnpremul(srcContent.eval(clamp(fragCoord + float2(0.0, 1.0), float2(0.5), maxCoord)));
                float3 pxLeft = arkaSafeUnpremul(srcContent.eval(clamp(fragCoord + float2(-1.0, 0.0), float2(0.5), maxCoord)));
                float3 pxRight = arkaSafeUnpremul(srcContent.eval(clamp(fragCoord + float2(1.0, 0.0), float2(0.5), maxCoord)));
                float3 blurAvg = (pxUp + pxDown + pxLeft + pxRight) * 0.25;
                baseRgb = clamp(baseRgb + (baseRgb - blurAvg) * (sharpAmt * 1.4), 0.0, 1.0);
            }

            float3 gradedRgb = arkaApplyGrade(baseRgb);

            // Optional radial vignette (pure black edges)
            if (uOptics.y > 0.001 && uResolution.x > 1.0 && uResolution.y > 1.0) {
                float2 uvNorm = (fragCoord / uResolution) * 2.0 - 1.0;
                float vigDist = length(uvNorm);
                float vigMask = 1.0 - smoothstep(0.65, 1.45, vigDist) * (uOptics.y * 0.65);
                gradedRgb *= clamp(vigMask, 0.0, 1.0);
            }

            return half4(half3(clamp(gradedRgb, 0.0, 1.0) * alphaVal), half(alphaVal));
        }
    """.trimIndent()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createRuntimeShader(): RuntimeShader = RuntimeShader(AGSL_SOURCE)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun bindUniforms(
        shader: RuntimeShader,
        state: DisplayEqState,
        widthPx: Float,
        heightPx: Float
    ) {
        val s = state.sanitized()
        shader.setFloatUniform("uResolution", widthPx.coerceAtLeast(1f), heightPx.coerceAtLeast(1f))
        shader.setFloatUniform("uToneA", s.exposure, s.contrast, s.gamma, s.blacks)
        shader.setFloatUniform("uToneB", s.shadows, s.highlights, s.whites, s.dimStrength)
        shader.setFloatUniform("uMood", s.temperature, s.tint, s.saturation, s.vibrance)
        shader.setFloatUniform("uRgbGain", s.redGain, s.greenGain, s.blueGain)
        shader.setFloatUniform("uOptics", s.sharpen, s.vignette)

        shader.setFloatUniform("uHslHueA", s.hslHue[0], s.hslHue[1], s.hslHue[2], s.hslHue[3])
        shader.setFloatUniform("uHslHueB", s.hslHue[4], s.hslHue[5], s.hslHue[6], s.hslHue[7])
        shader.setFloatUniform("uHslSatA", s.hslSat[0], s.hslSat[1], s.hslSat[2], s.hslSat[3])
        shader.setFloatUniform("uHslSatB", s.hslSat[4], s.hslSat[5], s.hslSat[6], s.hslSat[7])
        shader.setFloatUniform("uHslLumA", s.hslLum[0], s.hslLum[1], s.hslLum[2], s.hslLum[3])
        shader.setFloatUniform("uHslLumB", s.hslLum[4], s.hslLum[5], s.hslLum[6], s.hslLum[7])
    }

    /** Graceful GPU fallback for Android 12 (API 31-32) where AGSL RuntimeShader is unavailable */
    @RequiresApi(Build.VERSION_CODES.S)
    fun createApi31FallbackEffect(state: DisplayEqState): RenderEffect {
        val s = state.sanitized()
        val cm = ColorMatrix()

        val expScale = 2.0.pow(s.exposure.toDouble()).toFloat().coerceIn(0.25f, 2.0f)
        val wbR = (1f + 0.28f * s.temperature + 0.08f * s.tint).coerceIn(0.4f, 1.4f)
        val wbG = (1f - 0.14f * s.tint).coerceIn(0.4f, 1.4f)
        val wbB = (1f - 0.28f * s.temperature + 0.08f * s.tint).coerceIn(0.4f, 1.4f)
        cm.setScale(wbR * s.redGain * expScale, wbG * s.greenGain * expScale, wbB * s.blueGain * expScale, 1f)

        val slope = 1f + s.contrast * 0.65f
        val offset = 127.5f * (1f - slope) // ColorMatrix offsets operate in [0..255]
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    slope, 0f, 0f, 0f, offset,
                    0f, slope, 0f, 0f, offset,
                    0f, 0f, slope, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        val satMatrix = ColorMatrix().apply { setSaturation((1f + s.saturation).coerceIn(0f, 2f)) }
        cm.postConcat(satMatrix)

        val dimScale = (1f - s.dimStrength).coerceIn(0.25f, 1.0f)
        val dimMatrix = ColorMatrix().apply { setScale(dimScale, dimScale, dimScale, 1f) }
        cm.postConcat(dimMatrix)

        return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(cm))
    }
}
