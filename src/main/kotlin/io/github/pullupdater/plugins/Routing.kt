package io.github.pullupdater.plugins

import io.github.pullupdater.routes.updaterRouting
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    install(Resources)

    routing {
        authenticate("auth-bearer") {
            updaterRouting()
        }
    }
}
