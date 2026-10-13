plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.mezo.trace.server.api.ApiApplicationKt")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:application"))
    implementation(project(":infrastructure:persistence"))
    implementation(project(":infrastructure:security"))
    implementation(project(":infrastructure:fetch"))
    implementation(project(":adapters"))
    implementation(project(":ai"))
    implementation(project(":infrastructure:metrics"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logback.classic)
    implementation(libs.logstash.encoder)

    testImplementation(project(":test-support"))

    testImplementation(libs.ktor.server.test.host)
    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
