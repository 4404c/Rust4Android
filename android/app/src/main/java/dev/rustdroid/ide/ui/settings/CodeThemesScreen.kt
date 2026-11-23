package dev.rustdroid.ide.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.R

/** R4A: Opciones → Colores del código: todos los temas con su vista previa. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeThemesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_code_theme)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        CodeThemeList(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

/** Nombre visible de un tema de código. */
fun codeThemeName(id: String): Int = when (id) {
    "solarized" -> R.string.code_theme_solarized
    "tokyo" -> R.string.code_theme_tokyo
    "rosepine" -> R.string.code_theme_rosepine
    "ayu" -> R.string.code_theme_ayu
    else -> R.string.code_theme_classic
}

/** R4A: colores de las letras en el editor, con una vista previa de cada tema. */
@Composable
private fun CodeThemeList(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current by dev.rustdroid.ide.util.AppSettings.codeTheme.collectAsState()
    val dark = dev.rustdroid.ide.ui.theme.LocalDarkTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        run {
            Text(
                stringResource(R.string.settings_code_theme_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (theme in dev.rustdroid.ide.ui.editor.CodeThemes.all) {
                val selected = theme.id == current
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        )
                        .selectable(
                            selected = selected,
                            role = androidx.compose.ui.semantics.Role.RadioButton,
                            onClick = { dev.rustdroid.ide.util.AppSettings.setCodeTheme(context, theme.id) },
                        ),
                ) {
                    Row(
                        Modifier.padding(start = 4.dp, end = 12.dp, top = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = selected, onClick = null,
                            modifier = Modifier.padding(8.dp))
                        Text(stringResource(codeThemeName(theme.id)), style = MaterialTheme.typography.bodyMedium)
                    }
                    CodePreview(theme.colors(dark), dark)
                }
            }
        }
    }
}

/** Dos líneas de Rust pintadas con los colores del tema, sobre el fondo del editor. */
@Composable
private fun CodePreview(c: dev.rustdroid.ide.ui.editor.SyntaxColors, dark: Boolean) {
    val text = androidx.compose.ui.text.buildAnnotatedString {
        val nl = "\n"
        fun part(t: String, color: Long) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(color)))
            append(t)
            pop()
        }
        part("fn ", c.keyword); part("saludar", c.function); part("(", c.punctuation)
        part("n", c.variable); part(": ", c.punctuation); part("u32", c.type); part(") {" + nl, c.punctuation)
        part("    let ", c.keyword); part("x", c.variable); part(" = ", c.operator); part("42", c.number)
        part(";" + nl, c.punctuation)
        part("    println!", c.macro); part("(", c.punctuation); part("\"hola {n}\"", c.string)
        part("); ", c.punctuation); part("// listo", c.comment)
        part(nl + "}", c.punctuation)
    }
    Text(
        text,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(if (dark) 0xFF151515 else 0xFFFFFFFF))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

