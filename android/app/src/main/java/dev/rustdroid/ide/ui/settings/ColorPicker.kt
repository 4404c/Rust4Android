package dev.rustdroid.ide.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.rustdroid.ide.R

/** Colores rápidos: los de R4A y algunos clásicos. */
private val QUICK = listOf(
    0xFF9CD634, 0xFF5C941E, 0xFF1E360C, 0xFF2E7D32, 0xFF00897B, 0xFF0288D1,
    0xFF1565C0, 0xFF5E35B1, 0xFFC2185B, 0xFFE53935, 0xFFF57C00, 0xFFFBC02D,
    0xFF6D4C41, 0xFF546E7A, 0xFF212121, 0xFF000000, 0xFFF5F5F5, 0xFFFFFFFF,
)

fun Int.toHex() = String.format("#%06X", this and 0xFFFFFF)

private fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6) return null
    return t.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}

/** R4A: elegir un color: colores rápidos, tono/saturación/brillo o código #RRGGBB. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    /** Si no es null, muestra un botón "Automático" (para el color del texto). */
    onAuto: (() -> Unit)? = null,
) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val current = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    var hexText by remember { mutableStateOf(initial.toHex()) }

    fun set(color: Int) {
        val out = FloatArray(3)
        android.graphics.Color.colorToHSV(color, out)
        hue = out[0]; sat = out[1]; value = out[2]
        hexText = color.toHex()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(current))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (q in QUICK) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(q))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { set(q.toInt()) },
                        )
                    }
                }
                Text(stringResource(R.string.color_hue), style = MaterialTheme.typography.bodySmall)
                Slider(value = hue, onValueChange = { hue = it; hexText = current.toHex() }, valueRange = 0f..360f)
                Text(stringResource(R.string.color_saturation), style = MaterialTheme.typography.bodySmall)
                Slider(value = sat, onValueChange = { sat = it; hexText = current.toHex() })
                Text(stringResource(R.string.color_brightness), style = MaterialTheme.typography.bodySmall)
                Slider(value = value, onValueChange = { value = it; hexText = current.toHex() })
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { t -> hexText = t; parseHex(t)?.let { c -> set(c); hexText = t } },
                    label = { Text(stringResource(R.string.color_hex)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(Color(current).toArgb()) }) { Text(stringResource(R.string.color_done)) }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (onAuto != null) {
                    TextButton(onClick = onAuto) { Text(stringResource(R.string.custom_text_auto)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
