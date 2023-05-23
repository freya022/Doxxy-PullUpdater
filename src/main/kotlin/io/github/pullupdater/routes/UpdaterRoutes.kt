package io.github.pullupdater.routes

import io.github.pullupdater.JDAFork
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

@Suppress("unused")
@Resource("/update")
class PullRequestUpdate() {
    @Resource("{repository}")
    class Repository(val parent: PullRequestUpdate, val repository: String) {
        @Resource("{pullNumber}")
        class Number(val parent: Repository, val pullNumber: Int)
    }
}

fun Route.updaterRouting() {
    get<PullRequestUpdate.Repository.Number> { pullRequest ->
        val result = runCatching {
            JDAFork.requestUpdate(pullRequest.parent.repository, pullRequest.pullNumber)
        }.getOrElse { JDAFork.Result.fail(HttpStatusCode.InternalServerError, "Unknown error while updating PR") }
        call.respondText(contentType = ContentType.Application.Json, status = result.statusCode, text = result.body)
    }
}