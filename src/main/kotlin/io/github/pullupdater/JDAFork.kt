package io.github.pullupdater

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.notExists

private typealias BranchLabel = String
private typealias BranchSha = String

object JDAFork {
    @Serializable
    data class BranchIdentifier(val forkBotName: String, val forkRepoName: String, val forkedBranchName: String)
    @Serializable
    data class FailMessage(val message: String)
    class Result private constructor(val statusCode: HttpStatusCode, val body: String) {
        companion object {
            fun ok(pullRequest: PullRequest) = Result(
                HttpStatusCode.OK,
                Json.encodeToString(
                    BranchIdentifier(
                        config.forkBotName,
                        config.forkRepoName,
                        pullRequest.head.toForkedBranchName()
                    )
                )
            )

            fun fail(statusCode: HttpStatusCode, message: String): Result {
                return Result(statusCode, Json.encodeToString(FailMessage(message)))
            }
        }
    }

    private val logger = LoggerFactory.getLogger(JDAFork::class.java)
    private val config = Config.instance
    private val forkPath = Path(System.getProperty("user.home"), "Bots", "Doxxy", "JDA-Fork")

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json = Json { ignoreUnknownKeys = true })
        }
    }
    private val mutex = Mutex()

    private val latestHeadSha: MutableMap<BranchLabel, BranchSha> = hashMapOf()
    private val latestBaseSha: MutableMap<BranchLabel, BranchSha> = hashMapOf()

    suspend fun requestUpdate(prNumber: Int): Result {
        if (mutex.isLocked) {
            return Result.fail(HttpStatusCode.TooManyRequests, "Already running")
        }

        mutex.withLock {
            init()

            val pullRequest: PullRequest = client.get("https://api.github.com/repos/DV8FromTheWorld/JDA/pulls/$prNumber") {
                header("Accept", "applications/vnd.github.v3+json")
            }.also {
                if (it.status == HttpStatusCode.NotFound) {
                    return Result.fail(HttpStatusCode.NotFound, "Pull request not found")
                } else if (!it.status.isSuccess()) {
                    return Result.fail(HttpStatusCode.InternalServerError, "Error while getting pull request")
                }
            }.body()

            if (pullRequest.merged) {
                //Skip merged PRs
                return Result.ok(pullRequest)
            } else if (pullRequest.mergeable == false) {
                //Skip PRs with conflicts
                return Result.fail(HttpStatusCode.Conflict, "Head branch cannot be updated")
            } else if (latestHeadSha[pullRequest.head.label] == pullRequest.head.sha && latestBaseSha[pullRequest.base.label] == pullRequest.base.sha) {
                //Prevent unnecessary updates by checking if the latest SHA is the same on the remote
                return Result.ok(pullRequest)
            }

            val result = doUpdate(pullRequest)

            if (result.statusCode == HttpStatusCode.OK) {
                latestHeadSha[pullRequest.head.label] = pullRequest.head.sha
                latestBaseSha[pullRequest.base.label] = pullRequest.base.sha
            }

            return result
        }
    }

    private suspend fun doUpdate(pullRequest: PullRequest): Result {
        //JDA repo most likely
        val base = pullRequest.base
        val baseBranchName = base.branchName
        val baseRepo = base.repo.name
        val baseRemoteName = base.user.userName

        //The PR author's repo
        val head = pullRequest.head
        val headBranchName = head.branchName
        val headRepo = head.repo.name
        val headRemoteName = head.user.userName

        //Add remote
        val remotes = runProcess(forkPath, "git", "remote").trim().lines()
        if (baseRemoteName !in remotes) {
            runProcess(forkPath, "git", "remote", "add", baseRemoteName, "https://github.com/$baseRemoteName/$baseRepo")
        }
        if (headRemoteName !in remotes) {
            runProcess(forkPath, "git", "remote", "add", headRemoteName, "https://github.com/$headRemoteName/$headRepo")
        }

        //Fetch base and head repo
        runProcess(forkPath, "git", "fetch", baseRemoteName)
        runProcess(forkPath, "git", "fetch", headRemoteName)

        //Use remote branch
        val headRemoteReference = "refs/remotes/$headRemoteName/$headBranchName"
        try {
            runProcess(
                forkPath,
                "git",
                "switch",
                "--force-create",
                head.toForkedBranchName(),
                headRemoteReference
            )
        } catch (e: ProcessException) {
            if (e.errorOutput.startsWith("fatal: invalid reference")) {
                return Result.fail(HttpStatusCode.NotFound, "Head reference '$headRemoteReference' was not found")
            }
            return Result.fail(HttpStatusCode.InternalServerError, "Error while switching to head branch")
        }

        //Merge base branch into remote branch
        val baseRemoteReference = "$baseRemoteName/$baseBranchName"
        try {
            runProcess(forkPath, "git", "merge", baseRemoteReference)
        } catch (e: ProcessException) {
            if (e.errorOutput.startsWith("fatal: invalid reference")) {
                return Result.fail(HttpStatusCode.NotFound, "Base reference '$baseRemoteReference' was not found")
            }
            return Result.fail(HttpStatusCode.InternalServerError, "Error while switching to base branch")
        }

        //Publish result on our fork
        // Force push is used as the bot takes the remote head branch instead of reusing the local one,
        // meaning the remote branch would always be incompatible on the 2nd update
        runProcess(forkPath, "git", "push", "--force", "origin")

        return Result.ok(pullRequest)
    }

    private suspend fun init() {
        if (forkPath.notExists()) {
            val forkPathTmp = forkPath.resolveSibling("JDA-Fork-tmp")
            runProcess(
                workingDirectory = forkPathTmp.parent,
                "git", "clone", "https://${config.gitToken}@github.com/${config.forkBotName}/${config.forkRepoName}", forkPathTmp.name
            )
            runProcess(
                workingDirectory = forkPathTmp,
                "git", "config", "--local", "user.name", config.gitName
            )
            runProcess(
                workingDirectory = forkPathTmp,
                "git", "config", "--local", "user.email", config.gitEmail
            )

            //Disable signing just in case
            runProcess(
                workingDirectory = forkPathTmp,
                "git", "config", "--local", "commit.gpgsign", "false"
            )
            runProcess(
                workingDirectory = forkPathTmp,
                "git", "config", "--local", "tag.gpgsign", "false"
            )

            forkPathTmp.moveTo(forkPath, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun PullRequest.Branch.toForkedBranchName() = "${user.userName}/$branchName"

    private suspend fun runProcess(workingDirectory: Path, vararg command: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command.asList())
            .directory(workingDirectory.toFile())
            .start()

        val outputStream = ByteArrayOutputStream()
        val errorStream = ByteArrayOutputStream()
        val exitCode = coroutineScope {
            launch { redirectStream(outputStream, process.inputStream) }
            launch { redirectStream(errorStream, process.errorStream) }

            process.waitFor()
        }

        if (exitCode != 0) {
            val outputString = outputStream.toByteArray().decodeToString()
            when {
                outputString.isNotBlank() -> logger.warn("Output:\n$outputString")
                else -> logger.warn("No output")
            }

            val errorString = errorStream.toByteArray().decodeToString()
            when {
                errorString.isNotBlank() -> logger.error("Error output:\n$errorString")
                else -> logger.warn("No error output")
            }

            throw ProcessException(exitCode, errorString, "Process exited with code $exitCode: ${command.joinToString(" ") { if (it.contains("github_pat_")) "[bot_repo]" else it }}")
        }

        return@withContext outputStream.toByteArray().decodeToString()
    }

    private fun redirectStream(arrayStream: ByteArrayOutputStream, processStream: InputStream) {
        arrayStream.bufferedWriter().use { writer ->
            processStream.bufferedReader().use { reader ->
                var readLine: String?
                while (reader.readLine().also { readLine = it } != null) {
                    writer.append(readLine + System.lineSeparator())
                }
            }
        }
    }
}