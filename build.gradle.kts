// Kotlin is pinned above AGP 9's built-in 2.2.10 with the buildscript classpath override from the
// AGP 9.0 release notes. The Compose compiler plugin ships with Kotlin and must match it exactly.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
