package com.synka.studio

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import org.intellij.lang.annotations.Language

object ArkaShader {

    @Language("AGSL")
    private const val ARKA_AGSL_SOURCE = """
        uniform shader input;
        uniform float uWarmth;
        uniform float uSaturation;
        uniform float uBypass;

        vec4 main(vec2 fragCoord) {
            vec4 src = input.eval(fragCoord);

            if (uBypass > 0.5) {
                return src;
            }

            float alpha = clamp(src.a, 0.0, 1.0);
            if (alpha <= 0.0001) {
                return vec4(0.0, 0.0, 0.0, alpha);
            }

            // Un-premultiply straight RGB before color grading
            vec3 rgb = clamp(src.rgb / alpha, 0.0, 1.0);

            // Warmth adjustment (-1.0 cool -> +1.0 warm)
            float w = clamp(uWarmth, -1.0, 1.0);
            vec3 warmTint = vec3(w * 0.12, w * 0.03, -w * 0.14);
            rgb = clamp(rgb + warmTint, 0.0, 1.0);

            // Saturation adjustment (0.0 grayscale -> 2.0 vivid) using Rec. 709 luma
            float sat = clamp(uSaturation, 0.0, 2.0);
            float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
            rgb = clamp(mix(vec3(luma), rgb, sat), 0.0, 1.0);

            // Re-multiply alpha for premultiplied Android View / RenderEffect compositing
            return vec4(rgb * alpha, alpha);
        }
    """

    private val runtimeShader: RuntimeShader by lazy {
        RuntimeShader(ARKA_AGSL_SOURCE)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun applyToView(
        targetView: View,
        warmth: Float,
        saturation: Float,
        bypass: Boolean
    ) {
        runtimeShader.setFloatUniform("uWarmth", warmth.coerceIn(-1f, 1f))
        runtimeShader.setFloatUniform("uSaturation", saturation.coerceIn(0f, 2f))
        runtimeShader.setFloatUniform("uBypass", if (bypass) 1f else 0f)

        val renderEffect = RenderEffect.createRuntimeShaderEffect(runtimeShader, "input")
        targetView.setRenderEffect(renderEffect)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun clearFromView(targetView: View) {
        targetView.setRenderEffect(null)
    }
}
