plugins {
    alias(libs.plugins.trimio.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
        }
    }
}
