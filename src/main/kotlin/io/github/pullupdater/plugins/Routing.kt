package io.github.pullupdater.plugins

import io.github.pullupdater.routes.updaterRouting
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        updaterRouting()
    }
}
