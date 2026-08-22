plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":infrastructure:persistence"))
    implementation(project(":infrastructure:security"))

    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    api(libs.embedded.postgres)
    runtimeOnly(platform(libs.embedded.postgres.binaries.bom))
    runtimeOnly(libs.embedded.postgres.binaries.linux.amd64)

    implementation(platform(libs.junit.bom))
    api(libs.junit.jupiter)
}