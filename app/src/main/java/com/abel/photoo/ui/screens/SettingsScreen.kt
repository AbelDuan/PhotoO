package com.abel.photoo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import com.abel.photoo.data.prefs.ThemeMode
import com.abel.photoo.model.GestureAction
import com.abel.photoo.model.GestureDirection
import com.abel.photoo.model.GestureSensitivity
import com.abel.photoo.model.KeepStrategy
import com.abel.photoo.model.SimilarityLevel
import com.abel.photoo.ui.PhotoOViewModel
import com.abel.photoo.ui.components.ConfirmDialog
import com.abel.photoo.ui.util.Format

/**
 * 设置页。
 *
 * 这里的每一项都会立刻生效并落盘，不做"保存"按钮 —— 相册类应用的设置
 * 大多是即时可感知的（列数、深浅色），延迟生效反而让人困惑。
 */
@Composable
fun SettingsScreen(
    vm: PhotoOViewModel,
    contentPadding: PaddingValues,
    onOpenTrash: () -> Unit,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val stats by vm.stats.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()

    var confirmReset by remember { mutableStateOf(false) }
    var confirmClearGroups by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("appearance") {
            SettingsGroup("外观", Icons.Rounded.Palette) {
                RowLabel("主题")
                ChipRow(
                    options = ThemeMode.entries,
                    selected = settings.themeMode,
                    label = { it.label },
                    onPick = vm::setThemeMode,
                )
                SwitchRow(
                    title = "动态取色",
                    subtitle = "跟随澎湃 OS 壁纸主题色（Android 12+）",
                    checked = settings.dynamicColor,
                    onChange = vm::setDynamicColor,
                )
            }
        }

        item("grid") {
            SettingsGroup("网格", Icons.Rounded.GridView) {
                RowLabel("每行照片数")
                ChipRow(
                    options = listOf(2, 3, 4, 5, 6),
                    selected = settings.gridColumns,
                    label = { "$it" },
                    onPick = vm::setColumns,
                )
            }
        }

        item("gesture") {
            SettingsGroup("大图手势", Icons.Rounded.Swipe, initiallyExpanded = false) {
                Hint("给四个滑动方向各绑一个动作。左右保持「下一张 / 上一张」时用系统翻页，跟手最顺；改成别的动作后翻页交给手势判定。")
                GestureDirection.entries.forEach { dir ->
                    RowLabel(dir.label)
                    ChipRow(
                        options = GestureAction.entries,
                        selected = settings.gesture(dir),
                        label = { it.label },
                        onPick = { vm.setGesture(dir, it) },
                    )
                }
                RowLabel("灵敏度")
                ChipRow(
                    options = GestureSensitivity.entries,
                    selected = settings.gestureSensitivity,
                    label = { it.label },
                    onPick = vm::setGestureSensitivity,
                )
                Hint("越灵敏需要滑动的距离越短。当前：约屏幕高度的 " +
                    "${(16f / settings.gestureSensitivity.factor).toInt()}% 触发。")
                ActionRow(
                    title = "恢复默认手势",
                    subtitle = "上滑删除 / 下滑退出 / 左右翻页 · 标准灵敏度",
                    onClick = vm::resetGestures,
                )
            }
        }

        item("live") {
            SettingsGroup("Live Photo", Icons.Rounded.MotionPhotosOn) {
                Hint("实况照片自动识别：进入大图即自动播放一次，无需任何开关。")
                SwitchRow(
                    title = "Live Photo 默认静音",
                    subtitle = "仅影响下次启动应用；本次会话中可在大图页 LIVE 标旁随时开声",
                    checked = settings.liveMutedDefault,
                    onChange = vm::setLiveMutedDefault,
                )
            }
        }

        item("similar") {
            SettingsGroup("相似照片", Icons.Rounded.Tune) {
                RowLabel("相似度判定")
                ChipRow(
                    options = SimilarityLevel.entries,
                    selected = settings.similarityLevel,
                    label = { it.label },
                    onPick = vm::setSimilarityLevel,
                )
                Hint(
                    when (settings.similarityLevel) {
                        SimilarityLevel.STRICT -> "只把几乎一模一样的照片归到一组，漏判多、误判少。"
                        SimilarityLevel.BALANCED -> "连拍、轻微裁剪和亮度变化都能识别，日常推荐。"
                        SimilarityLevel.LOOSE -> "同一场景的不同构图也可能归到一组，需要仔细复核。"
                    }
                )
                RowLabel("默认保留策略")
                ChipRow(
                    options = KeepStrategy.entries,
                    selected = settings.keepStrategy,
                    label = { it.label },
                    onPick = vm::setKeepStrategy,
                )
                Hint(settings.keepStrategy.description)
                ActionRow(
                    title = "清除分组处理记录",
                    subtitle = "让所有已处理的相似组重新出现",
                    onClick = { confirmClearGroups = true },
                )
            }
        }

        item("quick") {
            SettingsGroup("快捷归入", Icons.Rounded.Folder) {
                Hint("选中的相册会出现在大图页底部，点一下即可把当前照片归入，免去每次走相册选择器。")
                if (albums.isEmpty()) {
                    Hint("还没有任何相册，先去相册页看看。")
                } else {
                    // 同一个相册名可能对应多个 bucket（如小米的"截图"出现在两个目录），
                    // 这里按名字去重只显示一次，并保留照片数最多的那个作为代表，
                    // 否则会出现"两个截图"且勾选一个另一个也联动勾选的问题。
                    val uniqueAlbums = albums
                        .groupBy { it.name }
                        .map { (_, list) -> list.maxByOrNull { it.count }!! }
                    val picked = settings.quickAlbums.toSet()
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uniqueAlbums.forEach { album ->
                            FilterChip(
                                selected = album.name in picked,
                                onClick = {
                                    val next = if (album.name in picked) {
                                        picked - album.name
                                    } else {
                                        picked + album.name
                                    }
                                    vm.setQuickAlbums(next.toList())
                                },
                                label = { Text(album.name) },
                            )
                        }
                    }
                }
            }
        }

        item("delete") {
            SettingsGroup("删除与回收站", Icons.Rounded.Delete) {
                SwitchRow(
                    title = "同时移入系统回收站",
                    subtitle = "开启后上滑删除会一并调用系统回收站（需确认弹窗）；" +
                        "关闭则只在 PhotoO 内隐藏，原图不动。",
                    checked = settings.alsoSystemTrash,
                    onChange = vm::setAlsoSystemTrash,
                )
                ActionRow(
                    title = "回收站",
                    subtitle = if (trash.isEmpty()) "空" else
                        "${trash.size} 张 · 可释放 ${Format.bytes(trash.sumOf { it.size })}",
                    onClick = onOpenTrash,
                )
            }
        }

        item("review") {
            SettingsGroup("整理", Icons.Rounded.Autorenew) {
                SwitchRow(
                    title = "启动时继续整理",
                    subtitle = "有未处理照片时，打开应用直接进入筛选界面",
                    checked = settings.resumeReviewOnLaunch,
                    onChange = vm::setResumeReview,
                )
                Hint("当前进度：${stats.reviewed} / ${stats.total} 已处理，${stats.pending} 张待整理")
                ActionRow(
                    title = "重置全部整理记录",
                    subtitle = "所有照片重新标记为未处理",
                    danger = true,
                    onClick = { confirmReset = true },
                )
            }
        }

        item("privacy") {
            SettingsGroup("隐私", Icons.Rounded.Place) {
                SwitchRow(
                    title = "解析拍摄地点",
                    subtitle = "把 EXIF 里的经纬度反查成地名，全部在本机离线完成",
                    checked = settings.showLocation,
                    onChange = vm::setShowLocation,
                )
            }
        }

        item("map") {
            SettingsGroup("地图", Icons.Rounded.Place) {
                Hint("地图 Tab 要显示可缩放的真实地图、并联网解析拍摄点地址，需要一个高德地图 Key（JS API 类型）。")
                var keyText by remember { mutableStateOf(settings.amapKey) }
                OutlinedTextField(
                    value = keyText,
                    onValueChange = {
                        keyText = it
                        vm.setAmapKey(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("高德地图 Key") },
                    placeholder = { Text("留空则使用离线示意图") },
                    singleLine = true,
                )
                if (settings.amapKey.isNotBlank()) {
                    Hint("已填入 Key（共 ${settings.amapKey.length} 位）。地图 Tab 会自动联网加载真实底图。")
                } else {
                    Hint("没有 Key 也能用：地图 Tab 会画出离线示意图，只是不可缩放、不显示真实街道。")
                }
                SwitchRow(
                    title = "云端地址解析",
                    subtitle = "开：用高德联网反查拍摄点地名（更准）；关：仅用本机 Geocoder，离线且更隐私",
                    checked = settings.amapCloud,
                    onChange = vm::setAmapCloud,
                )
            }
        }

        item("about") {
            SettingsGroup("关于", Icons.Rounded.Info) {
                Hint(
                    "PhotoO 只读写本机相册，不联网、不上传任何照片或位置信息。\n" +
                        "回收站是应用内的软删除；只有在回收站里再次删除，才会真正调用系统删除。"
                )
                ActionRow(
                    title = "分享调试日志",
                    subtitle = "把运行日志通过系统分享面板发出，方便排查 Live Photo / 地图等问题",
                    onClick = {
                        val uri = vm.shareDebugLogUri()
                        if (uri == null) {
                            vm.toast("还没有日志文件")
                            return@ActionRow
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享调试日志"))
                    },
                )
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "重置整理记录",
            message = "所有照片的「已处理 / 已保留 / 已跳过」标记都会清空，照片本身不受影响。",
            confirmText = "重置",
            danger = true,
            onConfirm = vm::resetReviews,
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmClearGroups) {
        ConfirmDialog(
            title = "清除分组处理记录",
            message = "已标记为处理完毕的相似组会重新出现在列表里。",
            confirmText = "清除",
            onConfirm = vm::clearGroupDecisions,
            onDismiss = { confirmClearGroups = false },
        )
    }
}

// ------------------------------------------------------------------ 组件

@Composable
private fun SettingsGroup(
    title: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 只有标题栏可点：避免和内部 Switch/Chip/ActionRow 的点击冲突。
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun RowLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onPick(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(Modifier.padding(start = 12.dp)) {
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
