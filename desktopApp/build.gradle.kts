import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

/**
 * Trimio for desktop (Compose Desktop on the JVM): the shared app, the production engine through
 * ffmpeg + Skia + whisper.cpp/llama.cpp, and the Windows/macOS platform layer. Windows installers
 * are built by .github/workflows/desktop-windows.yml; see docs/DESKTOP.md.
 *
 *   ./gradlew :desktopApp:run                    run from source (ffmpeg on the PATH)
 *   ./gradlew :desktopApp:createDistributable    app image for the current OS
 *   ./gradlew :desktopApp:packageMsi packageExe  Windows installers (on Windows only)
 */
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared)
    implementation(projects.engine.production)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.koin.compose.viewmodel)
    testImplementation(kotlin("test"))
}

/** Version of the installers: MSI needs MAJOR.MINOR.BUILD with MAJOR ≥ 1. */
val desktopVersion = providers.gradleProperty("trimio.desktop.version").getOrElse("1.0.0")

compose.desktop {
    application {
        mainClass = "io.trimio.desktop.MainKt"
        jvmArgs += listOf("-Xmx6g", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")

        nativeDistributions {
            packageName = "Trimio"
            packageVersion = desktopVersion
            description = "Talking video in, motion-graphics edit out"
            vendor = "Trimio"
            copyright = "© Trimio"
            // The engines and SDKs reach many JDK modules (TLS, management, reflection): ship them all
            // rather than discover a missing one on a user's machine; models dwarf the runtime anyway.
            includeAllModules = true
            // Bundled tools and native engines, per OS: resources/common, resources/windows, resources/macos, resources/linux.
            // CI fills resources/windows/{ffmpeg,native}; the app finds them through compose.application.resources.dir.
            appResourcesRootDir.set(layout.projectDirectory.dir("resources"))

            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg, TargetFormat.Deb)

            windows {
                iconFile.set(project.file("icons/trimio.ico"))
                // Per-user install: no administrator prompt, installs under %LOCALAPPDATA%\Programs.
                perUserInstall = true
                menu = true
                menuGroup = "Trimio"
                shortcut = true
                dirChooser = true
                // A stable upgrade code lets a newer MSI replace an older one in place.
                upgradeUuid = "6f0f3c7e-5d0a-4b8e-9a3c-2c1f7b9e4d21"
                fileAssociation("video/mp4", "mp4", "MPEG-4 video", project.file("icons/trimio.ico"))
                fileAssociation("video/quicktime", "mov", "QuickTime video", project.file("icons/trimio.ico"))
            }

            // macOS: configured, not built yet (docs/DESKTOP.md lists what shipping needs).
            macOS {
                iconFile.set(project.file("icons/trimio.icns"))
                bundleID = "io.trimio.desktop"
                appCategory = "public.app-category.video"
                minimumSystemVersion = "12.0"
                entitlementsFile.set(project.file("macos/entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("macos/runtime-entitlements.plist"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Trimio records a voice-over only when you ask it to.</string>
                    """.trimIndent()
                }
                // Signing and notarisation read their secrets from the environment in CI only.
                signing {
                    sign.set(providers.environmentVariable("TRIMIO_MAC_SIGN").map { it == "true" }.orElse(false))
                    identity.set(providers.environmentVariable("TRIMIO_MAC_SIGN_IDENTITY").orElse(""))
                }
                notarization {
                    appleID.set(providers.environmentVariable("TRIMIO_MAC_NOTARY_APPLE_ID").orElse(""))
                    password.set(providers.environmentVariable("TRIMIO_MAC_NOTARY_PASSWORD").orElse(""))
                    teamID.set(providers.environmentVariable("TRIMIO_MAC_NOTARY_TEAM_ID").orElse(""))
                }
            }

            linux {
                iconFile.set(project.file("icons/trimio.png"))
                packageName = "trimio"
            }
        }

        buildTypes.release.proguard {
            // The engines reach classes by reflection (serialization, Koin, JNI callbacks); not worth shrinking.
            isEnabled.set(false)
        }
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    // Headless rendering for the window screenshot test (Skia draws off-screen).
    systemProperty("java.awt.headless", "true")
    systemProperty("trimio.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    // CI on Windows: -Ptrimio.nativeSmoke=<dir with cpu/ and vulkan/ DLLs> loads the packaged engines.
    providers.gradleProperty("trimio.nativeSmoke").orNull?.let { dir ->
        systemProperty("trimio.native.smoke", "true")
        systemProperty("trimio.native.dir", dir)
        systemProperty("trimio.native.variants", "vulkan,cpu")
    }
}
