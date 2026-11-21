package com.synka.studio

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

object ArkaShader {

    private const val INPUT_SHADER_NAME = "input"

    private const val AGSL_SOURCE = """
        uniform shader input;

        // Effective per-band saturation multipliers (solo + mute pre-resolved on CPU)
        // A = [RED, ORANGE, YELLOW, GREEN], B = [CYAN, BLUE, PURPLE, MAGENTA]
        uniform float4 uSatMultA;
        uniform float4 uSatMultB;

        // Per-band hue shift in normalized turns (deg / 360.0)
        uniform float4 uHueA;
        uniform float4 uHueB;

        // Per-band luminance shift in [-1.0, 1.0]
        uniform float4 uLumA;
        uniform float4 uLumB;

        // 5-zone Tone EQ in [-1.0, 1.0]: uToneA = [blacks, shadows, mids, highlights]
        uniform float4 uToneA;
        uniform float uToneWhites;

        // Dual tint globes in [-1.0, 1.0]: [shadowX, shadowY, highlightX, highlightY]
        uniform float4 uTintGlobes;

        // Global temperature & tint in [-1.0, 1.0]: [warmth, tint]
        uniform float2 uTempTint;

        // RGB white-balance trim in [-0.5, 0.5]
        uniform float3 uTrim;

        // Comfort & Optics in [0.0, 1.0]: [paper, subzeroDimmer]
        uniform float2 uOptics;

        const float PI = 3.14159265;

        // Asymmetric piecewise raised-cosine weight around centerDeg with circular wrap-around.
        // Adjacent bands share the exact inter-band span, guaranteeing Partition of Unity:
        // sum(w0..w7) == 1.0 everywhere on [0, 360) with C1 continuity.
        float bandWeight(float hDeg, float centerDeg, float leftSpan, float rightSpan) {
            float delta = mod(hDeg - centerDeg + 180.0, 360.0) - 180.0;
            float span = (delta < 0.0) ? leftSpan : rightSpan;
            float t = clamp(abs(delta) / span, 0.0, 1.0);
            return 0.5 + 0.5 * cos(PI * t);
        }

        // 32-bit float RGB -> HSL with strict zero-chroma NaN guards
        float3 rgbToHsl(float3 rgb) {
            float cMax = max(rgb.r, max(rgb.g, rgb.b));
            float cMin = min(rgb.r, min(rgb.g, rgb.b));
            float delta = cMax - cMin;
            float l = 0.5 * (cMax + cMin);

            float hasChroma = step(1e-5, delta);
            float safeDelta = max(delta, 1e-5);
            float denomS = max(1.0 - abs(2.0 * l - 1.0), 1e-5);
            float s = hasChroma * clamp(delta / denomS, 0.0, 1.0);

            float h = 0.0;
            if (delta > 1e-5) {
                if (cMax == rgb.r) {
                    h = mod((rgb.g - rgb.b) / safeDelta, 6.0);
                } else if (cMax == rgb.g) {
                    h = ((rgb.b - rgb.r) / safeDelta) + 2.0;
                } else {
                    h = ((rgb.r - rgb.g) / safeDelta) + 4.0;
                }
                h = fract(h / 6.0);
            }
            return float3(h, s, l);
        }

        // Branchless, division-free HSL -> RGB
        float3 hslToRgb(float3 hsl) {
            float h = fract(hsl.x);
            float s = clamp(hsl.y, 0.0, 1.0);
            float l = clamp(hsl.z, 0.0, 1.0);

            float c = (1.0 - abs(2.0 * l - 1.0)) * s;
            float m = l - 0.5 * c;
            float3 p = abs(mod(h * 6.0 + float3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0;
            float3 rgb = clamp(p, 0.0, 1.0);
            return clamp(rgb * c + float3(m), 0.0, 1.0);
        }

        // Maps 2D globe (x: cool..warm, y: magenta..teal-green) to a subtle RGB offset
        float3 globeTintVector(float2 xy) {
            return float3(
                xy.x,
                0.35 * xy.x + 0.65 * xy.y,
                -xy.x + 0.25 * xy.y
            );
        }

        half4 main(float2 coords) {
            // 1. Safe un-premultiply in 32-bit float (avoids fp16 FTZ subnormal underflow)
            half4 c = input.eval(coords);
            float a = clamp(float(c.a), 0.0, 1.0);
            float3 rgb = clamp(float3(c.rgb) / max(a, 0.0001), 0.0, 1.0);

            // 2. Unrolled 8-band circular raised-cosine HSL grading (zero dynamic array indexing)
            float3 hsl = rgbToHsl(rgb);
            float hDeg = hsl.x * 360.0;

            // Centers: 0, 30, 60, 120, 180, 240, 275, 320 deg
            float w0 = bandWeight(hDeg,   0.0, 40.0, 30.0); // RED
            float w1 = bandWeight(hDeg,  30.0, 30.0, 30.0); // ORANGE
            float w2 = bandWeight(hDeg,  60.0, 30.0, 60.0); // YELLOW
            float w3 = bandWeight(hDeg, 120.0, 60.0, 60.0); // GREEN
            float w4 = bandWeight(hDeg, 180.0, 60.0, 60.0); // CYAN
            float w5 = bandWeight(hDeg, 240.0, 60.0, 35.0); // BLUE
            float w6 = bandWeight(hDeg, 275.0, 35.0, 45.0); // PURPLE
            float w7 = bandWeight(hDeg, 320.0, 45.0, 40.0); // MAGENTA

            float satScale =
                w0 * uSatMultA.x + w1 * uSatMultA.y + w2 * uSatMultA.z + w3 * uSatMultA.w +
                w4 * uSatMultB.x + w5 * uSatMultB.y + w6 * uSatMultB.z + w7 * uSatMultB.w;

            float hueShift =
                w0 * uHueA.x + w1 * uHueA.y + w2 * uHueA.z + w3 * uHueA.w +
                w4 * uHueB.x + w5 * uHueB.y + w6 * uHueB.z + w7 * uHueB.w;

            float lumShift =
                w0 * uLumA.x + w1 * uLumA.y + w2 * uLumA.z + w3 * uLumA.w +
                w4 * uLumB.x + w5 * uLumB.y + w6 * uLumB.z + w7 * uLumB.w;

            // Gate hue/lum band edits by source chroma so neutral grays never inherit Red (H=0)
            float chromaGate = smoothstep(0.0, 0.12, hsl.y);
            // Parabolic lightness envelope protects pure black (L=0) and pure white (L=1)
            float lumEnvelope = 4.0 * hsl.z * (1.0 - hsl.z);

            float newH = fract(hsl.x + hueShift * chromaGate);
            float newS = clamp(hsl.y * satScale, 0.0, 1.0);
            float newL = clamp(hsl.z + lumShift * 0.25 * chromaGate * lumEnvelope, 0.0, 1.0);

            rgb = hslToRgb(float3(newH, newS, newL));

            // 3. 5-zone smoothstep Tone EQ (blacks, shadows, mids, highlights, whites)
            float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
            float zBlacks     = 1.0 - smoothstep(0.00, 0.22, luma);
            float zShadows    = smoothstep(0.02, 0.22, luma) * (1.0 - smoothstep(0.25, 0.50, luma));
            float zMids       = smoothstep(0.20, 0.45, luma) * (1.0 - smoothstep(0.55, 0.80, luma));
            float zHighlights = smoothstep(0.50, 0.75, luma) * (1.0 - smoothstep(0.78, 0.98, luma));
            float zWhites     = smoothstep(0.78, 1.00, luma);

            float toneDelta =
                zBlacks     * uToneA.x    * 0.15 +
                zShadows    * uToneA.y    * 0.20 +
                zMids       * uToneA.z    * 0.22 +
                zHighlights * uToneA.w    * 0.20 +
                zWhites     * uToneWhites * 0.15;

            rgb = clamp(rgb + float3(toneDelta), 0.0, 1.0);

            // 4. Dual Shadow / Highlight 2D tint globes + global warmth & tint
            float tintLuma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
            float shWeight = (1.0 - tintLuma) * (1.0 - tintLuma);
            float hiWeight = tintLuma * tintLuma;

            float3 shColor = globeTintVector(uTintGlobes.xy);
            float3 hiColor = globeTintVector(uTintGlobes.zw);

            float warm = uTempTint.x;
            float tint = uTempTint.y;
            float3 tempTintDelta = float3(
                warm * 0.14 + tint * 0.06,
                warm * 0.03 - tint * 0.10,
                -warm * 0.14 + tint * 0.06
            );

            rgb = clamp(
                rgb + shColor * (shWeight * 0.18) + hiColor * (hiWeight * 0.18) + tempTintDelta,
                0.0,
                1.0
            );

            // 5. Comfort & Optics: matte paper mode, RGB trim, and post-grade Subzero Dimmer
            float paper = uOptics.x;
            if (paper > 0.0001) {
                float pLuma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
                rgb = mix(rgb, float3(pLuma), paper * 0.35);
                rgb = rgb * (1.0 - paper * 0.08) + float3(paper * 0.055);
            }

            rgb = clamp(rgb * (float3(1.0) + uTrim), 0.0, 1.0);

            // Subzero dimmer caps attenuation at 85% so controls remain legible at 100%
            float dimGain = 1.0 - (uOptics.y * 0.85);
            rgb = clamp(rgb * dimGain, 0.0, 1.0);

            // 6. Re-premultiply by original alpha to preserve HWUI compositing invariants
            return half4(half3(rgb * a), half(a));
        }
    """

    // Lazily compiled once on the main thread; uniforms are snapshotted into each new RenderEffect.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private var cachedShader: RuntimeShader? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun getOrCreateShader(): RuntimeShader {
        return cachedShader ?: RuntimeShader(AGSL_SOURCE).also { cachedShader = it }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyToView(
        targetView: View,
        params: ArkaParams,
        bypass: Boolean = false
    ) {
        if (bypass) {
            clearFromView(targetView)
            return
        }

        val norm = params.normalized()
        val bands = norm.bands
        val anySolo = bands.any { it.solo }

        fun effectiveSatMult(index: Int): Float {
            val b = bands[index]
            if (b.mute) return 0f
            if (anySolo && !b.solo) return 0f
            return (1f + b.sat / 100f).coerceIn(0f, 2f)
        }

        val shader = getOrCreateShader()

        shader.setFloatUniform(
            "uSatMultA",
            effectiveSatMult(0),
            effectiveSatMult(1),
            effectiveSatMult(2),
            effectiveSatMult(3)
        )
        shader.setFloatUniform(
            "uSatMultB",
            effectiveSatMult(4),
            effectiveSatMult(5),
            effectiveSatMult(6),
            effectiveSatMult(7)
        )

        shader.setFloatUniform(
            "uHueA",
            bands[0].hue / 360f,
            bands[1].hue / 360f,
            bands[2].hue / 360f,
            bands[3].hue / 360f
        )
        shader.setFloatUniform(
            "uHueB",
            bands[4].hue / 360f,
            bands[5].hue / 360f,
            bands[6].hue / 360f,
            bands[7].hue / 360f
        )

        shader.setFloatUniform(
            "uLumA",
            bands[0].lum / 100f,
            bands[1].lum / 100f,
            bands[2].lum / 100f,
            bands[3].lum / 100f
        )
        shader.setFloatUniform(
            "uLumB",
            bands[4].lum / 100f,
            bands[5].lum / 100f,
            bands[6].lum / 100f,
            bands[7].lum / 100f
        )

        shader.setFloatUniform(
            "uToneA",
            norm.blacks / 100f,
            norm.shadows / 100f,
            norm.mids / 100f,
            norm.highlights / 100f
        )
        shader.setFloatUniform("uToneWhites", norm.whites / 100f)

        shader.setFloatUniform(
            "uTintGlobes",
            norm.shadowTintX,
            norm.shadowTintY,
            norm.highlightTintX,
            norm.highlightTintY
        )

        shader.setFloatUniform(
            "uTempTint",
            norm.warmth / 100f,
            norm.tint / 100f
        )

        shader.setFloatUniform(
            "uTrim",
            norm.trimR / 100f,
            norm.trimG / 100f,
            norm.trimB / 100f
        )

        shader.setFloatUniform(
            "uOptics",
            norm.paper / 100f,
            norm.subzeroDimmer / 100f
        )

        // Always re-create RenderEffect so HWUI snapshots the updated uniform buffer on RenderThread
        val effect = RenderEffect.createRuntimeShaderEffect(shader, INPUT_SHADER_NAME)
        targetView.setRenderEffect(effect)
        targetView.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun clearFromView(targetView: View) {
        targetView.setRenderEffect(null)
        targetView.invalidate()
    }
}
