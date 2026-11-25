package dev.rustdroid.ide

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.rustdroid.ide.ui.AppRoot
import dev.rustdroid.ide.ui.theme.RustDroidTheme

class MainActivity : ComponentActivity() {

    /** Set from ACTION_VIEW .rs intents until AppRoot consumes it (navigates to the imported file). */
    private val pendingOpenRs = mutableStateOf<Uri?>(null)

    // R4A: UI language chosen in Settings (Spanish by default)
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(dev.rustdroid.ide.util.AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureRsIntent(intent)
        val container = (application as RustDroidApp).container
        setContent {
            RustDroidTheme {
                AppRoot(
                    container,
                    pendingOpenRs = pendingOpenRs,
                    onPendingRsConsumed = { pendingOpenRs.value = null },
                )
            }
        }
    }

    // singleTask launch mode: a running instance receives VIEW intents here.
    override fun onStart() {
        super.onStart()
        dev.rustdroid.ide.ai.AppVisibility.foreground = true
    }

    override fun onStop() {
        dev.rustdroid.ide.ai.AppVisibility.foreground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureRsIntent(intent)
    }

    private fun isInsideAppData(path: String?): Boolean {
        if (path == null) return true
        val target = runCatching { java.io.File(path).canonicalPath }.getOrNull() ?: return true
        val data = runCatching { java.io.File(applicationInfo.dataDir).canonicalPath }.getOrNull() ?: return true
        return target == data || target.startsWith(data + java.io.File.separator) ||
            target.startsWith("/data/data/$packageName/") || target.startsWith("/data/user/0/$packageName/")
    }

    private fun captureRsIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            // R4A (auditoría B5): otra app no puede hacernos abrir (e importar)
            // un archivo privado de R4A con un file:// a nuestros datos
            if (uri.scheme == "file" && isInsideAppData(uri.path)) return
            if (uri.toString().endsWith(".rs") || intent.type == "text/x-rust") {
                pendingOpenRs.value = uri
            }
        }
    }
}
