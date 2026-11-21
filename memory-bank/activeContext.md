# Active Context

_Última actualización: 2026-11-21_

## Foco actual
Terminados los **errores en vivo** (commit `108f857`, en `main`). Sin tarea en curso: elegir lo próximo.

## Cambios recientes
- Errores en vivo: `runtime/LiveChecker.kt`, `runtime/CargoCheckParser.kt` (+ test), caja de error propia y quick fixes en `CodeEditorPane.kt`.
- Botón ⓘ (autocompletado on/off), opción "Revisar errores mientras escribo", abrir el drawer cierra caja/teclado/popups.
- Memory bank y `.cursorrules` creados.
- ▶ Ejecutar baja el teclado (commit `149be81`): en `EditorScreen.kt`, el botón llama `hideEditorWindows`, `hideSoftInput` y `keyboard.hide()` antes de `vm.run()`.

## Próximos pasos posibles
1. Decidir si la caja de error debe abrirse sola al escribir dentro del error.
2. Verificar warnings amarillos en el celular.
3. Clave de firma real / camino a Play Store.

## Decisiones activas
- Errores en vivo con `cargo check` sobre copia sombra (no rust-analyzer; nunca guardar a escondidas).
- Commits con prefijo `R4A:` y mensaje en español corto; ramas `r4a/<tema>` que se pasan a `main` con fast-forward.
- Pendiente de respuesta: un `let 5;` en el `main.rs` del usuario (no se sabe si fue una prueba nuestra).
