// The shared screens: the phone app's Compose screens (and AppRoot, which wires them to the shared logic), used by both
// the phone app and the desktop program (0.13.0, phone 1.8.0), so both show the same fields, confirms and wording.
// Built the way :shared is: src/uiShared/kotlin is compiled by each target as its own platform code, on Jetpack
// Compose for the phone (the app's own Compose BOM) and on Compose Multiplatform for the program (the program's own
// Compose version). The few calls that differ (Back, a picked file) are one small file each in src/androidMain and
// src/jvmMain. See ui/README.md.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    android {
        namespace = "net.darkrelay.deaddropfinance.ui"
        compileSdk {
            version = release(37) {
                minorApiLevel = 2
            }
        }
        minSdk = 33
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    sourceSets {
        val ui = "src/uiShared/kotlin"
        androidMain {
            kotlin.srcDir(ui)
            dependencies {
                implementation(project(":shared"))
                implementation(project.dependencies.platform("androidx.compose:compose-bom:2026.09.00"))
                implementation("androidx.activity:activity-compose:1.13.0")
                implementation("androidx.compose.material3:material3")
                implementation("androidx.compose.ui:ui")
            }
        }
        jvmMain {
            kotlin.srcDir(ui)
            dependencies {
                implementation(project(":shared"))
                implementation(compose.material3)
                implementation(compose.desktop.common)
                // the phone's update check reads JSON with org.json (Android's own on the phone)
                implementation("org.json:json:20260814")
            }
        }
    }
}
