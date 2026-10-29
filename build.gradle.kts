// Kotlin is pinned above AGP 9's built-in 2.2.10 with the buildscript classpath override from the
// AGP 9.0 release notes. The Compose compiler plugin ships with Kotlin and must match it exactly.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
        // KSP for Room's annotation processing; KSP versions are independent of Kotlin since 2.3.0
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.12")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    // the shared module (:shared): the phone's logic, built for the phone app and the desktop program
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
}
