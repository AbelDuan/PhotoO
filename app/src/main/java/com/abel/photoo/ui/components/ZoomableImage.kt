package com.abel.photoo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.abel.photoo.model.GestureDirection
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 支持双指缩放、拖动、双击放大，以及四方向滑动手势的图片容器。
 *
 * 手势没有直接套 detectTransformGestures，因为它无法区分
 * "单指滑动"和"缩放后的平移"，也没有手势结束回调 —— 而滑动手势恰恰
 * 需要在抬手那一刻判断位移是否够。所以这里自己写了一遍事件循环：
 *   · 两指 → 缩放 + 平移
 *   · 一指且已放大 → 平移
 *   · 一指且未放大 → 锁定主轴后才接管，触发对应方向的手势回调
 *
 * 水平方向默认不接管（[horizontalEnabled] = false），留给外层的翻页器，
 * 因为原生 Pager 的跟手翻页手感比自己模拟的好得多。只有用户把左右手势
 * 改成了翻页以外的动作时，外层才会关掉 Pager 滚动并把水平交给这里。
 */
@Composable
fun ZoomableImage(
    model: Any,
    contentDescription: String?,
    onTap: () -> Unit,
    onSwipe: (GestureDirection) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    /** 预览用缩略图：先以它秒出画面，全图加载完成后叠在上面淡入，翻页不再空等。 */
    thumbModel: Any? = null,
    /** 灵敏度系数：越大越容易触发（阈值除以它）。 */
    sensitivity: Float = 1f,
    /** 水平滑动是否由本组件接管。 */
    horizontalEnabled: Boolean = false,
    /** 哪些方向触发后要做"整张图飞出淡出"的动画（退出 / 删除这类动作）。 */
    flyOut: (GestureDirection) -> Boolean = { false },
    /** 飞出动画刚开始时回调（在延时真正处理之前），用于立刻停掉 Live 等副作用。 */
    onFlyStart: (GestureDirection) -> Unit = {},
    /**
     * 盖在图片之上的内容（Live Photo 视频层）。
     *
     * 刻意放在带手势的 Box 内部而不是外面：Compose 的事件会先给最上层的子节点，
     * 子节点不消费时再交给祖先，而 VideoView.onTouchEvent 恒返回 false，
     * 所以视频播放时四方向手势依然照常工作。
     */
    overlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var exiting by remember { mutableStateOf(false) }
    var exitDir by remember { mutableStateOf(GestureDirection.UP) }
    val scope = rememberCoroutineScope()

    // 翻页之后必须复位，否则下一张会继承上一张的缩放状态。
    LaunchedEffect(resetKey) {
        scale = 1f
        offset = Offset.Zero
        dragX = 0f
        dragY = 0f
        exiting = false
        onZoomChanged(false)
    }

    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    val animScale by animateFloatAsState(scale, spring(), label = "scale")
    // 松手后整张图朝手势方向飞出并淡出，给出明确的"已处理"反馈；动画结束后才真正回调。
    val flyProgress by animateFloatAsState(
        targetValue = if (exiting) 3700f else 0f,
        animationSpec = tween(240),
        label = "flyProgress",
    )
    val flyX = if (exiting && exitDir.isHorizontal) exitDir.sign * flyProgress else 0f
    val flyY = if (exiting && !exitDir.isHorizontal) exitDir.sign * flyProgress else 0f
    val displayX = dragX + flyX
    val displayY = dragY + flyY
    // 滑动时整张图跟着走并逐渐变淡，给一个"要被处理掉了"的直觉反馈。
    val travel = maxOf(abs(displayX), abs(displayY))
    val dragAlpha = (1f - (travel / 900f).coerceIn(0f, 1f)).coerceIn(0f, 1f)

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
            .pointerInput(horizontalEnabled, sensitivity) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var axis = Axis.NONE
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
                            if (axis == Axis.NONE) {
                                if (abs(totalY) > slop && abs(totalY) > abs(totalX) * 1.5f) {
                                    axis = Axis.VERTICAL
                                } else if (
                                    horizontalEnabled &&
                                    abs(totalX) > slop &&
                                    abs(totalX) > abs(totalY) * 1.5f
                                ) {
                                    axis = Axis.HORIZONTAL
                                }
                            }
                            when (axis) {
                                Axis.VERTICAL -> {
                                    dragY += pan.y
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                Axis.HORIZONTAL -> {
                                    dragX += pan.x
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                Axis.NONE -> Unit
                            }
                        }
                    } while (pressed)

                    val factor = sensitivity.coerceAtLeast(0.2f)
                    val dir = when (axis) {
                        Axis.VERTICAL -> {
                            val threshold = size.height * 0.16f / factor
                            when {
                                dragY < -threshold -> GestureDirection.UP
                                dragY > threshold -> GestureDirection.DOWN
                                else -> null
                            }
                        }
                        Axis.HORIZONTAL -> {
                            val threshold = size.width * 0.22f / factor
                            when {
                                dragX < -threshold -> GestureDirection.LEFT
                                dragX > threshold -> GestureDirection.RIGHT
                                else -> null
                            }
                        }
                        Axis.NONE -> null
                    }
                    if (dir != null) {
                        if (flyOut(dir)) {
                            // 用独立协程延时后再真正回调，避免动画被打断
                            // （仓库层已是内存隐藏，无需整库刷新）。
                            exitDir = dir
                            exiting = true
                            onFlyStart(dir)
                            scope.launch { kotlinx.coroutines.delay(240); onSwipe(dir) }
                        } else {
                            dragX = 0f
                            dragY = 0f
                            onSwipe(dir)
                        }
                    } else {
                        dragX = 0f
                        dragY = 0f
                    }
                    if (scale <= 1.02f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animScale
                    scaleY = animScale
                    translationX = offset.x + displayX
                    translationY = offset.y + displayY
                    alpha = dragAlpha
                },
        ) {
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        overlay?.invoke(this)
    }
}

private enum class Axis { NONE, VERTICAL, HORIZONTAL }

private val GestureDirection.isHorizontal: Boolean
    get() = this == GestureDirection.LEFT || this == GestureDirection.RIGHT

/** 飞出方向：上/左为负，下/右为正。 */
private val GestureDirection.sign: Float
    get() = when (this) {
        GestureDirection.UP, GestureDirection.LEFT -> -1f
        GestureDirection.DOWN, GestureDirection.RIGHT -> 1f
    }

/** 放大后限制平移范围，避免把图片拖出屏幕外只剩黑边。 */
private fun Offset.clampTo(scale: Float, width: Float, height: Float): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
}
