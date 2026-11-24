# R4A (auditoría M10): R8 está ACTIVADO en release. Las librerías que traen
# sus propias reglas (SDK de Anthropic, kotlinx.serialization, OkHttp, Compose)
# no necesitan nada acá; abajo va lo que usa reflexión o JNI.

# --- Phase 4: vendored terminal modules (com.termux.*) -------------------
# The JNI bridge class must survive shrinking with its native methods
# bound, or the library load fails at runtime with UnsatisfiedLinkError
# in minified builds (plan §4.2; review P3-11 — inert today while
# isMinifyEnabled = false, landed in implementation step 1).
-keepclasseswithmembernames class com.termux.terminal.JNI {
    native <methods>;
}

# termux.c (the only vendored native code) never calls back into Java;
# there are no native-invoked Java callbacks to keep today. If a patch
# ever adds a JNIEnv callback, its keep rule belongs HERE — review this
# block when touching terminal-emulator/src/main/jni/.

# --- R4A: game host (miniquad) ---------------------------------------------
# miniquad's Rust side binds quad_native.QuadNative's natives by name and
# calls GameActivity methods (showKeyboard, setFullScreen, clipboard) by name.
-keep class quad_native.QuadNative { native <methods>; }
-keep class dev.rustdroid.ide.game.GameActivity { public <methods>; }
# Everything in the game host is reached from native code (miniquad) or by
# name: keep the whole packages, not just the methods listed above.
-keep class quad_native.** { *; }
-keep class dev.rustdroid.ide.game.** { *; }

# --- R4A: editor (sora-editor + TextMate) ------------------------------------
# tm4e reads grammars and themes with Gson into these model classes by
# reflection; renaming their fields breaks syntax colors silently.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
# The Oniguruma regex engine behind tm4e (joni + jcodings) looks up its
# encodings and tables by class name: renamed, the editor crashed on open
# (ExceptionInInitializerError in OnigRegExp). snakeyaml-engine is how tm4e
# reads YAML grammars.
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-keep class org.snakeyaml.engine.** { *; }

# --- R4A: Exportar APK (apksig) ----------------------------------------------
# apksig's ASN.1 encoder/decoder reads annotated fields by reflection
# (com.android.apksig.internal.asn1); renamed, signing failed with
# "Failed to sign using signer R4A".
-keep class com.android.apksig.** { *; }

# --- R4A: Anthropic SDK -----------------------------------------------------
# OpenTelemetry (pulled in by the SDK) names this annotation, which is not on
# the classpath; R8 stops with "Missing class" otherwise.
-dontwarn com.google.auto.value.AutoValue
