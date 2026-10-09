plugins {
    alias(libs.plugins.trimio.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.trimio.engine.styles.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        jvmMain.dependencies {
            // The desktop style tool renders previews with the production renderer.
            implementation(projects.engine.render)
            implementation(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(projects.engine.render)
            implementation(projects.core.designsystem)
            implementation(compose.desktop.currentOs)
        }
    }
}

/**
 * Designer tool: `./gradlew :engine:styles:stylePreview -Ppack=path/to/pack.json` renders a contact sheet
 * of the pack (footage + audio-only, key moments) into build/style-tool.
 * `-Psign=path/to/pack.json -Pkey=private.der -PkeyId=trimio-2026` prints a signed envelope instead.
 */
val stylePreview by tasks.registering(JavaExec::class) {
    group = "styles"
    description = "Renders or signs a style pack (see docs/STYLE_PACKS.md)."
    val main = kotlin.jvm().compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("io.trimio.engine.styles.StyleToolKt")
    // Paths on the command line are relative to the repository root.
    workingDir = rootProject.projectDir
    val props = providers
    val outDir = layout.buildDirectory.dir("style-tool").get().asFile.absolutePath
    argumentProviders += CommandLineArgumentProvider {
        listOfNotNull(
            props.gradleProperty("pack").orNull?.let { "--preview=$it" },
            props.gradleProperty("sign").orNull?.let { "--sign=$it" },
            props.gradleProperty("key").orNull?.let { "--key=$it" },
            props.gradleProperty("keyId").orNull?.let { "--key-id=$it" },
            "--out=$outDir",
        )
    }
}
