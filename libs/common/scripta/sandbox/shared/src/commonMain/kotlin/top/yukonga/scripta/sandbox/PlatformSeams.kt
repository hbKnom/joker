package top.yukonga.scripta.sandbox

import androidx.compose.runtime.Composable

/** 平台文档打开器：open() 触发平台选择器；成功回调 (显示名, 内容)，失败回调错误信息。 */
expect class DocumentOpener {
    fun open()
}

@Composable
expect fun rememberDocumentOpener(
    onOpened: (name: String, content: String) -> Unit,
    onError: (message: String) -> Unit,
): DocumentOpener

/** 把当前主题明暗反映到平台系统栏图标（Android：状态栏/导航栏图标明暗）；桌面无系统栏，为空实现。 */
@Composable
expect fun SyncSystemBarsAppearance(dark: Boolean)
