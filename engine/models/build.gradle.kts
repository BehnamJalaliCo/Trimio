plugins {
    alias(libs.plugins.trimio.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.ktor.client.core)
            api(libs.kotlinx.io.core)
            implementation(libs.kotlincrypto.sha2)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        wasmJsMain.dependencies { implementation(libs.ktor.client.js) }
        jvmTest.dependencies { implementation(libs.ktor.client.mock) }
    }
}
