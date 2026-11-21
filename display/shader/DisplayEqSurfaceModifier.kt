package com.synkastudio.display.shader

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
import com.synkastudio.display.model.DisplayEqState

/**
 * Applies full Tier 3 GPU Display EQ to Compose previews, images, or TextureView-backed video.
 *
 * CRITICAL MEDIA3 / EXOPLAYER REQUIREMENT:
 *   Never attach this modifier to a SurfaceView (SurfaceView bypasses HWUI RenderNodes).
 *   Always configure Media3 PlayerView with app:surface_type="texture_view" or Compose
 *   PlayerSurface(surfaceType = SURFACE_TYPE_TEXTURE_VIEW) / AndroidEmbeddedExternalSurface.
 */
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
