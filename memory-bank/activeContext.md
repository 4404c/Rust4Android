# Active Context

_Última actualización: 2026-11-21_

## Foco actual
Rama `r4a/errores-en-vivo`: **errores en vivo** mientras se escribe (estilo IDE de escritorio) + detalles de la caja de error y del autocompletado.

## Cambios recientes (sin commitear)
- Nuevos: `runtime/LiveChecker.kt`, `runtime/CargoCheckParser.kt`, `test/.../CargoCheckParserTest.kt`.
- Modificados: `CodeEditorPane.kt` (tooltip propio, subrayados, quick fixes, colores), `EditorScreen.kt` (ⓘ, cerrar ventanas al abrir drawer), `EditorViewModel.kt` (`scheduleLiveCheck`), `SettingsScreen.kt` + `AppSettings.kt` (opciones liveCheck/autoComplete), `RdIcons.kt` (Info), temas TextMate, strings ES/EN.
- Último feedback del usuario: "las sugerencias se ven bien".

## Próximos pasos
1. Commit de la rama y subirla / pasarla a `main` (preguntar antes).
2. Decidir si la caja de error debe abrirse sola al escribir dentro del error.
3. Verificar warnings amarillos en el celular.

## Decisiones activas
- Errores en vivo con `cargo check` sobre copia sombra (no rust-analyzer; nunca guardar a escondidas).
- Commits con prefijo `R4A:` y mensaje en español corto.
- Pendiente de respuesta: un `let 5;` en el `main.rs` del usuario (no se sabe si fue una prueba nuestra).
