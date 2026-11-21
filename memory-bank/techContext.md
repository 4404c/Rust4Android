# Tech Context

## Tecnologías
- Kotlin + Jetpack Compose (Material 3), Gradle Kotlin DSL (`android/app/build.gradle.kts`).
- sora-editor 0.23.6 con TextMate (gramática Rust, temas en `assets/textmate/`).
- kotlinx.serialization (JSON de cargo y crates.io).
- Toolchain Rust 1.85.0 (aarch64 Android) del release de upstream; BusyBox 1.36.1 parcheado.
- Módulo `android/terminal-emulator` (con `host-tests/tests.c`).

## Entorno de desarrollo (Windows)
- Build release (rápido de abrir, ~0,35 s vs 1,25 s debug):
  ```
  cd android && ./gradlew :app:assembleRelease
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```
- El release está **firmado con la clave debug** (provisorio, hay un TODO para clave real antes de publicar).
- Tests unitarios: en Windows algunos fallan por symlinks/POSIX → correr los puntuales (`./gradlew :app:testDebugUnitTest --tests '*CargoCheckParserTest*'`). El CI (`.github/workflows/android.yml`, Linux) corre todos.
- Celular de pruebas: moto g54 5G (Android 15, 1080×2400), serial `ZY22K3LM74`, por adb.
  - En Git Bash usar `export MSYS_NO_PATHCONV=1` antes de comandos adb con rutas.
  - `uiautomator dump` para encontrar coordenadas; scripts temporales se suben a `/sdcard/Download`, se corren en la terminal de la app y se borran.
  - Capturas de comparación en `comparacion/celular/` (excluida de git vía `.git/info/exclude`).
- Emulador x86: sirve para ver UI, pero no compila (la traducción ARM falla con busybox/rust-lld).

## Restricciones técnicas
- Package `dev.rustdroid.ide` fijo (ruta de la toolchain).
- targetSdk 28 → problema para Play Store (pide targetSdk alto). Idea investigada: llevar binarios como `jniLibs` (se extraen a `nativeLibraryDir`, que sí es ejecutable).
- Crates que piden Rust > 1.85 no compilan: el resolver con fallback y `CrateVersions.pickCompatible` lo mitigan.
- rust-analyzer descartado por pesado; los errores en vivo usan `cargo check`.
- `cargo check` en vivo corre `--offline`: si falta bajar una dependencia, hay que hacer Fetch/Run primero.

## Licencias
- RustDroid: MIT. BusyBox: GPLv2 → la pantalla de licencias apunta al source de upstream, que **no** incluye el parche 0018; actualizar antes de publicar.
- Revisar uso de la marca "Rust" antes de Play Store.
