package io.github.pullupdater.plugins

import io.github.pullupdater.Config
import io.ktor.server.application.*
import io.ktor.server.auth.*

fun Application.configureAuth() {
    install(Authentication) {
        bearer("auth-bearer") {
            realm = "Access to the '/' path"
            authenticate { tokenCredential ->
                if (tokenCredential.token == Config.instance.doxxyToken) {
                    UserIdPrincipal("Doxxy")
                } else {
                    null
                }
            }
        }
    }
}