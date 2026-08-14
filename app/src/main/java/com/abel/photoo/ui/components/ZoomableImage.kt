package com.abel.photoo.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
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
 *
 * 删除/退出的"飞出"动画统一为：拖动时图片**不透明**跟手（不再提前变淡），
 * 松手超过阈值后按屏高比例飞出 + 轻微旋转 + 同步淡出；未达阈值则弹簧回弹。
 * 这样普通照片、Live 静帧删除时观感一致，不会出现"半透明幽灵"或黑屏割裂。
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
    /** 飞出方向已确定、动画刚开始时回调：父层据此立刻移除/退出当前照片，
     *  由父层负责渲染"飞出"副本，这样下一张照片能无缝跟上、不再等动画播完。
     *  第二个参数为手指松手瞬间图片的位移，飞出副本从同一位置起步，避免回弹跳变；
     *  第三个参数是已经解码好的图片画笔，副本直接复用它画图，避免重新加载造成的闪白/卡顿。 */
    onFlyConfirm: (GestureDirection, Offset, Painter?) -> Unit = { _, _, _ -> },
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
    // 手指拖动位移用 Animatable：松手未达阈值时能弹簧弹回，而不是硬生生跳回原位。
    val dragX = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    var exiting by remember { mutableStateOf(false) }
    var exitDir by remember { mutableStateOf(GestureDirection.UP) }
    // 已经解码好的主图画笔：飞出副本直接复用它画图，避免重新加载造成闪白/卡顿。
    var capturedPainter by remember { mutableStateOf<Painter?>(null) }
    val scope = rememberCoroutineScope()

    // 翻页之后必须复位，否则下一张会继承上一张的缩放状态。
    LaunchedEffect(resetKey) {
        scale = 1f
        offset = Offset.Zero
        dragX.snapTo(0f)
        dragY.snapTo(0f)
        exiting = false
        onZoomChanged(false)
    }

    LaunchedEffect(scale) { onZoomChanged(scale > 1.01f) }

    val animScale by animateFloatAsState(scale, spring(), label = "scale")
    // 飞出进度 0..1：松手后整张图朝手势方向飞出并淡出（动画结束后才真正回调删除/退出）。
    // 位移按"屏高/屏宽的比例"计算，任何屏幕都能干净地飞出画面，不再用固定像素值。
    val flyProgress by animateFloatAsState(
        targetValue = if (exiting) 1f else 0f,
        animationSpec = tween(260, easing = FastOutLinearInEasing),
        label = "flyProgress",
    )

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
                                    // AwaitPointerEventScope 是受限协程，不能直接调 Animatable 的挂起成员，
                                    // 借 rememberCoroutineScope 的协程同步 snapTo（无动画，跟手无延迟感）。
                                    scope.launch { dragY.snapTo(dragY.value + pan.y) }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                Axis.HORIZONTAL -> {
                                    scope.launch { dragX.snapTo(dragX.value + pan.x) }
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
                                dragY.value < -threshold -> GestureDirection.UP
                                dragY.value > threshold -> GestureDirection.DOWN
                                else -> null
                            }
                        }
                        Axis.HORIZONTAL -> {
                            val threshold = size.width * 0.22f / factor
                            when {
                                dragX.value < -threshold -> GestureDirection.LEFT
                                dragX.value > threshold -> GestureDirection.RIGHT
                                else -> null
                            }
                        }
                        Axis.NONE -> null
                    }
                    if (dir != null) {
                        if (flyOut(dir)) {
                            // 飞出动画刚开始就通知父层：父层立刻移除/退出当前照片，
                            // 并负责渲染"飞出"副本（见 ViewerScreen 的 FlyingPhoto）。
                            // 本组件不再播自身的飞出动画（exiting 保持 false），
                            // 停在原位把位置交给 FlyingPhoto 从同一手指位移无缝接手，
                            // 因此不再有"先回弹到中心再飞出去"的卡顿跳变。
                            exitDir = dir
                            onFlyStart(dir)
                            onFlyConfirm(dir, Offset(dragX.value, dragY.value), capturedPainter)
                        } else {
                            scope.launch {
                                dragX.animateTo(0f, spring())
                                dragY.animateTo(0f, spring())
                            }
                            onSwipe(dir)
                        }
                    } else {
                        // 没到阈值：弹簧弹回原位，比瞬间跳回自然得多。
                        scope.launch {
                            dragX.animateTo(0f, spring())
                            dragY.animateTo(0f, spring())
                        }
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
                    // 拖动阶段：图片不透明跟手（alpha 恒 1），避免"半透明幽灵"观感；
                    // 飞出阶段：按屏高/屏宽比例位移 + 淡出 + 轻微旋转，所有照片动画一致。
                    translationX = offset.x + dragX.value +
                        if (exiting && exitDir.isHorizontal) {
                            exitDir.sign * flyProgress * size.width * 1.3f
                        } else 0f
                    translationY = offset.y + dragY.value +
                        if (exiting && !exitDir.isHorizontal) {
                            exitDir.sign * flyProgress * size.height * 1.3f
                        } else 0f
                    alpha = if (exiting) (1f - flyProgress).coerceIn(0f, 1f) else 1f
                    rotationZ = if (exiting) exitDir.sign * 10f * flyProgress else 0f
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
            val mainPainter = rememberAsyncImagePainter(model = model)
            capturedPainter = mainPainter
            Image(
                painter = mainPainter,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            // Live Photo 视频层放进"会跟手平移"的盒子内部，竖向滑动时整张（含视频）
            // 一起随手指移动，不会出现"视频留在原位、静态图飞走"的分裂观感。
            overlay?.invoke(this)
        }
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
