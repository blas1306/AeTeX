import java.net.URI
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.3.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    implementation("net.java.dev.jna:jna:5.17.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "dev.aetex.experiments.rendering.BenchmarkMainKt"
}

tasks.test {
    useJUnitPlatform()
}

data class PdfiumAsset(val fileName: String, val sha256: String)

val pdfiumVersion = "152.0.7961.0"
val pdfiumTag = "7961"
val osName = System.getProperty("os.name").lowercase()
val archName = System.getProperty("os.arch").lowercase()
val osPart = when {
    osName.contains("linux") -> "linux"
    osName.contains("mac") -> "mac"
    osName.contains("windows") -> "win"
    else -> error("Unsupported PDFium benchmark OS: $osName")
}
val archPart = when (archName) {
    "x86_64", "amd64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Unsupported PDFium benchmark architecture: $archName")
}
val assets = mapOf(
    "linux-arm64" to PdfiumAsset("pdfium-linux-arm64.tgz", "974107999784a438149605024475d42d80dd306799d90e1af5f6fa63f976455f"),
    "linux-x64" to PdfiumAsset("pdfium-linux-x64.tgz", "019665c8877d46fe65f625f80fd714ab07aac68554b0636acf2a2adf9288adb2"),
    "mac-arm64" to PdfiumAsset("pdfium-mac-arm64.tgz", "1193a771e0bd934530afa3df73a0d44551d8f4078442e290054e6dd38ded960f"),
    "mac-x64" to PdfiumAsset("pdfium-mac-x64.tgz", "17f069d7012ab83898ad5eddebd139b240f05d7411c220775d507a0e3e285536"),
    "win-arm64" to PdfiumAsset("pdfium-win-arm64.tgz", "9d8c50c65f129c3774b972cde09d3bea44478075f72faadfe9fc0c152e65d509"),
    "win-x64" to PdfiumAsset("pdfium-win-x64.tgz", "88276459349b291c41f10422dad0210f007c04d919c8fa56472b6b7c6406adf4"),
)
val pdfiumAsset = assets.getValue("$osPart-$archPart")
val pdfiumUrl = "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium/$pdfiumTag/${pdfiumAsset.fileName}"
val pdfiumArchive = layout.buildDirectory.file("pdfium/downloads/${pdfiumAsset.fileName}")
val pdfiumDirectory = layout.buildDirectory.dir("pdfium/$pdfiumVersion/$osPart-$archPart")
val pdfiumProvenance = pdfiumDirectory.map { it.file("provenance.properties") }

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

val downloadPdfium by tasks.registering {
    description = "Downloads and verifies the pinned experimental PDFium binary."
    outputs.file(pdfiumArchive)
    // Check the cached archive on every invocation. Existence alone must never
    // bypass the supply-chain verification.
    outputs.upToDateWhen { false }
    doLast {
        val target = pdfiumArchive.get().asFile
        target.parentFile.mkdirs()
        if (target.exists() && sha256(target) != pdfiumAsset.sha256) {
            check(target.delete()) { "Cannot remove invalid cached PDFium archive: $target" }
        }
        if (!target.exists()) {
            val partial = target.resolveSibling("${target.name}.partial")
            try {
                Files.deleteIfExists(partial.toPath())
                URI(pdfiumUrl).toURL().openStream().use { input ->
                    partial.outputStream().use(input::copyTo)
                }
                check(sha256(partial) == pdfiumAsset.sha256) {
                    "Downloaded PDFium checksum mismatch for $pdfiumUrl"
                }
                try {
                    Files.move(
                        partial.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(partial.toPath())
            }
        }
        val actual = sha256(target)
        check(actual == pdfiumAsset.sha256) {
            "PDFium checksum mismatch: expected ${pdfiumAsset.sha256}, got $actual"
        }
    }
}

val preparePdfium by tasks.registering(Sync::class) {
    description = "Extracts the verified experimental PDFium binary."
    dependsOn(downloadPdfium)
    from(tarTree(resources.gzip(pdfiumArchive)))
    into(pdfiumDirectory)
    outputs.upToDateWhen { false }
    doLast {
        val library = findPdfiumLibrary()
        pdfiumProvenance.get().asFile.writeText(
            """
            version=$pdfiumVersion
            tag=chromium/$pdfiumTag
            asset=${pdfiumAsset.fileName}
            source=$pdfiumUrl
            archiveSha256=${pdfiumAsset.sha256}
            librarySha256=${sha256(library)}
            distribution=bblanchon/pdfium-binaries (third-party precompiled distribution)
            """.trimIndent() + "\n",
        )
    }
}

fun findPdfiumLibrary(): File {
    val names = when (osPart) {
        "linux" -> setOf("libpdfium.so")
        "mac" -> setOf("libpdfium.dylib")
        else -> setOf("pdfium.dll")
    }
    return pdfiumDirectory.get().asFile.walkTopDown()
        .firstOrNull { it.isFile && it.name in names }
        ?: error("PDFium library not found under ${pdfiumDirectory.get().asFile}")
}

tasks.register<JavaExec>("benchmark") {
    group = "benchmark"
    description = "Generates the corpus and runs the isolated PDFBox/PDFium benchmark."
    dependsOn(preparePdfium, tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    workingDir = projectDir
    doFirst {
        args(
            "--native-library",
            findPdfiumLibrary().absolutePath,
            "--pdfium-provenance",
            pdfiumProvenance.get().asFile.absolutePath,
        )
    }
}

tasks.register<JavaExec>("generateCorpus") {
    group = "benchmark"
    description = "Generates only the deterministic synthetic PDF corpus."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    workingDir = projectDir
    args("--generate-corpus-only")
}
