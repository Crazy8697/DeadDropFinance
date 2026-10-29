// The shared module: Dead Drop Finance's logic (data model, database, envelopes, transactions, CSV import, rules,
// calculations), built twice from one source folder: for the phone app (Android target, Room on the phone's SQLCipher)
// and for the desktop program (JVM target, Room on encrypted SQLite). See shared/README.md.
//
// The code lives in src/jvmShared/kotlin, which both targets compile as their own platform code, so it keeps using
// java.time and org.json (Android's own org.json on the phone, org.json:json on the desktop) exactly as it did inside
// the app. Only the transaction call differs: src/androidMain and src/jvmMain each hold that one small file.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("com.google.devtools.ksp")
}

kotlin {
    android {
        namespace = "net.darkrelay.deaddropfinance.shared"
        // the app's own SDK platform (app/build.gradle.kts)
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
        val shared = "src/jvmShared/kotlin"
        androidMain {
            kotlin.srcDir(shared)
            dependencies {
                implementation("androidx.room:room-runtime:2.8.5")
                // libsodium for server sync's encrypted envelopes (1.4.0): the Android build of lazysodium, whose aar
                // carries libsodium for each ABI, and JNA's Android aar, which it calls through
                implementation("com.goterl:lazysodium-android:5.2.0@aar")
                implementation("net.java.dev.jna:jna:5.19.1@aar")
            }
        }
        jvmMain {
            kotlin.srcDir(shared)
            dependencies {
                api("androidx.room:room-runtime:2.8.5")
                // the desktop's encrypted SQLite: SQLite3 Multiple Ciphers in its SQLCipher 4 mode (the phone uses SQLCipher
                // through the app), and JNA for Windows DPAPI, which protects its key
                implementation("io.github.willena:sqlite-jdbc:3.53.4.0")
                implementation("net.java.dev.jna:jna-platform:5.19.1")
                // the phone gets org.json from Android itself
                implementation("org.json:json:20260814")
                // server sync compiles against lazysodium; the desktop program doesn't sync yet, so it doesn't ship it
                compileOnly("com.goterl:lazysodium-java:5.2.0")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                implementation("com.goterl:lazysodium-java:5.2.0")
                implementation("net.java.dev.jna:jna:5.19.1")
            }
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnit() }

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.8.5")
    add("kspJvm", "androidx.room:room-compiler:2.8.5")
}

// Both targets export the schema to app/schemas, where every version since 1 is committed. They write the same file,
// so a desktop schema that differed from the phone's would show up as a change there.
ksp {
    arg("room.schemaLocation", rootProject.file("app/schemas").path)
    arg("room.generateKotlin", "true")
}
