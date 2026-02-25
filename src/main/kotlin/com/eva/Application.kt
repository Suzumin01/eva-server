package com.eva

import com.eva.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>): Unit = EngineMain.main(args)

fun Application.module() {
    configureDatabases()
    configureSerialization()
    configureHTTP()
    configureSecurity()
    configureRouting()
}
