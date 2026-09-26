package top.yukonga.scripta.sandbox

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

actual class DocumentOpener(private val launch: () -> Unit) {
    actual fun open() = launch()
}

@Composable
actual fun rememberDocumentOpener(
    onOpened: (name: String, content: String) -> Unit,
    onError: (message: String) -> Unit,
): DocumentOpener {
    val scope = rememberCoroutineScope()
    return remember { DocumentOpener { pickAndRead(scope, onOpened, onError) } }
}

private fun pickAndRead(
    scope: CoroutineScope,
    onOpened: (String, String) -> Unit,
    onError: (String) -> Unit,
) = scope.launch {
    try {
        val file = withContext(Dispatchers.IO) {
            // Swing components must be created/shown on the AWT event dispatch thread. This runs on a
            // Dispatchers.IO thread (never the EDT), so invokeAndWait cannot self-deadlock.
            var selected: File? = null
            SwingUtilities.invokeAndWait {
                val chooser = JFileChooser()
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selected = chooser.selectedFile
            }
            selected
        } ?: return@launch
        val content = withContext(Dispatchers.IO) {
            if (file.length() > MAX_OPEN_BYTES) {
                throw IOException("文件过大（${file.length() / 1024 / 1024}MB），上限 ${MAX_OPEN_BYTES / 1024 / 1024}MB")
            }
            file.readBytes().decodeToString()
        }
        onOpened(file.name, content)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError("打开失败: ${e.message}")
    }
}

@Composable
actual fun SyncSystemBarsAppearance(dark: Boolean) {
    // 桌面窗口无系统栏，无需处理。
}
