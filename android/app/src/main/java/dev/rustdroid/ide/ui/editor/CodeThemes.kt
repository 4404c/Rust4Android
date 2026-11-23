package dev.rustdroid.ide.ui.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * R4A: temas de colores del código (Opciones → Colores del código).
 * Los colores salen de los temas publicados de VS Code (todos con licencia MIT):
 * Clásico = Dark+/Light+ de VS Code; los demás, de los archivos oficiales de
 * cada tema (ver el comentario de cada uno). Las letras semitransparentes de
 * Ayu se mezclaron con el fondo del editor de R4A.
 * Sólo cambian el color de las letras; el fondo del editor sigue al modo
 * claro/oscuro de la app (sale de assets/textmate/theme-{dark,light}.json).
 */
data class SyntaxColors(
    val comment: Long,
    val string: Long,
    val constant: Long,
    val number: Long,
    val keyword: Long,
    val operator: Long,
    val function: Long,
    val type: Long,
    val enumMember: Long,
    val macro: Long,
    val variable: Long,
    val attribute: Long,
    val lifetime: Long,
    val punctuation: Long,
    val invalid: Long,
    val plain: Long,
)

data class CodeTheme(val id: String, val dark: SyntaxColors, val light: SyntaxColors) {
    fun colors(dark: Boolean) = if (dark) this.dark else light
    fun registryName(dark: Boolean) = "R4A $id ${if (dark) "Dark" else "Light"}"
}

object CodeThemes {

    const val DEFAULT = "classic"

    val all = listOf(
        CodeTheme(
            "classic",
            dark = SyntaxColors(
                comment = 0xFF6A9955, string = 0xFFCE9178, constant = 0xFF569CD6, number = 0xFFB5CEA8,
                keyword = 0xFF569CD6, operator = 0xFFD4D4D4, function = 0xFFDCDCAA, type = 0xFF4EC9B0,
                enumMember = 0xFF4FC1FF, macro = 0xFFDCDCAA, variable = 0xFF9CDCFE, attribute = 0xFFDCDCAA,
                lifetime = 0xFFDCDCAA, punctuation = 0xFFD4D4D4, invalid = 0xFFF44747, plain = 0xFFD4D4D4,
            ),
            light = SyntaxColors(
                comment = 0xFF008000, string = 0xFFA31515, constant = 0xFF0000FF, number = 0xFF098658,
                keyword = 0xFF0000FF, operator = 0xFF3B3B3B, function = 0xFF795E26, type = 0xFF267F99,
                enumMember = 0xFF007ACC, macro = 0xFF795E26, variable = 0xFF001080, attribute = 0xFF795E26,
                lifetime = 0xFF795E26, punctuation = 0xFF3B3B3B, invalid = 0xFFCD3131, plain = 0xFF3B3B3B,
            ),
        ),
        // Solarized (microsoft/vscode, MIT)
        CodeTheme(
            "solarized",
            dark = SyntaxColors(
                comment = 0xFF586E75, string = 0xFF2AA198, constant = 0xFFB58900, number = 0xFFD33682,
                keyword = 0xFF859900, operator = 0xFF859900, function = 0xFF268BD2, type = 0xFFCB4B16,
                enumMember = 0xFF268BD2, macro = 0xFF268BD2, variable = 0xFF268BD2, attribute = 0xFF93A1A1,
                lifetime = 0xFF93A1A1, punctuation = 0xFF839496, invalid = 0xFFDC322F, plain = 0xFF839496,
            ),
            light = SyntaxColors(
                comment = 0xFF93A1A1, string = 0xFF2AA198, constant = 0xFFB58900, number = 0xFFD33682,
                keyword = 0xFF859900, operator = 0xFF859900, function = 0xFF268BD2, type = 0xFFCB4B16,
                enumMember = 0xFF268BD2, macro = 0xFF268BD2, variable = 0xFF268BD2, attribute = 0xFF93A1A1,
                lifetime = 0xFF586E75, punctuation = 0xFF657B83, invalid = 0xFFDC322F, plain = 0xFF657B83,
            ),
        ),
        // Tokyo Night / Tokyo Night Light (enkia/tokyo-night-vscode-theme, MIT)
        CodeTheme(
            "tokyo",
            dark = SyntaxColors(
                comment = 0xFF51597D, string = 0xFF9ECE6A, constant = 0xFFFF9E64, number = 0xFFFF9E64,
                keyword = 0xFFBB9AF7, operator = 0xFF89DDFF, function = 0xFF7AA2F7, type = 0xFFC0CAF5,
                enumMember = 0xFF7AA2F7, macro = 0xFF7AA2F7, variable = 0xFFC0CAF5, attribute = 0xFFBB9AF7,
                lifetime = 0xFF9D7CD8, punctuation = 0xFF89DDFF, invalid = 0xFFFF5370, plain = 0xFFA9B1D6,
            ),
            light = SyntaxColors(
                comment = 0xFF888B94, string = 0xFF385F0D, constant = 0xFF965027, number = 0xFF965027,
                keyword = 0xFF65359D, operator = 0xFF006C86, function = 0xFF2959AA, type = 0xFF343B58,
                enumMember = 0xFF2959AA, macro = 0xFF2959AA, variable = 0xFF343B58, attribute = 0xFF65359D,
                lifetime = 0xFF7B43BA, punctuation = 0xFF006C86, invalid = 0xFF942F2F, plain = 0xFF343B59,
            ),
        ),
        // Rosé Pine / Rosé Pine Dawn (rose-pine/vscode, MIT)
        CodeTheme(
            "rosepine",
            dark = SyntaxColors(
                comment = 0xFF6E6A86, string = 0xFFF6C177, constant = 0xFFEBBCBA, number = 0xFFEBBCBA,
                keyword = 0xFF31748F, operator = 0xFF31748F, function = 0xFFEBBCBA, type = 0xFF9CCFD8,
                enumMember = 0xFFE0DEF4, macro = 0xFFEBBCBA, variable = 0xFFE0DEF4, attribute = 0xFFC4A7E7,
                lifetime = 0xFF31748F, punctuation = 0xFF908CAA, invalid = 0xFFEB6F92, plain = 0xFFE0DEF4,
            ),
            light = SyntaxColors(
                comment = 0xFF9893A5, string = 0xFFEA9D34, constant = 0xFFD7827E, number = 0xFFD7827E,
                keyword = 0xFF286983, operator = 0xFF286983, function = 0xFFD7827E, type = 0xFF56949F,
                enumMember = 0xFF575279, macro = 0xFFD7827E, variable = 0xFF575279, attribute = 0xFF907AA9,
                lifetime = 0xFF286983, punctuation = 0xFF797593, invalid = 0xFFB4637A, plain = 0xFF575279,
            ),
        ),
        // Ayu Dark / Ayu Light (ayu-theme/vscode-ayu, MIT)
        CodeTheme(
            "ayu",
            dark = SyntaxColors(
                comment = 0xFF5A6673, string = 0xFFAAD94C, constant = 0xFFD2A6FF, number = 0xFFD2A6FF,
                keyword = 0xFFFF8F40, operator = 0xFFF29668, function = 0xFFFFB454, type = 0xFF59C2FF,
                enumMember = 0xFFC0BDB4, macro = 0xFFFFB454, variable = 0xFFC0BDB4, attribute = 0xFFFFB454,
                lifetime = 0xFFFF8F40, punctuation = 0xFFC0BDB4, invalid = 0xFFD95757, plain = 0xFFC0BDB4,
            ),
            light = SyntaxColors(
                comment = 0xFFADAEB1, string = 0xFF86B300, constant = 0xFFA37ACC, number = 0xFFA37ACC,
                keyword = 0xFFFA8532, operator = 0xFFF2A191, function = 0xFFEBA400, type = 0xFF22A4E6,
                enumMember = 0xFF5D6268, macro = 0xFFEBA400, variable = 0xFF5D6268, attribute = 0xFFEBA400,
                lifetime = 0xFFFA8532, punctuation = 0xFF5D6268, invalid = 0xFFE65050, plain = 0xFF5D6268,
            ),
        ),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: all.first()

    /** Arma el JSON de TextMate: los colores del editor del archivo base + las letras del tema. */
    fun buildThemeJson(baseJson: String, theme: CodeTheme, dark: Boolean): String {
        val c = theme.colors(dark)
        fun hex(v: Long) = String.format("#%06X", v and 0xFFFFFF)
        fun rule(scopes: List<String>, color: Long) = JSONObject()
            .put("scope", JSONArray(scopes))
            .put("settings", JSONObject().put("foreground", hex(color)))
        val tokens = JSONArray()
            .put(rule(listOf("comment", "punctuation.definition.comment"), c.comment))
            .put(rule(listOf("string", "string.quoted", "punctuation.definition.string"), c.string))
            .put(rule(listOf("constant.character.escape", "constant.character", "constant.language", "constant.other", "entity.name.constant", "variable.other.constant"), c.constant))
            .put(rule(listOf("constant.numeric"), c.number))
            .put(rule(listOf("keyword", "storage.type", "storage.modifier", "keyword.control", "keyword.other"), c.keyword))
            .put(rule(listOf("keyword.operator"), c.operator))
            .put(rule(listOf("entity.name.function", "support.function", "meta.function.call", "meta.function.definition"), c.function))
            .put(rule(listOf("entity.name.type", "entity.name.type.instance", "support.type", "support.class", "entity.name.struct"), c.type))
            .put(rule(listOf("entity.name.enum", "entity.name.enum.variant", "variable.other.enummember"), c.enumMember))
            .put(rule(listOf("entity.name.macro", "meta.macro", "support.macro"), c.macro))
            .put(rule(listOf("variable.parameter", "variable.other", "variable", "meta.parameter"), c.variable))
            .put(rule(listOf("entity.name.attribute", "meta.attribute", "meta.annotation"), c.attribute))
            .put(rule(listOf("lifetime"), c.lifetime))
            .put(rule(listOf("punctuation", "meta.brace", "meta.bracket", "meta.paragraph"), c.punctuation))
            .put(rule(listOf("invalid"), c.invalid))
            .put(rule(listOf("source"), c.plain))
        val json = JSONObject(baseJson)
        json.put("name", theme.registryName(dark))
        json.getJSONObject("colors").put("editor.foreground", hex(c.plain))
        json.put("tokenColors", tokens)
        return json.toString()
    }
}
