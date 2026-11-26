package dev.rustdroid.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rustdroid.ide.R
import dev.rustdroid.ide.projects.ProjectSearch
import dev.rustdroid.ide.ui.components.RdIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher

/**
 * R4A: find / replace in the open file, on top of sora-editor's searcher
 * (it highlights every match, jumps between them and replaces). Row 1:
 * text to find, "3/7", previous/next, Aa (match case), replace toggle,
 * close. Row 2 (optional): replacement, Replace (this one), All.
 * The folder toggle searches the whole project instead: a list of results
 * (file:line + the line) that open the file at that line.
 */
@Composable
fun EditorSearchBar(
    editor: CodeEditor,
    fileKey: String?,
    projectDir: File,
    unsaved: () -> Map<String, String>,
    onOpenHit: (path: String, line: Int) -> Unit,
    onClose: () -> Unit,
) {
    var inProject by remember { mutableStateOf(false) }
    var projectResult by remember { mutableStateOf<ProjectSearch.Result?>(null) }
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var matchCase by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(-1) }
    var total by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    val searcher = editor.searcher

    fun refreshCount() {
        total = runCatching { searcher.matchedPositionCount }.getOrDefault(0)
        current = runCatching { searcher.currentMatchedPositionIndex }.getOrDefault(-1)
    }

    // search again when the text to find, the options or the file change
    LaunchedEffect(query, matchCase, fileKey, editor) {
        if (query.isEmpty()) {
            searcher.stopSearch()
            total = 0; current = -1
        } else {
            searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, !matchCase))
        }
    }
    // results arrive in the background; the cursor moves the "current" one
    DisposableEffect(editor) {
        val a = editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> refreshCount() }
        val b = editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> refreshCount() }
        onDispose { a.unsubscribe(); b.unsubscribe() }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // whole project: search on disk (open tabs as they are), after a pause in typing
    LaunchedEffect(query, matchCase, inProject) {
        if (!inProject || query.isEmpty()) {
            projectResult = null
            return@LaunchedEffect
        }
        delay(250)
        val snapshot = unsaved()
        projectResult = withContext(Dispatchers.IO) {
            ProjectSearch.search(projectDir, query, matchCase, snapshot)
        }
    }

    fun next() { if (searcher.hasQuery()) searcher.gotoNext(); refreshCount() }
    fun previous() { if (searcher.hasQuery()) searcher.gotoPrevious(); refreshCount() }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.search_find),
                onDone = { next() },
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (!inProject) Text(
                when {
                    query.isEmpty() -> ""
                    total == 0 -> "0"
                    current >= 0 -> "${current + 1}/$total"
                    else -> "$total"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (query.isNotEmpty() && total == 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp).widthIn(min = 28.dp),
            )
            if (!inProject) {
                SmallIcon(onClick = { previous() }) {
                    Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.search_previous))
                }
                SmallIcon(onClick = { next() }) {
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.search_next))
                }
            }
            SmallIcon(onClick = { matchCase = !matchCase }) {
                Text(
                    "Aa",
                    fontWeight = if (matchCase) FontWeight.Bold else FontWeight.Normal,
                    color = if (matchCase) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallIcon(onClick = { inProject = !inProject }) {
                Icon(
                    RdIcons.Folder,
                    stringResource(R.string.search_in_project),
                    modifier = Modifier.size(20.dp),
                    tint = if (inProject) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!inProject) SmallIcon(onClick = { showReplace = !showReplace }) {
                Text(
                    "⇄",
                    fontSize = 18.sp,
                    color = if (showReplace) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallIcon(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.search_close))
            }
        }
        if (inProject) {
            ProjectResults(query, projectResult, onOpenHit)
        } else if (showReplace) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    placeholder = stringResource(R.string.search_replace_with),
                    onDone = {},
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = total > 0,
                    onClick = {
                        // replace the selected match (select the next one first)
                        if (!searcher.isMatchedPositionSelected) searcher.gotoNext()
                        if (searcher.isMatchedPositionSelected) searcher.replaceThis(replacement)
                    },
                ) { Text(stringResource(R.string.search_replace_one)) }
                TextButton(
                    enabled = total > 0,
                    onClick = { searcher.replaceAll(replacement) },
                ) { Text(stringResource(R.string.search_replace_all)) }
            }
        }
    }
}

/** Results of the whole-project search: a summary line, then file:line + the line. */
@Composable
private fun ProjectResults(
    query: String,
    result: ProjectSearch.Result?,
    onOpenHit: (path: String, line: Int) -> Unit,
) {
    if (query.isEmpty() || result == null) return
    val files = result.hits.map { it.path }.distinct().size
    val lines = result.hits.size
    Text(
        if (lines == 0) stringResource(R.string.search_project_none)
        else androidx.compose.ui.res.pluralStringResource(R.plurals.search_project_lines, lines, lines) + " " +
            androidx.compose.ui.res.pluralStringResource(R.plurals.search_project_files, files, files) +
            (if (result.truncated) " " + stringResource(R.string.search_project_more) else ""),
        style = MaterialTheme.typography.labelMedium,
        color = if (result.hits.isEmpty()) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    if (result.hits.isEmpty()) return
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
        items(result.hits) { hit ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenHit(hit.path, hit.line) }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
            ) {
                Text(
                    "${hit.path}:${hit.line}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    hit.preview,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SmallIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { content() }
}

/** A single-line field shorter than Material's 56dp ones (screen space matters here). */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = color, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onDone() }, onDone = { onDone() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier
                    .height(38.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                }
                inner()
            }
        },
    )
}
