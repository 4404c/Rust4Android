# Project Brief — R4A (Rust 4 Android)

## Qué es
IDE de Rust para Android, al estilo Pydroid: escribir, compilar y correr programas Rust **en el celular**, sin PC ni internet (salvo para bajar la toolchain y crates).

- Nombre del proyecto: **Rust 4 Android**. Nombre de la app: **R4A**.
- Fork de [majnoon40/RustDroid](https://github.com/majnoon40/RustDroid) (licencia MIT).
- Repo propio: https://github.com/4404c/Rust4Android (remote `origin`); el original es el remote `upstream`.
- Carpeta local: `C:\Users\Belca\OneDrive\Desktop\codeNaza\proyectos\RUST4ANDROID`.

## Objetivos
1. App usable y linda para aprender y practicar Rust en Android.
2. Diferenciales propios: errores en vivo con sugerencias/quick fixes, consola clara, buena UX táctil (drawer con el dedo, barra de símbolos, deshacer/rehacer).
3. A futuro: distribución en Play Store, posible agente de IA, monetización (a definir).

## Restricciones duras
- **NO cambiar el package name `dev.rustdroid.ide`.** La toolchain está compilada para vivir en `/data/data/dev.rustdroid.ide/files/usr`; cambiarlo obliga a recompilarla en CI (~2 h). Se decide juntos, más adelante.
- `targetSdk 28` es parte del truco que permite ejecutar binarios desde los datos de la app (ver systemPatterns.md). No subirlo sin plan.
- Idioma: la UI está en **español** por defecto, con inglés seleccionable en Opciones. Los nombres de funciones clave quedan en inglés (Files, Console, Problems, Deps, Terminal, New file, Fetch, Sources…); mensajes, diálogos y ajustes en español ("Opciones", "Salir del proyecto").

## Forma de trabajar
- Explicar todo en español rioplatense, con calma, en pasos chicos y verificables.
- Proponer el plan y esperar confirmación antes de cambios grandes.
- Si el código contradice la descripción, confiar en el código y avisar.
- Probar en el celular real antes de dar algo por hecho.
