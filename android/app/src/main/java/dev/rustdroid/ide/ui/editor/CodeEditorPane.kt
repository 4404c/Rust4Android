package dev.rustdroid.ide.ui.editor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.rustdroid.ide.model.JumpRequest
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * sora-editor interop: Rust grammar + TextMate color scheme + jump-to-line.
 * The editor view is owned by Compose via AndroidView; content changes are
 * forwarded to the VM through [onTextChange] (programmatic setText events
 * are filtered so loading a tab does not mark it dirty), and View-level
 * focus changes through [onFocusChanged] (drives the IME-aware layout in
 * EditorScreen: keyboard + editor focus => hide the console panel).
 */
object RustEditor {

    @Volatile private var grammarReady = false

    fun ensureInit(context: Context) {
        if (grammarReady) return
        synchronized(this) {
            if (grammarReady) return
            val assets = context.assets
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            // theme(s): load both, switch current later
            ThemeRegistry.getInstance().apply {
                loadTheme(
                    IThemeSource.fromInputStream(
                        assets.open("textmate/theme-dark.json"), "theme-dark.json", null
                    )
                )
                loadTheme(
                    IThemeSource.fromInputStream(
                        assets.open("textmate/theme-light.json"), "theme-light.json", null
                    ),
                    false
                )
            }
            grammarReady = true
        }
    }

    /**
     * R4A: load the theme and the Rust grammar off the main thread at app
     * start (the grammar registry caches it), so opening a project no longer
     * freezes the UI for ~150 ms while it parses. The throwaway language is
     * destroyed; each editor still gets its own instance.
     */
    fun warmUp(context: Context) {
        runCatching { createLanguage(context).destroy() }
    }

    fun createLanguage(context: Context): TextMateLanguage = synchronized(this) {
        ensureInit(context)
        val assets = context.assets
        val def = DefaultGrammarDefinition.withLanguageConfiguration(
            grammarSourceFor(assets, "textmate/rust.tmLanguage.json"),
            "textmate/rust-language-configuration.json",
            "rust", "source.rust",
        )
        TextMateLanguage.create(def, true)
    }

    private fun grammarSourceFor(assets: android.content.res.AssetManager, path: String): IGrammarSource =
        IGrammarSource.fromInputStream(assets.open(path), path, null)

    fun createEditor(context: Context, dark: Boolean): CodeEditor {
        ensureInit(context)
        val editor = CodeEditor(context)
        editor.setEditorLanguage(createLanguage(context))
        editor.setColorScheme(colorScheme(dark))
        editor.typefaceText = android.graphics.Typeface.MONOSPACE
        editor.setTextSize(14f)
        editor.setWordwrap(false)
        // R4A: line numbers used to touch the screen edge — indent them and
        // leave some room around the divider (values are in px)
        val dp = context.resources.displayMetrics.density
        editor.setLineNumberMarginLeft(8 * dp)
        editor.setDividerMargin(6 * dp, 6 * dp)
        editor.getProps().autoIndent = true
        editor.getProps().symbolPairAutoCompletion = true
        return editor
    }

    fun colorScheme(dark: Boolean): EditorColorScheme {
        val registry = ThemeRegistry.getInstance()
        runCatching {
            registry.setTheme(if (dark) "RustDroid Dark" else "RustDroid Light")
        }
        // R4A: the matching-bracket color lives in the theme JSONs
        // ("highlightedDelimitersForeground"). Setting it here does not stick:
        // the scheme re-applies its theme when attached to the editor.
        return TextMateColorScheme.create(registry, registry.currentThemeModel)
    }
}

/**
 * R4A: "flash" for jump-to-line. The editor already paints the cursor's line
 * with the scheme's CURRENT_LINE color; after a jump the cursor sits on the
 * target line, so we tint that color toward [accent], hold it briefly, then
 * animate it back to the original. The original is always restored, also
 * when a new jump cancels a running flash.
 */
private class LineFlash(private val editor: CodeEditor) {
    private var animator: android.animation.ValueAnimator? = null
    private var restore: Pair<EditorColorScheme, Int>? = null

    fun start(accent: Int) {
        cancel() // restores the base color of a running flash first
        val scheme = editor.colorScheme
        val base = scheme.getColor(EditorColorScheme.CURRENT_LINE)
        restore = scheme to base
        val evaluator = android.animation.ArgbEvaluator()
        val lit = evaluator.evaluate(0.55f, base, accent) as Int
        scheme.setColor(EditorColorScheme.CURRENT_LINE, lit)
        editor.invalidate()
        animator = android.animation.ValueAnimator.ofObject(evaluator, lit, base).apply {
            startDelay = HOLD_MS
            duration = FADE_MS
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addUpdateListener {
                scheme.setColor(EditorColorScheme.CURRENT_LINE, it.animatedValue as Int)
                editor.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    scheme.setColor(EditorColorScheme.CURRENT_LINE, base)
                    editor.invalidate()
                }
            })
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
        // restore explicitly: a flash cancelled during its hold delay may not
        // deliver onAnimationEnd on every Android version
        restore?.let { (scheme, base) ->
            scheme.setColor(EditorColorScheme.CURRENT_LINE, base)
            editor.invalidate()
        }
        restore = null
    }

    private companion object {
        const val HOLD_MS = 400L
        const val FADE_MS = 1600L
    }
}

@Composable
fun CodeEditorPane(
    text: String,
    dark: Boolean,
    onTextChange: (String) -> Unit,
    jumpRequest: JumpRequest?,
    onFocusChanged: (Boolean) -> Unit = {},
    onEditorReady: (CodeEditor) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val editor = remember { RustEditor.createEditor(context, dark) }
    // R4A: hand the view to the screen (symbol bar types into it)
    LaunchedEffect(editor) { onEditorReady(editor) }
    val programmaticSet = remember { AtomicBoolean(false) }

    // text sync: only when the incoming text differs (tab switch / external load)
    LaunchedEffect(text) {
        if (editor.text.toString() != text) {
            programmaticSet.set(true)
            editor.setText(text)
            editor.setSelection(0, 0, false)
        }
    }

    // jump to diagnostic location (1-based -> 0-based)
    val flashAccent = MaterialTheme.colorScheme.primary.toArgb()
    val flash = remember { LineFlash(editor) }
    LaunchedEffect(jumpRequest) {
        if (jumpRequest != null) {
            val line = (jumpRequest.line - 1).coerceAtLeast(0)
            val col = (jumpRequest.col - 1).coerceAtLeast(0)
            editor.setSelection(line, col, true)
            // R4A: light up the target line, then fade back to normal
            flash.start(flashAccent)
        }
    }
    DisposableEffect(Unit) {
        onDispose { flash.cancel() }
    }

    // theme flip: recreate the color scheme in place
    LaunchedEffect(dark) {
        editor.setColorScheme(RustEditor.colorScheme(dark))
    }

    DisposableEffect(Unit) {
        val receipt = editor.subscribeEvent(
            io.github.rosemoe.sora.event.ContentChangeEvent::class.java
        ) { event, _ ->
            if (event.action == io.github.rosemoe.sora.event.ContentChangeEvent.ACTION_SET_NEW_TEXT &&
                programmaticSet.getAndSet(false)
            ) {
                return@subscribeEvent
            }
            onTextChange(editor.text.toString())
        }
        onDispose {
            runCatching { receipt.unsubscribe() }
            editor.release()
        }
    }

    AndroidView(
        factory = { editor },
        // focus listener re-attached on recomposition — captures the current
        // callback without recreating the (expensive) editor View
        update = { it.setOnFocusChangeListener { _, hasFocus -> onFocusChanged(hasFocus) } },
        modifier = modifier,
    )
}
