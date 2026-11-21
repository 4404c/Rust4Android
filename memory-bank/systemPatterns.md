# System Patterns

Código Android en `android/app/src/main/java/dev/rustdroid/ide/` (abajo, rutas relativas a esa carpeta). Los cambios propios llevan comentario `R4A:`.

## El truco central (heredado de RustDroid)
- **Toolchain bundle** (~113 MB, Rust 1.85.0) bajada del release de GitHub de upstream (URL fija en `toolchain/ToolchainDistro.kt`), instalada en `files/usr`.
- **targetSdk 28** → la app corre en el dominio SELinux `untrusted_app_27`, que todavía permite ejecutar binarios desde los datos de la app.
- Linker falso `cc` (shim `rustdroid-link`) que llama a `rust-lld`; `libc++_shared` vía `LD_LIBRARY_PATH`.
- Proyectos en almacenamiento compartido (noexec): `CARGO_TARGET_DIR` redirigido a la app.
- Terminal: BusyBox. R4A trae su propio busybox 1.36.1 parcheado (`assets/busybox/busybox`, parche `busybox/patches/0018-lineedit-sigwinch-no-new-prompt.patch`, arregla el prompt duplicado al redimensionar), instalado por `toolchain/BusyboxOverlay.kt`.
- `toolchain/ToolchainManager.kt` además escribe en `config.toml` de cargo `[resolver] incompatible-rust-versions = "fallback"` (resolver MSRV-aware: elige versiones de crates compatibles con 1.85).

## UI (Jetpack Compose)
- `ui/editor/EditorScreen.kt`: pantalla principal. Drawer propio `ui/components/EdgeDrawer.kt` (sigue al dedo, Animatable acotado 0..1, franja de borde 20dp, ancho `min(70%, 300dp)`). Pestañas con ícono por tipo de archivo (long-press cierra). `SymbolBar` entre editor y panel. Panel Console/Problems plegable (`panelCollapsed`). Barra superior: ⓘ, deshacer, rehacer, ▶/■.
- `ui/editor/EditorViewModel.kt`: estado del editor, guardado, `scheduleLiveCheck`, salir con "¿guardar?".
- `ui/editor/CodeEditorPane.kt`: integración con **sora-editor 0.23.6** (`RustEditor` con warmUp/ensureInit, colores de problemas y del popup de autocompletado, `R4ADiagnosticTooltip`, `LiveSquiggles`, `LineFlash`).
- `ui/deps/` (Deps), `ui/terminal/TerminalScreen.kt`, `ui/settings/SettingsScreen.kt` (idioma, apariencia: íconos de archivo, revisar errores mientras escribo).
- `ui/components/RdIcons.kt` (íconos desde path data), `ui/components/FileTypeIcon.kt` (engranaje de Rust con "R" dibujada en Canvas con tamaño en px).
- Tema: `ui/theme/Color.kt` (violeta `0xFF9C6BFF` como primario — las variables todavía se llaman `RustOrange*`), `ui/theme/Theme.kt`.

## Runtime
- `runtime/ConsoleClassifier.kt`: clasifica líneas (OUTPUT/STATUS/ERROR/WARNING/NOTE/NOISE/SYSTEM/STDERR); `KnownNoise` oculta avisos conocidos.
- **Errores en vivo**: `runtime/LiveChecker.kt` copia el proyecto a una sombra (`files/check/<hash>/src-copy`, sólo lo que cambió, sin `target/` ni `.git`), escribe el texto sin guardar del editor y corre `cargo check --message-format=json --offline` con target propio. Nunca toca la carpeta del usuario. `runtime/CargoCheckParser.kt` convierte el JSON en `LiveDiagnostic` + `LiveFix` (sólo sugerencias MachineApplicable/MaybeIncorrect/Unspecified). Debounce ~1 s.

## Datos / config
- `util/AppLanguage.kt`: ES/EN en SharedPreferences `r4a_prefs`, clave `language`; se aplica en `attachBaseContext`.
- `util/AppSettings.kt`: StateFlows `fileIcons`, `liveCheck`, `autoComplete` (todos true por defecto).
- `projects/CratesIoClient.kt`, `CrateVersions.kt` (elige versión compatible), `PopularCrates.kt`, `ProjectRepository.kt`.
- Strings: `res/values/strings.xml` (español, por defecto) y `res/values-en/strings.xml`.

## Patrones / decisiones
- Lógica pura separada y testeada en JVM (parsers, clasificador, versiones, árbol de archivos).
- Insets: `consumeWindowInsets` antes de `imePadding`, o `ime.exclude(navigationBars)`, para no duplicar el espacio de la barra de navegación arriba del teclado.
- Colores del editor que sora pisa al hacer `attachEditor` → van en el JSON del tema (`assets/textmate/theme-*.json`) o se aplican después de `setColorScheme`.
