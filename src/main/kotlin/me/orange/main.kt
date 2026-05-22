package me.orange

import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import me.orange.plugins.configureSerialization


fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    configureSerialization()
    configureRouting()
}