package com.synka.studio.display.shader

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.synka.studio.display.model.DisplayEqState

@Composable
fun Modifier.synkaDisplayEqGpu(state: DisplayEqState): Modifier {
    if (!state.enabled || state.isNeutral()) return this

    var layerSize by remember { mutableStateOf(IntSize.Zero) }

    val composeEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { ArkaDisplayShader.createRuntimeShader() }
        remember(shader, state, layerSize) {
            ArkaDisplayShader.bindUniforms(
                shader = shader,
                state = state,
                widthPx = layerSize.width.toFloat(),
                heightPx = layerSize.height.toFloat()
            )
            RenderEffect.createRuntimeShaderEffect(shader, "srcContent")
                .asComposeRenderEffect()
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        remember(state) {
            ArkaDisplayShader.createApi31FallbackEffect(state).asComposeRenderEffect()
        }
    } else {
        null
    }

    return this
        .onSizeChanged { layerSize = it }
        .graphicsLayer {
            renderEffect = composeEffect
        }
}
