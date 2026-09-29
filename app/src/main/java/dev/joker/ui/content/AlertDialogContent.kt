package dev.joker.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// drop-in replacement for AlertDialog that should be used in showComposeDialog()
// to avoid creating 2 Windows for one Dialog
@Composable
fun AlertDialogContent(
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)?,
    text: @Composable (() -> Unit)?,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    textTopSpacing: Dp = 12.dp,
    /**
     * 【Round43】正文区是否可纵向滚动。
     *
     * 背景：设置页/汇总菜单这类「条目很多」的弹窗（例如「聊天功能」汇总菜单一次列 10+ 行）
     * 在 `AlertDialogContent` 里被硬裁剪 —— 用户截图上最后一行与「关闭」按钮重叠、且整页
     * 根本滑不动（`Box(weight(1f, fill=false))` 没有滚动能力，`wrapContentHeight` 又允许
     * 内容超过窗口高度）。开启本开关后正文区变成可滚动、且弹窗整体高度被限制在屏幕的
     * 86% 以内，长列表就能正常上下滑动。
     *
     * 默认 false —— 保持原行为，避免影响正文里本身带 LazyColumn/LazyRow 的弹窗
     * （惰性列表被放进 verticalScroll 会因无限高约束直接抛
     * "Vertically scrollable component was measured with an infinity maximum height"）。
     */
    textScrolls: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = modifier
            .padding(4.dp)
            .fillMaxWidth()
            .then(
                if (textScrolls) {
                    // 屏高的 86%：留出状态栏/导航栏 + 弹窗自身边距，避免正文被系统裁掉。
                    Modifier.heightIn(
                        max = (LocalConfiguration.current.screenHeightDp.dp * 0.86f),
                    )
                } else {
                    Modifier
                },
            )
            .wrapContentHeight()
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                            icon()
                        }
                    }
                }
                title?.let {
                    val customStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    )
                    CompositionLocalProvider(LocalTextStyle provides customStyle) {
                        it()
                    }
                }
            }

            HorizontalDivider(Modifier.padding(top = 12.dp))

            text?.let {
                val bodyStyle = MaterialTheme.typography.bodyMedium
                val bodyColor = MaterialTheme.colorScheme.onSurface

                val scrollModifier = if (textScrolls) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(top = textTopSpacing)
                        .then(scrollModifier),
                ) {
                    CompositionLocalProvider(
                        LocalTextStyle provides bodyStyle,
                        LocalContentColor provides bodyColor
                    ) {
                        it()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                val buttonTextStyle = MaterialTheme.typography.labelLarge
                CompositionLocalProvider(LocalTextStyle provides buttonTextStyle) {
                    dismissButton?.invoke()
                    confirmButton?.invoke()
                }
            }
        }
    }
}
