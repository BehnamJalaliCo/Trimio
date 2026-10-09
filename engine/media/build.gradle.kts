plugins {
    alias(libs.plugins.trimio.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.pipeline)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
