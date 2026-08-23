plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:application"))
    implementation(project(":core:domain"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}