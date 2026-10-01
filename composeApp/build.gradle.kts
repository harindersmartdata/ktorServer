import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val ktorVersion = "3.1.3"
val roomVersion = "2.7.2"
val coroutines = "1.9.0"

kotlin {
    jvmToolchain(17)

    // Room generates the `actual` for AppDatabaseConstructor as an object; without
    // this flag every build warns that expect/actual classes are still Beta.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // Named "desktop" so the Gradle tasks read :composeApp:runDistributable etc.
    // This is a plain JVM target: it runs on Windows, macOS and Linux alike.
    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)

            // CIO is pure Kotlin — the one engine that works on Android and desktop.
            implementation("io.ktor:ktor-server-core:$ktorVersion")
            implementation("io.ktor:ktor-server-cio:$ktorVersion")
            implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
            implementation("io.ktor:ktor-server-cors:$ktorVersion")

            // The primary is also a client: it pushes changes to the secondary.
            // CIO is the one engine that works unchanged on Android and desktop.
            implementation("io.ktor:ktor-client-core:$ktorVersion")
            implementation("io.ktor:ktor-client-cio:$ktorVersion")
            implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

            // Room 2.7 ships multiplatform artifacts; sqlite-bundled supplies the
            // native SQLite build so desktop needs no system library.
            implementation("androidx.room:room-runtime:$roomVersion")
            implementation("androidx.sqlite:sqlite-bundled:2.5.2")

            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }

        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutines")
            implementation("org.slf4j:slf4j-simple:2.0.16")
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:$coroutines")
            implementation("org.slf4j:slf4j-simple:2.0.16")
        }
    }
}

android {
    namespace = "com.example.ktorserver"
    compileSdk = 35

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    defaultConfig {
        applicationId = "com.example.ktorserver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Release signing is optional: the APK builds unsigned when no keystore
        // is configured in local.properties, which keeps CI and fresh clones working.
        create("release") {
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            val store = props.getProperty("RELEASE_STORE_FILE")
            if (store != null && rootProject.file(store).exists()) {
                storeFile = rootProject.file(store)
                storePassword = props.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = props.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = props.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    // Ktor jars ship metadata files that collide when merged into an APK.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module",
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

// Room's Gradle plugin replaces the old ksp arg; it applies to every target.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // One KSP invocation per target — Room generates separate implementations.
    add("kspAndroid", "androidx.room:room-compiler:$roomVersion")
    add("kspDesktop", "androidx.room:room-compiler:$roomVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}

compose.desktop {
    application {
        mainClass = "com.example.ktorserver.MainKt"

        nativeDistributions {
            // Exe/Msi are produced by jpackage, which does NOT cross-compile:
            // these targets only build when Gradle runs on Windows.
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "TuCountServer"
            packageVersion = "1.0.0"
            description = "TuCount local HTTP server"

            windows {
                menu = true
                shortcut = true
                // Stable UUID — keeps upgrades replacing the install rather than
                // stacking a second copy. Generate a fresh one per product.
                upgradeUuid = "4F3D2A1B-8C7E-4A5B-9D6F-1E2C3B4A5D6E"
            }
        }
    }
}
