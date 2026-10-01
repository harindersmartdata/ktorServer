plugins {
    id("com.android.application") version "8.8.2" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
    // Compose Multiplatform 1.8.2 is the stable release built against Kotlin 2.1.20.
    // 1.9.x requires Kotlin 2.2 — do not bump one without the other.
    id("org.jetbrains.compose") version "1.8.2" apply false
    id("com.google.devtools.ksp") version "2.1.20-2.0.1" apply false
    id("androidx.room") version "2.7.2" apply false
}
