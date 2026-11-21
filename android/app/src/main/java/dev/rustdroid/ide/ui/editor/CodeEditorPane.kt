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
        TextMateLanguage.create(def, true).apply { setCompleterKeywords(RUST_WORDS) }
    }

    /**
     * R4A: extra words for the basic (non-semantic) completion, besides the
     * identifiers already in the file: Rust keywords and the std names a
     * beginner types most. Real member completion would need rust-analyzer.
     */
    private val RUST_WORDS = arrayOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
        "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
        "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
        "trait", "true", "type", "unsafe", "use", "where", "while",
        "String", "Vec", "Option", "Some", "None", "Result", "Ok", "Err", "Box", "HashMap",
        "HashSet", "BTreeMap", "println!", "print!", "eprintln!", "format!", "vec!", "panic!",
        "assert!", "assert_eq!", "dbg!", "todo!", "unwrap", "expect", "clone", "to_string",
        "len", "push", "iter", "into_iter", "collect", "map", "filter", "std", "io", "stdin",
        "read_line", "trim", "parse", "i32", "i64", "u8", "u32", "u64", "usize", "f32", "f64",
        "bool", "char", "str",
    )

    private fun grammarSourceFor(assets: android.content.res.AssetManager, path: String): IGrammarSource =
        IGrammarSource.fromInputStream(assets.open(path), path, null)

    fun createEditor(context: Context, dark: Boolean): CodeEditor {
        ensureInit(context)
        val editor = CodeEditor(context)
        editor.setEditorLanguage(createLanguage(context))
        editor.setColorScheme(colorScheme(dark))
        applyProblemColors(editor, dark)
        // R4A: our error card (margins from the screen edges, more padding)
        editor.replaceComponent(
            io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow::class.java,
            R4ADiagnosticTooltip(editor),
        )
        // R4A: our icons in the suggestion list (instead of the "K" letter)
        editor.getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)
            .setAdapter(R4ACompletionAdapter())
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
        // R4A: suggest while the keyboard is still composing the word (Gboard
        // composes almost always; with this off, suggestions never showed)
        editor.getProps().autoCompletionOnComposing = true
        return editor
    }

    /**
     * R4A: squiggle colors for live errors. Not themable through the
     * TextMate JSON, and the scheme re-applies its theme when attached —
     * so this must run AFTER editor.setColorScheme(...).
     */
    fun applyProblemColors(editor: CodeEditor, dark: Boolean) {
        val scheme = editor.colorScheme
        if (dark) {
            scheme.setColor(EditorColorScheme.PROBLEM_ERROR, 0xFFFF5C5C.toInt())
            scheme.setColor(EditorColorScheme.PROBLEM_WARNING, 0xFFFFC857.toInt())
            // suggestion list in R4A colors (it was white on the dark theme)
            scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFF2B2930.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFF4A4458.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFFEDE0D4.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, 0xFF9E96A8.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, 0xFF3D3550.toInt())
        } else {
            scheme.setColor(EditorColorScheme.PROBLEM_ERROR, 0xFFD32F2F.toInt())
            scheme.setColor(EditorColorScheme.PROBLEM_WARNING, 0xFFB7791F.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, 0xFFF3EDF7.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_CORNER, 0xFFCAC4D0.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, 0xFF201A16.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, 0xFF6B6375.toInt())
            scheme.setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, 0xFFE4DAF5.toInt())
        }
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
 * R4A: sora's error card, kept off the screen edges. The stock window
 * centres itself on the error but only clamps at x = 0 and may be as wide
 * as the editor, so near the left edge it touched the border. Here it keeps
 * a margin on both sides and its panels get more inner padding.
 */
private class R4ADiagnosticTooltip(
    editor: CodeEditor,
) : io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow(editor) {

    private val margin = (12 * editor.resources.displayMetrics.density).toInt()

    init {
        val pad = (14 * editor.resources.displayMetrics.density).toInt()
        (popup.contentView as? android.view.ViewGroup)?.let { root ->
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                child.setPadding(pad, child.paddingTop + pad / 3, pad, child.paddingBottom + pad / 3)
            }
        }
    }

    /** All text views of the card (brief, detail, quick-fix buttons). */
    private val texts: List<android.widget.TextView> = buildList {
        fun walk(v: android.view.View) {
            if (v is android.widget.TextView) add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        popup.contentView?.let { walk(it) }
    }

    override fun updateWindowSize() {
        // Wrap instead of overflowing: cap every text at the card's inner
        // width (the quick-fix row is a HorizontalScrollView, so without a
        // max width its button text never wraps), allow multi-line buttons,
        // and let the card grow up to 60% of the editor's height.
        val inner = editor.width - 2 * margin - (48 * editor.resources.displayMetrics.density).toInt()
        texts.forEach { tv ->
            tv.maxWidth = inner
            if (tv is android.widget.Button) {
                tv.isSingleLine = false
                tv.maxLines = 4
                tv.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            }
        }
        maxHeight = (editor.height * 0.6f).toInt()
        super.updateWindowSize()
    }

    override fun setSize(width: Int, height: Int) {
        super.setSize(width.coerceAtMost(editor.width - 2 * margin), height)
    }

    override fun setLocationAbsolutely(x: Int, y: Int) {
        val loc = IntArray(2).also { editor.getLocationInWindow(it) }
        val minX = loc[0] + margin
        val maxX = loc[0] + editor.width - width - margin
        super.setLocationAbsolutely(x.coerceIn(minX, maxOf(minX, maxX)), y)
    }
}

/**
 * R4A: turns live cargo-check diagnostics into sora squiggles + tooltip
 * details. Tapping a squiggle opens sora's diagnostic window with the
 * message and one "Quick fix" button per rustc suggestion.
 */
private object LiveSquiggles {

    fun build(
        editor: CodeEditor,
        diagnostics: List<dev.rustdroid.ide.runtime.LiveDiagnostic>,
    ): io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer {
        val container = io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer()
        val text = editor.text
        val version = text.documentVersion
        diagnostics.forEachIndexed { i, d ->
            val start = index(text, d.lineStart, d.colStart)
            var end = index(text, d.lineEnd, d.colEnd)
            if (end <= start) end = minOf(start + 1, text.length) // never zero-width
            val detailed = buildString {
                d.label?.let { appendLine(it) }
                d.notes.forEach { appendLine(it) }
                d.code?.let { append("[").append(it).append("]") }
            }.trim()
            val fixes = d.fixes
                .filter { fix -> fix.edits.all { it.file == d.file } }
                .map { fix ->
                    io.github.rosemoe.sora.lang.diagnostic.Quickfix(
                        fix.title, version, Runnable { apply(editor, fix) },
                    )
                }
            container.addDiagnostic(
                io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion(
                    start, end,
                    if (d.isError) io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_ERROR
                    else io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion.SEVERITY_WARNING,
                    i.toLong(),
                    io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail(d.message, detailed, fixes, null),
                ),
            )
        }
        return container
    }

    /** rustc line/column (1-based) -> char index, clamped to the text. */
    private fun index(text: io.github.rosemoe.sora.text.Content, line1: Int, col1: Int): Int {
        val line = (line1 - 1).coerceIn(0, text.lineCount - 1)
        val col = (col1 - 1).coerceIn(0, text.getColumnCount(line))
        return text.getCharIndex(line, col)
    }

    /** Applies a fix's edits back-to-front, as one undo step. */
    private fun apply(editor: CodeEditor, fix: dev.rustdroid.ide.runtime.LiveFix) {
        val text = editor.text
        text.beginBatchEdit()
        try {
            fix.edits
                .sortedWith(compareByDescending<dev.rustdroid.ide.runtime.TextEdit> { it.lineStart }.thenByDescending { it.colStart })
                .forEach { e ->
                    val sl = (e.lineStart - 1).coerceIn(0, text.lineCount - 1)
                    val el = (e.lineEnd - 1).coerceIn(0, text.lineCount - 1)
                    val sc = (e.colStart - 1).coerceIn(0, text.getColumnCount(sl))
                    val ec = (e.colEnd - 1).coerceIn(0, text.getColumnCount(el))
                    text.replace(sl, sc, el, ec, e.replacement)
                }
        } finally {
            text.endBatchEdit()
        }
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
    /** R4A: live `cargo check` diagnostics for THIS file. */
    liveDiagnostics: List<dev.rustdroid.ide.runtime.LiveDiagnostic> = emptyList(),
    /** R4A: the text those diagnostics were computed for (null = from disk, unknown). */
    checkedText: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val editor = remember { RustEditor.createEditor(context, dark) }
    // R4A: hand the view to the screen (symbol bar types into it)
    LaunchedEffect(editor) { onEditorReady(editor) }

    // R4A: errors while typing. Draw squiggles only when the editor still
    // shows exactly the text that was checked — otherwise positions could be
    // off; the next check (already scheduled by the edit) will redraw, and
    // meanwhile sora shifts the previous squiggles along with the edits.
    LaunchedEffect(liveDiagnostics, checkedText) {
        val current = editor.text.toString()
        if (liveDiagnostics.isNotEmpty() && checkedText != current) return@LaunchedEffect
        editor.diagnostics = LiveSquiggles.build(editor, liveDiagnostics)
    }
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
        RustEditor.applyProblemColors(editor, dark)
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
