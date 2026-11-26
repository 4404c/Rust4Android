package dev.rustdroid.ide.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.rustdroid.ide.RustDroidApp

/**
 * R4A (solo debug): simula que la IA trabajó [EXTRA_SECONDS] segundos en un
 * proyecto y terminó, por el mismo camino que una charla real
 * (ChatStore.report → AiWorkService), sin mandarle nada a ningún proveedor.
 * "Sin mirar" = la app no está a la vista (como en ProjectChats).
 */
class DebugAiDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as RustDroidApp
        val ref = intent.getStringExtra("project") ?: return
        val seconds = intent.getIntExtra("seconds", 5)
        val error = intent.getBooleanExtra("error", false)
        val repo = app.container.projectRepository
        val dir = repo.resolve(ref)
        val store = app.container.chatStore
        val key = store.keyOf(dir)
        store.report(key, dir.name, working = true, finishedUnseen = false, error = false)
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            store.report(key, dir.name, working = false, finishedUnseen = !AppVisibility.foreground, error = error)
            pending.finish()
        }, seconds * 1000L)
    }
}
