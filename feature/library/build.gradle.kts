plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

/**
 * «کتابخانه‌ها»: the downloadable content libraries (art, sound, overlays, looks), the creator's
 * own animations (desktop import, LottieFiles with their own key) and the in-app core set
 * (files/core: the most used animated pictures, drawn with no download; built by tools/packs).
 */
compose.resources {
    packageOfResClass = "io.trimio.feature.library.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(projects.core.data)
            implementation(projects.core.designsystem)
            api(projects.engine.models)
            implementation(projects.engine.assets)
            implementation(projects.engine.llm)
            implementation(libs.ktor.client.core)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        jvmTest.dependencies { implementation(compose.desktop.currentOs) }
    }
}
