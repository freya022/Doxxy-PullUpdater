package io.github.pullupdater

import dev.reformator.stacktracedecoroutinator.runtime.DecoroutinatorRuntime
import io.github.pullupdater.plugins.configureAuth
import io.github.pullupdater.plugins.configureRouting
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    DecoroutinatorRuntime.load()

    embeddedServer(Netty, port = 4545, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    configureAuth()
    configureRouting()
}
