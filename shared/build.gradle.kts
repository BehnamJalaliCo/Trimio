plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.pipeline)
            api(projects.core.data)
            api(projects.core.designsystem)
            api(projects.feature.stream)
            api(projects.feature.studio)
            api(projects.feature.create)
            api(projects.feature.editor)
            api(projects.feature.settings)
            api(projects.feature.gallery)
            api(projects.engine.media)
            api(projects.engine.audio)
            api(projects.engine.asr)
            api(projects.engine.models)
            api(projects.engine.llm)
            api(projects.engine.director)
            api(projects.engine.assets)
            api(projects.engine.render)
            api(projects.engine.styles)
            api(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.navigation3.ui)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.koin.android)
        }
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}
