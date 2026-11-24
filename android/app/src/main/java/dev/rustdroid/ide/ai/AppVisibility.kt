package dev.rustdroid.ide.ai

/** R4A: ¿la app se está viendo? (MainActivity lo actualiza en onStart/onStop). */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}
