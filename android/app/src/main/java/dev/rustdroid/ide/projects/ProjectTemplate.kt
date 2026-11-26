package dev.rustdroid.ide.projects

import androidx.annotation.StringRes
import dev.rustdroid.ide.R

/**
 * R4A: what `Nuevo proyecto` can start from. Every template is built with
 * `cargo new` first; then [mainFile] gets [code] and [dependencies] are
 * appended to Cargo.toml. [needsInternet] = it pulls crates, so the first
 * build downloads them.
 */
enum class ProjectTemplate(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    val isLib: Boolean = false,
    val dependencies: String = "",
    val code: String? = null,
) {
    CONSOLE("consola", R.string.tpl_console, R.string.tpl_console_desc),
    INTERACTIVE("interactivo", R.string.tpl_interactive, R.string.tpl_interactive_desc, code = INTERACTIVE_MAIN_RS),
    GAME(
        "juego", R.string.tpl_game, R.string.tpl_game_desc,
        dependencies = ProjectRepository.GAME_DEPENDENCIES,
        code = ProjectRepository.GAME_MAIN_RS,
    ),
    STRUCTS("structs", R.string.tpl_structs, R.string.tpl_structs_desc, code = STRUCTS_MAIN_RS),
    LIBRARY("biblioteca", R.string.tpl_library, R.string.tpl_library_desc, isLib = true, code = LIBRARY_LIB_RS);

    val needsInternet: Boolean get() = dependencies.isNotBlank()
    val mainFile: String get() = if (isLib) "src/lib.rs" else "src/main.rs"

    companion object {
        /** Loose match for the AI's `template` argument; unknown → [CONSOLE]. */
        fun fromId(raw: String): ProjectTemplate {
            val s = raw.lowercase()
            return when {
                "jueg" in s || "game" in s || "macroquad" in s -> GAME
                "interac" in s || "stdin" in s -> INTERACTIVE
                "struct" in s || "test" in s -> STRUCTS
                "lib" in s -> LIBRARY
                else -> CONSOLE
            }
        }
    }
}

private val INTERACTIVE_MAIN_RS = """
    |use std::io::{self, Write};
    |
    |// Un programa que te pregunta cosas: escribí en la consola y apretá Enter.
    |fn main() {
    |    let nombre = preguntar("¿Cómo te llamás?");
    |    println!("¡Hola, {nombre}!");
    |
    |    loop {
    |        let texto = preguntar("Decime un número (o \"salir\"):");
    |        if texto == "salir" {
    |            println!("¡Chau, {nombre}!");
    |            break;
    |        }
    |        match texto.parse::<i64>() {
    |            Ok(n) => println!("{n} al cuadrado es {}", n * n),
    |            Err(_) => println!("\"{texto}\" no es un número, probá de nuevo."),
    |        }
    |    }
    |}
    |
    |/// Muestra la pregunta y devuelve lo que escribiste, sin espacios de más.
    |fn preguntar(pregunta: &str) -> String {
    |    print!("{pregunta} ");
    |    io::stdout().flush().unwrap();
    |    let mut linea = String::new();
    |    io::stdin().read_line(&mut linea).unwrap();
    |    linea.trim().to_string()
    |}
    |""".trimMargin()

private val STRUCTS_MAIN_RS = """
    |// Un ejemplo con struct, impl y tests.
    |// ▶ corre main. Para los tests, en la Terminal: cargo test
    |
    |#[derive(Debug, Clone, PartialEq)]
    |struct Rectangulo {
    |    ancho: f64,
    |    alto: f64,
    |}
    |
    |impl Rectangulo {
    |    fn new(ancho: f64, alto: f64) -> Self {
    |        Rectangulo { ancho, alto }
    |    }
    |
    |    fn area(&self) -> f64 {
    |        self.ancho * self.alto
    |    }
    |
    |    fn es_cuadrado(&self) -> bool {
    |        self.ancho == self.alto
    |    }
    |}
    |
    |fn main() {
    |    let formas = vec![Rectangulo::new(3.0, 4.0), Rectangulo::new(5.0, 5.0)];
    |    for r in &formas {
    |        println!("{r:?} → área {}, ¿cuadrado? {}", r.area(), r.es_cuadrado());
    |    }
    |}
    |
    |#[cfg(test)]
    |mod tests {
    |    use super::*;
    |
    |    #[test]
    |    fn area_de_un_rectangulo() {
    |        assert_eq!(Rectangulo::new(3.0, 4.0).area(), 12.0);
    |    }
    |
    |    #[test]
    |    fn reconoce_un_cuadrado() {
    |        assert!(Rectangulo::new(2.0, 2.0).es_cuadrado());
    |        assert!(!Rectangulo::new(2.0, 3.0).es_cuadrado());
    |    }
    |}
    |""".trimMargin()

private val LIBRARY_LIB_RS = """
    |//! Una biblioteca: código para usar desde otros proyectos.
    |//! Probala en la Terminal con: cargo test
    |
    |/// Suma dos números.
    |pub fn sumar(a: i64, b: i64) -> i64 {
    |    a + b
    |}
    |
    |#[cfg(test)]
    |mod tests {
    |    use super::*;
    |
    |    #[test]
    |    fn suma() {
    |        assert_eq!(sumar(2, 2), 4);
    |    }
    |}
    |""".trimMargin()
