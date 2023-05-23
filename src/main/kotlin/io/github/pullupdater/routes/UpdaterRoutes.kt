package io.github.pullupdater.routes

import io.github.pullupdater.JDAFork
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

@Resource("/update")
class PullRequestUpdate() {
    @Suppress("unused")
    @Resource("/{pullNumber}")
    class Number(val parent: PullRequestUpdate = PullRequestUpdate(), val pullNumber: Int)
}

fun Route.updaterRouting() {
    get<PullRequestUpdate.Number> { pullRequest ->
        val result = runCatching {
            JDAFork.requestUpdate(pullRequest.pullNumber)
        }.getOrElse { JDAFork.Result.fail(HttpStatusCode.InternalServerError, "Unknown error while updating PR") }
        call.respondText(contentType = ContentType.Application.Json, status = result.statusCode, text = result.body)
    }
}