import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val osName = System.getProperty("os.name")
val isArm64 = System.getProperty("os.arch") in setOf("aarch64", "arm64")
val javafxPlatform = when {
    osName.startsWith("Windows", ignoreCase = true) -> "win"
    osName.startsWith("Mac", ignoreCase = true) -> if (isArm64) "mac-aarch64" else "mac"
    else -> if (isArm64) "linux-aarch64" else "linux"
}

kotlin {
    jvm {}
    sourceSets {
        jvmMain.dependencies {
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.collections.immutable)
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            for (component in listOf("base", "graphics", "controls", "swing", "media")) {
                implementation("org.openjfx:javafx-$component:21.0.10:$javafxPlatform") {
                    isTransitive = false
                }
            }
            implementation(project(":shared"))
            implementation(project(":library"))
        }
    }
}

// java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream4.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            modules("java.desktop", "java.logging", "java.net.http", "java.prefs", "jdk.unsupported")
            packageName = "CloudStream"
            packageVersion = "1.0.0"
            windows {
                iconFile.set(project.file("src/desktop-icons/icon-windows.ico"))
                shortcut = true
            }
            linux {
                iconFile.set(project.file("src/desktop-icons/icon-linux.png"))
            }
        }
    }
}
