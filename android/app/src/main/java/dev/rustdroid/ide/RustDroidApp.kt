package dev.rustdroid.ide

import android.app.Application
import dev.rustdroid.ide.di.AppContainer
import dev.rustdroid.ide.runtime.CrashRecorder

class RustDroidApp : Application() {

    lateinit var container: AppContainer
        private set

    // R4A: chosen language for app-level strings (notifications, services)
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(dev.rustdroid.ide.util.AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // Flight recorder FIRST: an uncaught exception anywhere (the
        // terminal bring-up path included) is persisted to
        // files/last-crash.txt and surfaced in-app on the next launch,
        // instead of vanishing with the process. Chained to the platform
        // handler so the system crash flow is unchanged.
        CrashRecorder.install(filesDir)
        container = AppContainer(this)
        dev.rustdroid.ide.util.AppSettings.init(this)
        // R4A: parse the editor's theme + Rust grammar in the background now,
        // instead of on the main thread when the first project opens.
        Thread({ dev.rustdroid.ide.ui.editor.RustEditor.warmUp(this) }, "r4a-editor-warmup")
            .apply { isDaemon = true }
            .start()
    }
}
