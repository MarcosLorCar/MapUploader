plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(ktorLibs.plugins.ktor)
}

group = "me.orange"
version = "1.2.0"

application {
    // Custom launcher: standalone web app by default, or a Minecraft-server proxy
    // when a `proxied_*.jar` is present. See me/orange/Launcher.kt.
    mainClass = "me.orange.LauncherKt"
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)

    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    implementation(libs.logback.classic)
    implementation(libs.nbt)
    implementation(libs.rcon)
}

tasks.shadowJar {
    archiveFileName.set("${project.name}-${project.version}.jar")
}