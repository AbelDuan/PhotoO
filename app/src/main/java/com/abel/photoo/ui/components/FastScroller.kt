package com.abel.photoo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 竖向「快速滚动」拖动手柄（小米相册风格，纯浮层 overlay）。
 * - 平时完全隐藏，不占任何布局空间、不挤压内容；
 * - 列表滚动或用户拖拽时才淡入；松手约 1.2s 后自动淡出；
 * - 握柄为中性灰半透明圆角 pill（带握纹），浮在内容之上，不撑满全高、不常驻。
 *
 * @param fraction    当前滚动进度 0f..1f（非拖动时由调用方根据列表位置算出）
 * @param enabled     内容是否可滚动（不可滚动时不显示）
 * @param visible     是否应可见（由包装层根据滚动/拖拽活动计算，带淡入淡出）
 * @param onScrub     用户把手柄拖到 fraction 位置时回调，内部据此跳转
 * @param onInteract  用户开始/结束拖拽或拖动时回调，用于重置自动隐藏计时
 * @param bubble      拖动时显示的气泡文本；null 不显示
 */
@Composable
fun FastScroller(
    modifier: Modifier = Modifier,
    fraction: Float,
    enabled: Boolean,
    visible: Boolean,
    onScrub: (Float) -> Unit,
    onInteract: () -> Unit = {},
    bubble: String? = null,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val hitW = 40.dp
    val thumbW = 22.dp
    val thumbH = 52.dp
    val gripW = 12.dp
    val gripH = 2.dp

    var trackHeight by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    var bubbleW by remember { mutableStateOf(0) }
    var bubbleH by remember { mutableStateOf(0) }

    val thumbHpx = with(density) { thumbH.roundToPx() }
    val progress = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)
    val thumbTop = if (trackHeight > 0) {
        ((trackHeight - thumbHpx) * progress).coerceAtLeast(0f).roundToInt()
    } else 0

    // 淡入淡出：滚动或拖拽时浮现，松手后淡出。
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "fastScrollerAlpha",
    )

    // 命中区域常驻（透明），以便隐藏时也能在右缘拖拽唤醒手柄。
    Box(
        modifier = modifier
            .width(hitW)
            .fillMaxHeight()
            .pointerInput(enabled) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragging = true
                        onInteract()
                    },
                    onDragEnd = {
                        dragging = false
                        onInteract()
                    },
                    onDragCancel = {
                        dragging = false
                        onInteract()
                    },
                    onVerticalDrag = { change, _ ->
                        val y = change.position.y
                        val frac = if (trackHeight > 0) (y / trackHeight).coerceIn(0f, 1f) else 0f
                        dragFraction = frac
                        onInteract()
                        onScrub(frac)
                    },
                )
            }
            .onGloballyPositioned { trackHeight = it.size.height },
    ) {
        // 拖拽时显示一条极淡的短轨道，作为位置参照（不贯穿全高，仅作点缀）。
        if (dragging) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            )
        }
        // 握柄：中性灰半透明圆角 pill，拖拽时略深。
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbTop) }
                .width(thumbW)
                .height(thumbH)
                .alpha(alpha)
                .clip(RoundedCornerShape(thumbW / 2))
                .background(
                    if (dragging) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                ),
        ) {
            // 握纹：居中一条细亮线，强化「可抓取」观感（参考小米相册）。
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(gripW)
                    .height(gripH)
                    .clip(RoundedCornerShape(gripH / 2))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
            )
        }
        // 气泡：拖动时显示当前位置标签（位于握柄左侧，垂直居中对齐握柄）。
        if (dragging && bubble != null) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        IntOffset(
                            x = -(bubbleW + 12.dp.roundToPx()),
                            y = thumbTop + (thumbHpx - bubbleH).coerceAtLeast(0) / 2,
                        )
                    }
                    .alpha(alpha)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 11.dp, vertical = 6.dp)
                    .onGloballyPositioned {
                        bubbleW = it.size.width
                        bubbleH = it.size.height
                    },
            ) {
                Text(
                    bubble,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 给 LazyVerticalGrid 用的快速滚动手柄（纯浮层，不挤压内容）。
 *
 * 可见性由本函数统一管理：列表滚动中或用户拖拽时浮现，停手 1.2s 后淡出。
 * 调用处需把手柄放在一个 Box 内（与滚动内容同级、靠后绘制），并用
 * `Modifier.align(Alignment.End).fillMaxHeight()` 定位到右侧内容区。
 */
@Composable
fun LazyGridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier.fillMaxHeight(),
    bubble: String? = null,
) {
    val layout = state.layoutInfo
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo
    if (visible.isEmpty()) return
    val scrollable = state.firstVisibleItemIndex > 0 ||
        state.firstVisibleItemIndex + visible.size < total
    if (!scrollable) return
    val denom = if (total > 1) total - 1 else 1
    val fraction = (state.firstVisibleItemIndex.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()

    var isVisible by remember { mutableStateOf(false) }
    var lastActive by remember { mutableStateOf(0L) }
    fun poke() { lastActive = System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            isVisible = (System.currentTimeMillis() - lastActive < 1200) || state.isScrollInProgress
        }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) poke()
    }

    Box(Modifier.fillMaxSize()) {
        FastScroller(
            modifier = modifier,
            fraction = fraction,
            enabled = true,
            visible = isVisible,
            onInteract = { poke() },
            onScrub = { f -> scope.launch { state.scrollToItem((f * denom).roundToInt()) } },
            bubble = bubble,
        )
    }
}

/**
 * 给 LazyColumn 用的快速滚动手柄（纯浮层，不挤压内容）。
 *
 * 可见性由本函数统一管理：列表滚动中或用户拖拽时浮现，停手 1.2s 后淡出。
 * 调用处需把手柄放在一个 Box 内（与滚动内容同级、靠后绘制），并用
 * `Modifier.align(Alignment.End).fillMaxHeight()` 定位到右侧内容区。
 */
@Composable
fun LazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier.fillMaxHeight(),
    bubble: String? = null,
) {
    val layout = state.layoutInfo
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo
    if (visible.isEmpty()) return
    val scrollable = state.firstVisibleItemIndex > 0 ||
        state.firstVisibleItemIndex + visible.size < total
    if (!scrollable) return
    val denom = if (total > 1) total - 1 else 1
    val fraction = (state.firstVisibleItemIndex.toFloat() / denom.toFloat()).coerceIn(0f, 1f)
    val scope = rememberCoroutineScope()

    var isVisible by remember { mutableStateOf(false) }
    var lastActive by remember { mutableStateOf(0L) }
    fun poke() { lastActive = System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(150)
            isVisible = (System.currentTimeMillis() - lastActive < 1200) || state.isScrollInProgress
        }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) poke()
    }

    Box(Modifier.fillMaxSize()) {
        FastScroller(
            modifier = modifier,
            fraction = fraction,
            enabled = true,
            visible = isVisible,
            onInteract = { poke() },
            onScrub = { f -> scope.launch { state.scrollToItem((f * denom).roundToInt()) } },
            bubble = bubble,
        )
    }
}
