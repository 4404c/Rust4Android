package dev.rustdroid.ide.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rustdroid.ide.R
import dev.rustdroid.ide.ui.theme.LocalEditorPalette

/** R4A: el cajón del Chat IA (desde la derecha). */
@Composable
fun ChatDrawerSheet(
    chat: dev.rustdroid.ide.ai.ProjectChats,
    onClose: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    /** Chat de Inicio: otro texto de bienvenida. */
    home: Boolean = false,
) {
    val palette = LocalEditorPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.consoleBg)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBars)
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiBadge(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.ai_chat_menu),
                style = MaterialTheme.typography.titleMedium,
                color = palette.stdout,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ai_chat_close), tint = palette.stdout)
            }
        }
        ChatPanel(
            chats = chat,
            onInputFocusChanged = {},
            onOpenFile = onOpenFile,
            onOpenProject = onOpenProject,
            emptyText = if (home) stringResource(R.string.ai_home_empty) else null,
        )
    }
}

/**
 * R4A: las letras "AI" en lugar de un ícono (botón del chat, menú ⋮ y
 * cabecera del cajón). Sin [color] toma el color del ícono (LocalContentColor).
 */
@Composable
fun AiBadge(
    color: Color = androidx.compose.material3.LocalContentColor.current,
    contentDescription: String? = null,
) {
    Text(
        "AI",
        color = color,
        fontSize = 15.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = if (contentDescription != null) {
            Modifier.semantics { this.contentDescription = contentDescription }
        } else Modifier,
    )
}
