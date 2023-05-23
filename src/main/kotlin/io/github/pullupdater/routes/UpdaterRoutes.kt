package io.github.pullupdater.routes

import io.github.pullupdater.JDAFork
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.updaterRouting() {
    route("/update") {
        get("{pull_number}") {
            val prNumber = let {
                val prNumberStr = call.parameters["pull_number"] ?: return@get call.respondText(
                    "Missing PR number",
                    status = HttpStatusCode.BadRequest
                )

                prNumberStr.toIntOrNull() ?: return@get call.respondText(
                    "Must be a number",
                    status = HttpStatusCode.BadRequest
                )
            }

            val result = runCatching {
                JDAFork.requestUpdate(prNumber)
            }.getOrElse { JDAFork.Result.fail(HttpStatusCode.InternalServerError, "Unknown error while updating PR") }
            call.respondText(contentType = ContentType.Application.Json, status = result.statusCode, text = result.body)
        }
    }
}