# Product Context

## Por qué existe
Aprender Rust exige una PC con la toolchain instalada. En Android no hay un "Pydroid de Rust" cómodo: RustDroid (el original) demuestra que se puede compilar en el celular, pero su UX es básica. R4A toma ese motor y lo convierte en un IDE agradable.

## Qué problemas resuelve
- Compilar y correr Rust offline en el teléfono (toolchain Rust 1.85.0 dentro de la app).
- Entender los errores del compilador sin pelearse con texto rojo: errores subrayados mientras escribís, con explicación y botón para aplicar el arreglo que sugiere rustc.
- Manejar dependencias (crates) sin editar `Cargo.toml` a mano: pantalla **Deps** con buscador y populares, que elige una versión compatible con Rust 1.85.
- Terminal real (BusyBox) para quien quiera ir más allá.

## Cómo debería funcionar (flujo del usuario)
1. Primera apertura: descarga e instala la toolchain (~113 MB zip → ~467 MB instalada).
2. Crea o abre un proyecto (cargo).
3. Editor con pestañas, resaltado (TextMate), autocompletado, barra de símbolos arriba del teclado, deshacer/rehacer.
4. Deslizar desde el borde izquierdo abre el drawer: árbol **Files**, y abajo **Deps**, **Terminal**, **Opciones**, **Salir del proyecto**.
5. ▶ corre `cargo run`; abajo, panel plegable **Console / Problems**. Tocar un problema salta a la línea con un destello.
6. Mientras escribís, `cargo check` en segundo plano marca errores (rojo) y warnings (amarillo); tocar el subrayado muestra la caja con el mensaje y quick fixes. El botón ⓘ activa/desactiva las sugerencias (autocompletado).

## Metas de UX
- Arranque rápido (release ~0,35 s en frío).
- Todo usable con una mano y con teclado abierto (nada tapado, sin espacios dobles por insets).
- Consola limpia: ruido conocido oculto, colores por tipo de línea.
- Visual coherente: tema oscuro con violeta como color principal.
