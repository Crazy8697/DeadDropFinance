import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Copies docs/IMPORT.md into the app's assets (not assets/licenses, so it never shows on the Licenses screen), so
// Export example (Import setup, 0.15.1) can pull its invented example straight out of the one doc that describes
// the format, instead of keeping a second copy that could drift out of sync.
abstract class CopyDocAsset : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("docs")
        out.mkdirs()
        source.get().asFile.copyTo(out.resolve(source.get().asFile.name), overwrite = true)
    }
}

// Copies LICENSE, NOTICE, THIRD-PARTY-NOTICES.md and the texts in licenses/ from the repo root into the app's
// assets/licenses, so the repo files are the one source. The About screen that shows them comes in Stage 15.
abstract class CopyLicenseAssets : DefaultTask() {
    @get:InputFiles
    abstract val files: ConfigurableFileCollection

    @get:InputDirectory
    abstract val licenseTexts: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.resolve("licenses")
        out.deleteRecursively()
        out.mkdirs()
        files.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
        licenseTexts.get().asFile.listFiles()?.filter { it.isFile }?.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}

// Release signing (docs/SIGNING.md). The key and its keystore.properties live outside the repo; the gitignored
// local.properties says where, with ddf.signing.properties=<path to keystore.properties>. Debug builds use the
// default debug key and never need it. A release build without a usable key fails: it is never left unsigned
// and never signed with the debug key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val signingProps = Properties()
val signingProblem: String? = run {
    val path = localProps.getProperty("ddf.signing.properties")?.trim().orEmpty()
    if (path.isEmpty()) return@run "local.properties has no ddf.signing.properties line"
    val propsFile = file(path)
    if (!propsFile.isFile) return@run "ddf.signing.properties points at a file that does not exist"
    propsFile.inputStream().use { signingProps.load(it) }
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { signingProps.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) return@run "keystore.properties is missing ${missing.joinToString()}"
    if (!file(signingProps.getProperty("storeFile")).isFile) return@run "the storeFile named in keystore.properties does not exist"
    null
}

// The version is set once, in gradle.properties (ddf.versionName). versionCode always rises with it:
// major * 10000 + minor * 100 + patch, so minor and patch each stay below 100.
val appVersionName = providers.gradleProperty("ddf.versionName").get()
val appVersionCode = run {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(appVersionName)
        ?: error("ddf.versionName must look like 1.2.3, got '$appVersionName'")
    val (major, minor, patch) = m.destructured.toList().map { it.toInt() }
    require(minor < 100 && patch < 100) { "ddf.versionName: minor and patch must be below 100" }
    major * 10000 + minor * 100 + patch
}

val realUpdateUrl = "https://api.github.com/repos/Crazy8697/DeadDropFinance/releases/latest"
val debugUpdateUrl = providers.gradleProperty("ddf.updateUrl").orNull?.also {
    require(Regex("""^https?://[A-Za-z0-9.:/_-]+$""").matches(it)) { "ddf.updateUrl: not a plain URL" }
} ?: realUpdateUrl
val debugFakeVersion = providers.gradleProperty("ddf.fakeVersion").orNull?.also {
    require(Regex("""^\d+\.\d+\.\d+$""").matches(it)) { "ddf.fakeVersion must look like 1.2.3" }
} ?: ""

android {
    namespace = "net.darkrelay.deaddropfinance"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "net.darkrelay.deaddropfinance"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Dead Drop Finance")
        // The native libraries (SQLCipher, Tesseract) for ARM phones and 64-bit x86 (ChromeOS); 32-bit x86 is left out,
        // which keeps the APK smaller.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Native libraries are stored compressed in the APK and unpacked at install: a much smaller download.
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        if (signingProblem == null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                // APK Signature Scheme v3: covers every Android version from 9 up (so all of minSdk 33 and later,
                // which is why the build leaves out v1 and v2) and lets the key be rotated later
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        // The update check's URL and the installed version it compares against. A release build always uses the
        // real GitHub URL and the real versionName. Only a debug build can be pointed elsewhere for testing, with
        // -Pddf.updateUrl=<https url> and -Pddf.fakeVersion=<x.y.z>.
        debug {
            // installs beside the release app and never touches it or its data
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "DDF Debug")
            buildConfigField("String", "UPDATE_URL", "\"$debugUpdateUrl\"")
            buildConfigField("String", "VERSION_OVERRIDE", "\"$debugFakeVersion\"")
        }
        release {
            buildConfigField("String", "UPDATE_URL", "\"$realUpdateUrl\"")
            buildConfigField("String", "VERSION_OVERRIDE", "\"\"")
            // null when the key is missing; checkReleaseSigning below then stops the build before packaging
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            // no META-INF/version-control-info.textproto (it carries the private commit hash)
            vcsInfo {
                include = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "verification"
    description = "Fails a release build that has no usable release key."
    val problem = signingProblem
    doLast {
        if (problem != null) {
            throw GradleException("Release signing is not set up: $problem. Release builds are never unsigned or " +
                "debug-signed. See docs/SIGNING.md.")
        }
    }
}
// Privacy gate (tools/privacy_check.py). On the owner's build machine local.properties holds
// ddf.privacy.gate=true: a release build then refuses on any privacy finding, in the sources and public set before
// packaging and in the finished APK after it (the APK is deleted). Without that line (a public clone, no Python,
// no tools/privacy-local.txt) the gate is skipped and says so.
val privacyGateOn = localProps.getProperty("ddf.privacy.gate")?.trim() == "true"
val privacyScript = rootProject.file("tools/privacy_check.py")
val releaseApkDir = layout.buildDirectory.dir("outputs/apk/release")

fun privacyRefused(where: String) = GradleException(
    "Privacy check found something in $where (see the lines above). The release build is refused and no " +
        "release APK was kept. Fix it, or see tools/privacy_check.py.")

val privacyGateSources = tasks.register<Exec>("privacyGateSources") {
    group = "verification"
    description = "Release gate: tools/privacy_check.py --tree --public."
    onlyIf("ddf.privacy.gate=true in local.properties") { privacyGateOn }
    workingDir = rootDir
    commandLine("python", "-B", privacyScript.path, "--tree", "--public")
    isIgnoreExitValue = true
    val apkDir = releaseApkDir
    doLast {
        if (executionResult.get().exitValue != 0) {
            // an older release APK from an earlier build must not survive a refused one
            apkDir.get().asFile.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
            throw privacyRefused("the sources or the public set")
        }
    }
}
val privacyGateApk = tasks.register<Exec>("privacyGateApk") {
    group = "verification"
    description = "Release gate: tools/privacy_check.py --apk on the release APK; deletes the APK on a finding."
    onlyIf("ddf.privacy.gate=true in local.properties") { privacyGateOn }
    workingDir = rootDir
    val apkDir = releaseApkDir
    commandLine("python", "-B", privacyScript.path, "--apk",
        apkDir.get().file("app-release.apk").asFile.path)
    isIgnoreExitValue = true
    doLast {
        if (executionResult.get().exitValue != 0) {
            apkDir.get().asFile.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
            throw privacyRefused("the release APK")
        }
    }
}
if (!privacyGateOn) logger.info("DeadDropFinance: privacy gate off (no ddf.privacy.gate=true in local.properties)")

val copyLicenseAssets = tasks.register<CopyLicenseAssets>("copyLicenseAssets") {
    files.from(
        rootProject.file("LICENSE"), rootProject.file("NOTICE"), rootProject.file("THIRD-PARTY-NOTICES.md"),
        rootProject.file("PRIVACY.md"), rootProject.file("TERMS.md"),
    )
    licenseTexts.set(rootProject.layout.projectDirectory.dir("licenses"))
}
val copyImportDoc = tasks.register<CopyDocAsset>("copyImportDoc") {
    source.set(rootProject.file("docs/IMPORT.md"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicenseAssets, CopyLicenseAssets::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(copyImportDoc, CopyDocAsset::outputDir)
    }
}

// License gate, switched on by the same ddf.privacy.gate=true line: a release build fails if any component it
// ships (group:artifact on the release runtime classpath) is not listed in THIRD-PARTY-NOTICES.md.
val licenseGate = tasks.register("licenseGate") {
    group = "verification"
    description = "Release gate: every shipped component is covered in THIRD-PARTY-NOTICES.md."
    onlyIf("ddf.privacy.gate=true in local.properties") { privacyGateOn }
    val notices = rootProject.file("THIRD-PARTY-NOTICES.md")
    val root = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    val apkDir = releaseApkDir
    inputs.file(notices)
    doLast {
        val covered = Regex("`([A-Za-z0-9_.\\-]+:[A-Za-z0-9_.\\-]+)`").findAll(notices.readText())
            .map { it.groupValues[1] }.toSet()
        val shipped = sortedSetOf<String>()
        val seen = mutableSetOf<ResolvedComponentResult>()
        fun walk(c: ResolvedComponentResult) {
            if (!seen.add(c)) return
            (c.id as? ModuleComponentIdentifier)?.let { shipped += "${it.group}:${it.module}" }
            c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        walk(root.get())
        val missing = shipped - covered
        if (missing.isNotEmpty()) {
            // an older release APK from an earlier build must not survive a refused one
            apkDir.get().asFile.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { it.delete() }
            throw GradleException("License check: these shipped components are not in THIRD-PARTY-NOTICES.md: " +
                missing.joinToString() + ". Add each one with its version, license and upstream URL. The release " +
                "build is refused.")
        }
        logger.lifecycle("License check: all ${shipped.size} shipped components are covered in THIRD-PARTY-NOTICES.md")
    }
}

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease" || name == "signReleaseBundle") {
        dependsOn(checkReleaseSigning)
        dependsOn(privacyGateSources)
        dependsOn(licenseGate)
    }
    if (name == "packageRelease") finalizedBy(privacyGateApk)
    if (name == "assembleRelease") dependsOn(privacyGateApk)
}
privacyGateApk.configure { mustRunAfter("packageRelease") }

// Room writes the schema of every database version to app/schemas (committed), so later stages can test
// real migrations against it. Destructive migration is never allowed (docs/DATABASE.md).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    // the encrypted database: Room on SQLCipher through its SupportOpenHelperFactory
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    // app lock timing: how long the whole app has been in the background
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    // bill reminders in the background (periodic)
    implementation("androidx.work:work-runtime:2.12.0")
    // Receipt text recognition on the phone: Tesseract OCR, Leptonica, libjpeg and libpng, wrapped for Android.
    // No network code and no permissions (checked in the AAR); the English model is in assets/tessdata.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // test only, never packaged into the app
    testImplementation("junit:junit:4.13.2")
    // the real org.json for the update tests (the one in android.jar is a stub off-device)
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.room:room-testing:2.8.5")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // on-device encryption check (instrumented, run by hand on a phone; never shipped)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

// The live check against the real GitHub release runs only when asked for: -Pddf.liveCheck=true
tasks.withType<Test>().configureEach {
    systemProperty("ddf.liveCheck", providers.gradleProperty("ddf.liveCheck").getOrElse("false"))
    // Roborazzi screenshots of every screen, only when asked for: -Pddf.screens=true
    val screens = providers.gradleProperty("ddf.screens").getOrElse("false")
    systemProperty("ddf.screens", screens)
    systemProperty("roborazzi.test.record", screens)
    // Robolectric on JDK 21 reaches into these for SharedPreferences and files
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
}
