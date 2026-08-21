package com.abel.photoo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 竖向「快速滚动」拖动手柄。叠在 LazyColumn / LazyVerticalGrid 右侧即可：
 * - 平时显示一条细轨道 + 圆角滑块，跟随当前滚动位置；
 * - 拖动滑块时按百分比 scrollToItem 跳到对应位置，并弹出气泡标签（可选）。
 *
 * @param fraction  当前滚动进度 0f..1f（非拖动时由调用方根据列表位置算出）
 * @param enabled   内容是否可滚动（不可滚动时不显示）
 * @param onScrub   用户把手柄拖到 fraction 位置时回调，内部据此跳转
 * @param bubble    拖动时显示的气泡文本；null 不显示
 */
@Composable
fun FastScroller(
    modifier: Modifier = Modifier,
    fraction: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    bubble: String? = null,
) {
    if (!enabled) return
    val density = LocalDensity.current
    val trackW = 4.dp
    val thumbW = 10.dp
    val thumbH = 56.dp
    val hitW = 44.dp

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

    Box(
        modifier = modifier
            .width(hitW)
            .fillMaxHeight()
            .pointerInput(enabled) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        val y = change.position.y
                        val frac = if (trackHeight > 0) (y / trackHeight).coerceIn(0f, 1f) else 0f
                        dragFraction = frac
                        onScrub(frac)
                    },
                )
            }
            .onGloballyPositioned { trackHeight = it.size.height },
    ) {
        // 轨道
        Box(
            Modifier
                .align(Alignment.Center)
                .width(trackW)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
        )
        // 滑块
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbTop) }
                .width(thumbW)
                .height(thumbH)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    if (dragging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    },
                ),
        )
        // 气泡：拖动时显示当前位置标签（位于滑块左侧，垂直居中对齐滑块）
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

/** 给 LazyVerticalGrid 用的快速滚动手柄。 */
@Composable
fun LazyGridFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
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
    FastScroller(
        modifier = modifier,
        fraction = fraction,
        enabled = true,
        onScrub = { f -> scope.launch { state.scrollToItem((f * denom).roundToInt()) } },
        bubble = bubble,
    )
}

/** 给 LazyColumn 用的快速滚动手柄。 */
@Composable
fun LazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
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
    FastScroller(
        modifier = modifier,
        fraction = fraction,
        enabled = true,
        onScrub = { f -> scope.launch { state.scrollToItem((f * denom).roundToInt()) } },
        bubble = bubble,
    )
}
