package dev.rustdroid.ide.ui.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * R4A: temas de colores del código (Opciones → Colores del código).
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
        CodeTheme(
            "crab",
            dark = SyntaxColors(
                comment = 0xFF6F8A55, string = 0xFFE8D47A, constant = 0xFFC4E88A, number = 0xFFF0B86E,
                keyword = 0xFF9CD634, operator = 0xFFD8E6C8, function = 0xFF7FE0B0, type = 0xFF5FD0C0,
                enumMember = 0xFFA6E3E9, macro = 0xFFD4F28C, variable = 0xFFE6F0D6, attribute = 0xFFB5C98E,
                lifetime = 0xFFF0B86E, punctuation = 0xFFA9B89A, invalid = 0xFFFF5C5C, plain = 0xFFE6F0D6,
            ),
            light = SyntaxColors(
                comment = 0xFF7A8A68, string = 0xFF9A6B00, constant = 0xFF2F5214, number = 0xFFB5541A,
                keyword = 0xFF3F6E14, operator = 0xFF3A4A2A, function = 0xFF1F7A5A, type = 0xFF0E7C70,
                enumMember = 0xFF116A80, macro = 0xFF5C941E, variable = 0xFF1E360C, attribute = 0xFF6B7A2E,
                lifetime = 0xFFB5541A, punctuation = 0xFF4A5A3A, invalid = 0xFFC62828, plain = 0xFF1E2A14,
            ),
        ),
        CodeTheme(
            "sunset",
            dark = SyntaxColors(
                comment = 0xFF8C7A6B, string = 0xFFF2C46D, constant = 0xFFFF9E64, number = 0xFFFF9E64,
                keyword = 0xFFFF6F91, operator = 0xFFE8D5C4, function = 0xFFFFC371, type = 0xFFF7A072,
                enumMember = 0xFFFFB4A2, macro = 0xFFFF8FA3, variable = 0xFFF2E2D2, attribute = 0xFFD4A373,
                lifetime = 0xFFF7A072, punctuation = 0xFFBFAE9F, invalid = 0xFFFF5555, plain = 0xFFF2E2D2,
            ),
            light = SyntaxColors(
                comment = 0xFF9C8574, string = 0xFFA0522D, constant = 0xFFC2410C, number = 0xFFC2410C,
                keyword = 0xFFBE185D, operator = 0xFF4A3B30, function = 0xFFB45309, type = 0xFF9A3412,
                enumMember = 0xFF9D174D, macro = 0xFFDB2777, variable = 0xFF3F2A1E, attribute = 0xFF92400E,
                lifetime = 0xFF9A3412, punctuation = 0xFF5C4A3D, invalid = 0xFFDC2626, plain = 0xFF2E2018,
            ),
        ),
        CodeTheme(
            "ocean",
            dark = SyntaxColors(
                comment = 0xFF5C7C8A, string = 0xFF7FD1B9, constant = 0xFF82AAFF, number = 0xFFF78C6C,
                keyword = 0xFF4FC3F7, operator = 0xFFB0C4D0, function = 0xFF82AAFF, type = 0xFFFFCB6B,
                enumMember = 0xFF89DDFF, macro = 0xFF80CBC4, variable = 0xFFD6E4EC, attribute = 0xFF80CBC4,
                lifetime = 0xFFFFCB6B, punctuation = 0xFF8FA5B2, invalid = 0xFFFF5370, plain = 0xFFD6E4EC,
            ),
            light = SyntaxColors(
                comment = 0xFF6A8794, string = 0xFF0F766E, constant = 0xFF1D4ED8, number = 0xFFC2410C,
                keyword = 0xFF0369A1, operator = 0xFF334155, function = 0xFF1E40AF, type = 0xFFA16207,
                enumMember = 0xFF0E7490, macro = 0xFF0F766E, variable = 0xFF0F2A3A, attribute = 0xFF0F766E,
                lifetime = 0xFFA16207, punctuation = 0xFF475569, invalid = 0xFFDC2626, plain = 0xFF0F172A,
            ),
        ),
        CodeTheme(
            "plain",
            dark = SyntaxColors(
                comment = 0xFF6B6B6B, string = 0xFFBDBDBD, constant = 0xFFE0E0E0, number = 0xFFE0E0E0,
                keyword = 0xFF9CD634, operator = 0xFF9E9E9E, function = 0xFFFFFFFF, type = 0xFFD0D0D0,
                enumMember = 0xFFD0D0D0, macro = 0xFF9CD634, variable = 0xFFCFCFCF, attribute = 0xFF8A8A8A,
                lifetime = 0xFFBDBDBD, punctuation = 0xFF8A8A8A, invalid = 0xFFFF5C5C, plain = 0xFFE0E0E0,
            ),
            light = SyntaxColors(
                comment = 0xFF9E9E9E, string = 0xFF555555, constant = 0xFF222222, number = 0xFF222222,
                keyword = 0xFF5C941E, operator = 0xFF666666, function = 0xFF000000, type = 0xFF333333,
                enumMember = 0xFF333333, macro = 0xFF5C941E, variable = 0xFF2A2A2A, attribute = 0xFF777777,
                lifetime = 0xFF555555, punctuation = 0xFF777777, invalid = 0xFFC62828, plain = 0xFF1A1A1A,
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
