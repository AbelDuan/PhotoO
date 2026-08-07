package com.abel.photoo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlin.math.abs

/**
 * 支持双指缩放、拖动、双击放大，以及"上滑删除 / 下滑关闭"的图片容器。
 *
 * 手势没有直接套 detectTransformGestures，因为它无法区分
 * "单指竖直滑动"和"缩放后的平移"，也没有手势结束回调 —— 而上滑删除恰恰
 * 需要在抬手那一刻判断位移是否够。所以这里自己写了一遍事件循环：
 *   · 两指 → 缩放 + 平移
 *   · 一指且已放大 → 平移
 *   · 一指且未放大 → 竖直方向锁定后才接管，水平方向留给外层的翻页器
 */
@Composable
fun ZoomableImage(
    model: Any,
    contentDescription: String?,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }

    // 翻页之后必须复位，否则下一张会继承上一张的缩放状态。
    LaunchedEffect(resetKey) {
        scale = 1f
        offset = Offset.Zero
        dragY = 0f
        onZoomChanged(false)
    }

    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    val animScale by animateFloatAsState(scale, spring(), label = "scale")
    // 上滑时整张图跟着走并逐渐变淡，给一个"要被丢掉了"的直觉反馈。
    val dragAlpha = (1f - abs(dragY) / 900f).coerceIn(0.35f, 1f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.6f
                            // 以双击点为中心放大，而不是死板地从中心放大。
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            offset = Offset(
                                (cx - tap.x) * (scale - 1f),
                                (cy - tap.y) * (scale - 1f),
                            ).clampTo(scale, size.width.toFloat(), size.height.toFloat())
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var verticalLocked = false
                    var totalX = 0f
                    var totalY = 0f
                    var pressed: Boolean

                    do {
                        val event = awaitPointerEvent()
                        val down = event.changes.count { it.pressed }
                        pressed = down > 0
                        if (down >= 2) {
                            multiTouch = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                offset = (offset + pan)
                                    .clampTo(scale, size.width.toFloat(), size.height.toFloat())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (scale > 1.01f) {
                            val pan = event.calculatePan()
                            if (pan != Offset.Zero) {
                                offset = (offset + pan)
                                    .clampTo(scale, size.width.toFloat(), size.height.toFloat())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (!multiTouch) {
                            val pan = event.calculatePan()
                            totalX += pan.x
                            totalY += pan.y
                            if (!verticalLocked &&
                                abs(totalY) > slop &&
                                abs(totalY) > abs(totalX) * 1.5f
                            ) {
                                verticalLocked = true
                            }
                            if (verticalLocked) {
                                dragY += pan.y
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (pressed)

                    if (verticalLocked) {
                        val threshold = size.height * 0.16f
                        when {
                            dragY < -threshold -> onSwipeUp()
                            dragY > threshold -> onSwipeDown()
                        }
                        dragY = 0f
                    }
                    if (scale <= 1.02f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animScale
                    scaleY = animScale
                    translationX = offset.x
                    translationY = offset.y + dragY
                    alpha = dragAlpha
                },
        )
    }
}

/** 放大后限制平移范围，避免把图片拖出屏幕外只剩黑边。 */
private fun Offset.clampTo(scale: Float, width: Float, height: Float): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}
