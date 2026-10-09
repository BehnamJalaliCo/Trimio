plugins {
    alias(libs.plugins.trimio.kmp.compose)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.trimio.core.designsystem.resources"
}
