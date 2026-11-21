# Progress

## Lo que funciona (en `main`, probado en el celular)
- Rebranding a **R4A**, color principal violeta, ícono/nombre.
- UI en español con inglés seleccionable (Opciones → Idioma).
- Consola mejorada: colores por tipo, ruido conocido oculto; tocar un problema salta a la línea con destello suave.
- Drawer desde el borde que sigue al dedo, sin pasarse; árbol **Files** con guías y carpetas plegables; abajo Deps, Terminal, Opciones, Salir del proyecto (con "¿guardar cambios?").
- Pestañas más grandes con ícono por tipo de archivo (opción para apagarlos); long-press cierra.
- Barra de símbolos arriba del teclado; sin espacios dobles por insets.
- Panel Console/Problems plegable; pestañas se ven apagadas al plegar.
- Terminal más linda y sin prompts duplicados (BusyBox parcheado dentro del APK).
- Llaves resaltadas visibles en tema oscuro; margen de números de línea.
- Deps: populares, ✓ en las agregadas, spinner sólo en la que se instala, versión compatible con Rust 1.85; archivos sin extensión visibles.
- Resolver MSRV-aware en cargo.
- Botones deshacer/rehacer arriba, al lado de ▶.
- Build release para arranque rápido.

## Errores en vivo (commit 108f857, ya en `main`)
- Errores en vivo (`LiveChecker` + `CargoCheckParser`, 5 tests) con subrayado y caja de error con quick fixes.
- Caja de error separada de los bordes, texto que no se sale, alto máximo 60 %.
- Abrir el drawer cierra la caja de error, el teclado y popups.
- Botón ⓘ para activar/desactivar autocompletado; colores del popup de autocompletado.
- Opción "Revisar errores mientras escribo" en Apariencia.
- Estado: el usuario confirmó que las sugerencias se ven bien.

## Lo que falta / ideas
- Clave de firma real para release.
- Distribución en Play Store (targetSdk, jniLibs, marca Rust, GPL de BusyBox con parche 0018).
- Agente de IA (idea), monetización (a definir).
- Sufijo "B" en contadores de descargas de crates.

## Problemas conocidos
- La caja de error puede abrirse sola al escribir dentro de una región con error (podría molestar).
- Warnings amarillos no verificados visualmente en el celular.
- Tests unitarios con symlinks fallan en Windows (en CI pasan).
