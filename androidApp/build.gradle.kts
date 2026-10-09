plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.trimio.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.trimio.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Release builds take both from the tag workflow (-Ptrimio.versionCode / -Ptrimio.versionName).
        versionCode = providers.gradleProperty("trimio.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("trimio.versionName").orNull ?: "0.1.0"

        // High-end phones only: 64-bit ARM. ARMv8.2 dot-product and fp16 cover every flagship SoC
        // since 2020 and give whisper.cpp/llama.cpp their fast int8/fp16 kernels.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DGGML_OPENMP=OFF",
                    "-DTRIMIO_WHISPER=ON",
                    "-DTRIMIO_LLAMA=ON",
                )
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Two store channels: Cafe Bazaar (Iran) and Google Play. Billing/CDN differ per flavor.
    flavorDimensions += "store"
    productFlavors {
        create("play") { dimension = "store" }
        create("bazaar") {
            dimension = "store"
            // Bazaar's per-app RSA key for local purchase verification (Bazaar panel → In-app billing).
            // Public by design; billing stays off in builds without it.
            buildConfigField("String", "BAZAAR_RSA_KEY", "\"${providers.gradleProperty("trimio.bazaarRsaKey").orNull.orEmpty()}\"")
        }
    }

    // Upload key from the environment (CI secrets). The private key never enters the repository;
    // without it release builds are unsigned, which is fine for local R8 checks.
    val keystore = System.getenv("TRIMIO_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("upload") {
            storeFile = keystore
            storePassword = System.getenv("TRIMIO_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("TRIMIO_KEY_ALIAS")
            keyPassword = System.getenv("TRIMIO_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        // Duplicate licence metadata from the SDK's HTTP dependencies; the licences are in THIRD_PARTY_NOTICES.
        resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.kotlin_module")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    "playImplementation"(libs.play.billing)
    "bazaarImplementation"(libs.poolakey)
}

// Licence audit of everything that ships in the Play release: `./gradlew :androidApp:checkLicenses`.
// Reads each dependency's POM (following parent POMs) and fails on copyleft licences, so no
// GPL/LGPL/AGPL code can slip into the app. The report lands in build/reports/licenses.txt.
val checkLicenses by tasks.registering {
    group = "verification"
    description = "Fails if a shipped dependency has a copyleft licence."
    notCompatibleWithConfigurationCache("Resolves POMs through the project's dependency handler.")
    doLast {
        val graph = configurations.getByName("playReleaseRuntimeClasspath").incoming.resolutionResult
        val modules = graph.allComponents.mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
        val poms = mutableMapOf<String, groovy.util.Node?>()
        fun pom(group: String, name: String, version: String): groovy.util.Node? = poms.getOrPut("$group:$name:$version") {
            val id = org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier.newId(
                org.gradle.api.internal.artifacts.DefaultModuleIdentifier.newId(group, name), version,
            )
            val result = dependencies.createArtifactResolutionQuery()
                .forComponents(id)
                .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
                .execute()
            val file = result.resolvedComponents.flatMap { it.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java) }
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>().firstOrNull()?.file
            file?.let { groovy.xml.XmlParser(false, false).parse(it) }
        }
        fun child(node: groovy.util.Node, tag: String) = (node.children() as List<*>).filterIsInstance<groovy.util.Node>().firstOrNull { it.name().toString().substringAfter('}') == tag }
        fun licenses(group: String, name: String, version: String, depth: Int = 0): List<String> {
            val node = pom(group, name, version) ?: return emptyList()
            val own = child(node, "licenses")?.let { l -> (l.children() as List<*>).filterIsInstance<groovy.util.Node>().mapNotNull { child(it, "name")?.text() } }.orEmpty()
            if (own.isNotEmpty() || depth > 4) return own
            val parent = child(node, "parent") ?: return own
            return licenses(child(parent, "groupId")!!.text(), child(parent, "artifactId")!!.text(), child(parent, "version")!!.text(), depth + 1)
        }
        val copyleft = Regex("\\b(A?GPL|LGPL|GNU (Lesser |Affero )?General Public)\\b", RegexOption.IGNORE_CASE)
        val report = StringBuilder()
        val violations = mutableListOf<String>()
        for (m in modules.sortedBy { "${it.group}:${it.module}" }) {
            val found = licenses(m.group, m.module, m.version)
            report.appendLine("${m.group}:${m.module}:${m.version}\t${found.joinToString(" | ").ifEmpty { "(no licence in POM)" }}")
            // Dual-licensed modules are fine when one option is permissive (e.g. "EPL 2.0 | GPL 2.0 with Classpath Exception").
            if (found.isNotEmpty() && found.all { copyleft.containsMatchIn(it) && !it.contains("Classpath", ignoreCase = true) }) {
                violations += "${m.group}:${m.module}:${m.version} (${found.joinToString()})"
            }
        }
        val out = layout.buildDirectory.file("reports/licenses.txt").get().asFile
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        logger.lifecycle("Licence audit: ${modules.size} dependencies, report at $out")
        if (violations.isNotEmpty()) throw GradleException("Copyleft licences found:\n" + violations.joinToString("\n"))
    }
}
