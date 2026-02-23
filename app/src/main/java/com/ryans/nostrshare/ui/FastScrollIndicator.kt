package com.ryans.nostrshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ryans.nostrshare.NostrUtils
import com.ryans.nostrshare.ProcessTextViewModel
import com.ryans.nostrshare.HistoryUiModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun FastScrollIndicator(
    lazyListState: LazyListState,
    history: List<HistoryUiModel>,
    vm: ProcessTextViewModel,
    modifier: Modifier = Modifier
) {
    if (history.isEmpty()) return

    // Force complete state reset when user changes
    key(vm.pubkey) {
        val density = LocalDensity.current
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val scope = rememberCoroutineScope()

        var isDragging by remember { mutableStateOf(false) }
        var containerHeightPx by remember { mutableFloatStateOf(0f) }
        var dragY by remember { mutableFloatStateOf(0f) }

        // Derived states to minimize recomposition overhead
        val thumbHeightPx by remember(containerHeightPx, history) {
            derivedStateOf {
                val layoutInfo = lazyListState.layoutInfo
                val visibleItems = layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty() || containerHeightPx == 0f) {
                    with(density) { 48.dp.toPx() }
                } else {
                    val totalItems = history.size
                    val visibleItemsCount = visibleItems.size
                    val proportion = visibleItemsCount.toFloat() / totalItems.toFloat().coerceAtLeast(1f)
                    (containerHeightPx * proportion).coerceIn(with(density) { 40.dp.toPx() }, containerHeightPx)
                }
            }
        }

        val visualThumbOffsetPx by remember(containerHeightPx, isDragging, history) {
            derivedStateOf {
                if (isDragging) {
                    dragY
                } else {
                    if (containerHeightPx == 0f || history.isEmpty()) 0f else {
                        val totalItems = history.size
                        if (totalItems <= 1) 0f else {
                            val firstVisibleIndex = lazyListState.firstVisibleItemIndex
                            val scrollAreaHeight = containerHeightPx - thumbHeightPx
                            val proportion = firstVisibleIndex.toFloat() / (totalItems - 1).toFloat().coerceAtLeast(1f)
                            (proportion * scrollAreaHeight).coerceIn(0f, scrollAreaHeight)
                        }
                    }
                }
            }
        }

        val currentScrollDate by remember(history) {
            derivedStateOf {
                if (history.isEmpty() || containerHeightPx <= 0f) "" else {
                    val scrollAreaHeight = containerHeightPx - thumbHeightPx
                    val index = if (isDragging && scrollAreaHeight > 0) {
                        val proportion = dragY / scrollAreaHeight
                        (proportion * (history.size - 1)).roundToInt().coerceIn(0, history.size - 1)
                    } else {
                        lazyListState.firstVisibleItemIndex.coerceIn(0, history.size - 1)
                    }
                    val item = history[index]
                    NostrUtils.formatDate(item.timestamp, "MMM d, yyyy")
                }
            }
        }

        Box(
            modifier = modifier
                .fillMaxHeight()
                .width(32.dp)
                .padding(top = 8.dp, bottom = 80.dp)
                .onGloballyPositioned { containerHeightPx = it.size.height.toFloat() }
                .pointerInput(history, containerHeightPx) { // Re-bind when history changes
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragY = offset.y.coerceIn(0f, (containerHeightPx - thumbHeightPx).coerceAtLeast(0f))
                            if (vm.isHapticEnabled()) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            
                            val scrollAreaHeight = containerHeightPx - thumbHeightPx
                            if (scrollAreaHeight > 0) {
                                dragY = (dragY + dragAmount).coerceIn(0f, scrollAreaHeight)
                                
                                val proportion = dragY / scrollAreaHeight
                                val totalItems = history.size
                                val targetIndex = (proportion * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
                                
                                scope.launch {
                                    lazyListState.scrollToItem(targetIndex, 0)
                                }
                            }
                        }
                    )
                }
        ) {
            // Thumb
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, visualThumbOffsetPx.roundToInt()) }
                    .align(Alignment.TopEnd)
                    .width(12.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDragging) 1f else 0.5f))
            )

            // Date Popup
            if (isDragging) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(
                        x = with(density) { -(48.dp.toPx()).toInt() }, 
                        y = (visualThumbOffsetPx + (thumbHeightPx / 2)).toInt() - with(density) { 28.dp.toPx().toInt() }
                    ),
                    properties = PopupProperties(focusable = false)
                ) {
                    Surface(
                        modifier = Modifier.width(180.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 12.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = currentScrollDate,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
