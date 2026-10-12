plugins {
    alias(libs.plugins.trimio.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.pipeline)
            api(projects.engine.audio)
            // The disk cache of fetched animation documents (art/AssetCache).
            implementation(libs.kotlinx.io.core)
        }
        jvmTest.dependencies { implementation(projects.engine.media) }
    }
}
