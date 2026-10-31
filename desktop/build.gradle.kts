import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Dead Drop Finance for Windows (the desktop program). Compose Desktop on a bundled Java runtime: createDistributable
// makes build/compose/binaries/main/app/DeadDropFinance (DeadDropFinance.exe, the app's jars and a jlink'd runtime),
// which desktop/build.ps1 packages with Inno Setup. Nothing here touches the phone app (:app).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// The desktop version is set once, in gradle.properties (ddf.desktop.versionName). Stage N of the build procedure
// ships 0.N.0. BuildInfo.kt is generated from it, and build.ps1 reads the same line for the installer.
val desktopVersion = providers.gradleProperty("ddf.desktop.versionName").get().also {
    require(Regex("""^\d+\.\d+\.\d+$""").matches(it)) { "ddf.desktop.versionName must look like 1.2.3, got '$it'" }
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val out = layout.buildDirectory.dir("generated/buildinfo")
    val version = desktopVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().asFile.resolve("net/darkrelay/deaddropfinance/desktop/BuildInfo.kt")
        f.parentFile.mkdirs()
        f.writeText(
            "package net.darkrelay.deaddropfinance.desktop\n\n" +
                "/** Generated from ddf.desktop.versionName in gradle.properties. */\n" +
                "object BuildInfo {\n    const val VERSION = \"$version\"\n}\n"
        )
    }
}

// The phone's own update check, copied unchanged into the desktop tests only, so a test can prove it still sees only
// phone releases on a GitHub that also carries desktop releases. Nothing of it ships, and the phone's files are only read.
val phoneUpdateSrc = tasks.register<Sync>("phoneUpdateSrc") {
    from(rootProject.file("app/src/main/java")) {
        include("net/darkrelay/deaddropfinance/UpdateClient.kt")
    }
    into(layout.buildDirectory.dir("generated/phone-update-src"))
}

// docs/IMPORT.md, PRIVACY.md and TERMS.md, in the program's resources (0.13.0), as the phone carries them: More, Import
// setup, Export the example saves IMPORT.md's example file; About shows the privacy policy and the end user agreement
val importDoc = tasks.register<Sync>("importDoc") {
    from(rootProject.file("docs/IMPORT.md"), rootProject.file("PRIVACY.md"), rootProject.file("TERMS.md"))
    into(layout.buildDirectory.dir("generated/import-doc/docs"))
}

// The phone's English text recognition model (app/src/main/assets/tessdata), the same file, in the installed program's
// resources folder (0.14.0): receipt text recognition reads it from there. Nothing is downloaded at run time.
val ocrModel = tasks.register<Sync>("ocrModel") {
    from(rootProject.file("app/src/main/assets/tessdata")) { include("eng.traineddata"); into("common/tessdata") }
    into(layout.buildDirectory.dir("generated/app-resources"))
}

// The shared module's in-memory Dead Drop server (its unit tests' FakeServer), copied unchanged into the desktop tests
// only, so the program's sync is tested against the same server rules with no network.
val fakeServerSrc = tasks.register<Sync>("fakeServerSrc") {
    from(rootProject.file("shared/src/jvmTest/kotlin")) { include("net/darkrelay/deaddropfinance/serversync/FakeServer.kt") }
    into(layout.buildDirectory.dir("generated/fake-server-src"))
}

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") { kotlin.srcDir(generateBuildInfo); resources.srcDir(importDoc.map { it.destinationDir.parentFile }) }
    sourceSets.named("test") { kotlin.srcDir(phoneUpdateSrc); kotlin.srcDir(fakeServerSrc) }
}

dependencies {
    // the phone app's logic (data model, database, budget rules), built for the JVM: see shared/README.md
    implementation(project(":shared"))
    // the phone app's screens (0.13.0), the same code the phone shows: see ui/README.md
    implementation(project(":ui"))
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    // the dark native title bar (0.9.0): one dwmapi call through JNA, which the program already ships (shared's DPAPI)
    implementation("net.java.dev.jna:jna:5.19.1")
    // the Add a phone QR code (0.10.0): ZXing's core library, which makes the code's modules; the program draws them
    implementation("com.google.zxing:core:3.5.3")
    // Receipt text recognition (0.14.0): Tesseract OCR 5.5.3 and Leptonica 1.87.0 built for 64-bit Windows, in Tess4J's
    // and Lept4J's jars (JNA wrappers; LoadLibs unpacks the two DLLs with commons-io). Their other dependencies (PDF
    // tools, image plugins, JBoss VFS) aren't used and don't ship. The model is the phone's (ocrModel).
    implementation("net.sourceforge.tess4j:tess4j:5.20.0") { isTransitive = false }
    implementation("net.sourceforge.lept4j:lept4j:1.24.0") { isTransitive = false }
    implementation("commons-io:commons-io:2.22.0")
    // PDF receipts (0.14.0): Apache PDFBox draws a PDF's pages, as Android's PdfRenderer does on the phone
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("junit:junit:4.13.2")
    // the phone's UpdateCheck reads JSON with org.json (built into Android); tests only
    testImplementation("org.json:json:20260814")
}

tasks.test {
    useJUnit()
    dependsOn(ocrModel)
    // the receipt reader finds the model where the installed program has it (compose.application.resources.dir)
    systemProperty("ddf.ocrResources", ocrModel.get().destinationDir.resolve("common").path)
    // screenshots of every screen into a folder, only when asked for: -Pddf.screens=<folder>
    systemProperty("ddf.screens", providers.gradleProperty("ddf.screens").getOrElse(""))
    systemProperty("ddf.screensRound", providers.gradleProperty("ddf.screensRound").getOrElse(""))
}

compose.desktop {
    application {
        mainClass = "net.darkrelay.deaddropfinance.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "DeadDropFinance"
            packageVersion = desktopVersion
            description = "Dead Drop Finance"
            vendor = "DarkRelay"
            copyright = "Copyright 2026 DarkRelay"
            licenseFile.set(rootProject.file("LICENSE"))
            // java.sql: the JDBC driver behind the encrypted budget (0.7.0)
            modules("java.desktop", "java.sql", "jdk.unsupported")
            // the receipt text model (0.14.0), in the installed program's app resources folder
            appResourcesRootDir.set(layout.buildDirectory.dir("generated/app-resources"))
            windows {
                iconFile.set(project.file("icons/deaddropfinance.ico"))
                // the launcher never opens a console window
                console = false
            }
        }
    }
}

// License gate (the phone's, for the desktop): createDistributable fails if any component the desktop program ships
// (group:artifact on the runtime classpath) is not listed in desktop/THIRD-PARTY-NOTICES.md.
val licenseGate = tasks.register("licenseGate") {
    group = "verification"
    description = "Every shipped desktop component is covered in desktop/THIRD-PARTY-NOTICES.md."
    val notices = project.file("THIRD-PARTY-NOTICES.md")
    val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    val ocrDir = ocrModel.get().destinationDir.resolve("common")
    dependsOn(ocrModel)
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
        // the files the program carries outside its jars (app resources: the text recognition model) are named too
        val bundled = ocrDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(ocrDir).invariantSeparatorsPath }.toSet()
        val missingFiles = bundled.filter { "`$it`" !in notices.readText() }
        if (missingFiles.isNotEmpty()) throw GradleException("License check: these bundled files are not in desktop/THIRD-PARTY-NOTICES.md: " +
            missingFiles.joinToString() + ". Add each one with its license and upstream URL.")
        val missing = shipped - covered
        if (missing.isNotEmpty()) {
            throw GradleException("License check: these shipped components are not in desktop/THIRD-PARTY-NOTICES.md: " +
                missing.joinToString() + ". Add each one with its version, license and upstream URL.")
        }
        logger.lifecycle("License check: all ${shipped.size} shipped desktop components and ${bundled.size} bundled files are covered in desktop/THIRD-PARTY-NOTICES.md")
    }
}

// a plain list of the shipped components with versions, for keeping THIRD-PARTY-NOTICES.md current
tasks.register("shippedComponents") {
    group = "help"
    val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    doLast {
        val out = sortedSetOf<String>()
        val seen = mutableSetOf<ResolvedComponentResult>()
        fun walk(c: ResolvedComponentResult) {
            if (!seen.add(c)) return
            (c.id as? ModuleComponentIdentifier)?.let { out += "${it.group}:${it.module}:${it.version}" }
            c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        walk(root.get())
        out.forEach { println(it) }
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach { dependsOn(licenseGate) }
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(ocrModel) }
