import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

/**
 * Licensed brand fonts live in the repository only encrypted (brand/assets.bin, AES-256-GCM; see
 * tools/brand/seal.py). This task decrypts them into the module's compose resources at build time
 * with the key from $TRIMIO_BRAND_KEY or brand/brand.key. Without a key (forks, CI without the
 * secret) every font role falls back to the open Vazirmatn font so the build still works.
 */
abstract class UnsealBrandAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val sealed: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val fallbackFont: RegularFileProperty

    /** SHA-256 of the key (never the key itself), so a new key re-runs the task. */
    @get:Input
    abstract val keyFingerprint: Property<String>

    @get:Internal
    abstract val keyFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun unseal() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val key = System.getenv("TRIMIO_BRAND_KEY")?.trim()?.takeIf { it.isNotEmpty() }
            ?: keyFile.get().asFile.takeIf { it.exists() }?.readText()?.trim()
        if (key == null) {
            logger.warn("Brand key not found: building with fallback fonts.")
            ROLES.forEach { fallbackFont.get().asFile.copyTo(out.resolve("font/$it.ttf"), overwrite = true) }
            return
        }
        val blob = sealed.get().asFile.readBytes()
        require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "brand/assets.bin is not a sealed brand archive" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(key), "AES"), GCMParameterSpec(128, blob, MAGIC.size, 12))
            updateAAD(MAGIC)
        }
        val zip = cipher.doFinal(blob, MAGIC.size + 12, blob.size - MAGIC.size - 12)
        ZipInputStream(zip.inputStream()).use { entries ->
            generateSequence { entries.nextEntry }.forEach { entry ->
                // Licence files travel with the fonts inside the app, as the font licence requires.
                val target = if (entry.name.startsWith("license/")) out.resolve("files/brand/${entry.name.removePrefix("license/")}") else out.resolve(entry.name)
                require(target.canonicalPath.startsWith(out.canonicalPath)) { "bad entry ${entry.name}" }
                target.parentFile.mkdirs()
                target.writeBytes(entries.readBytes())
            }
        }
        val missing = ROLES.filterNot { out.resolve("font/$it.ttf").exists() }
        require(missing.isEmpty()) { "sealed brand archive lacks $missing" }
    }

    companion object {
        val MAGIC = "TRMB1".toByteArray()
        val ROLES = listOf("ui", "expressive") +
            listOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 950, 1000).map { "display_$it" } +
            listOf(300, 400, 500, 600, 700, 900).map { "accent_$it" }
    }
}

val brandKeyFile = rootProject.layout.projectDirectory.file("brand/brand.key")
val unsealBrandAssets by tasks.registering(UnsealBrandAssets::class) {
    sealed.set(rootProject.layout.projectDirectory.file("brand/assets.bin"))
    fallbackFont.set(rootProject.layout.projectDirectory.file("core/designsystem/src/commonMain/composeResources/font/vazirmatn.ttf"))
    keyFile.set(brandKeyFile)
    keyFingerprint.set(
        providers.environmentVariable("TRIMIO_BRAND_KEY")
            .orElse(providers.fileContents(brandKeyFile).asText)
            .map { k -> MessageDigest.getInstance("SHA-256").digest(k.trim().toByteArray()).joinToString("") { "%02x".format(it) } }
            .orElse("none"),
    )
    outputDir.set(layout.buildDirectory.dir("generated/brand/composeResources"))
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.trimio.core.brand.resources"
    customDirectory("commonMain", unsealBrandAssets.flatMap { it.outputDir })
}

kotlin {
    sourceSets {
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
