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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
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
    class Result(val statusCode: HttpStatusCode, val errorMessage: String) {
        companion object {
            val OK = Result(HttpStatusCode.OK, "OK")
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
    private val semaphore = Semaphore(1)

    private val latestHeadSha: MutableMap<BranchLabel, BranchSha> = hashMapOf()
    private val latestBaseSha: MutableMap<BranchLabel, BranchSha> = hashMapOf()

    suspend fun requestUpdate(prNumber: Int): Result {
        if (!semaphore.tryAcquire()) {
            return Result(HttpStatusCode.TooManyRequests, "Already running")
        }

        try {
            init()

            val pullRequest: PullRequest = client.get("https://api.github.com/repos/DV8FromTheWorld/JDA/pulls/$prNumber") {
                header("Accept", "applications/vnd.github.v3+json")
            }.also {
                if (it.status == HttpStatusCode.NotFound) {
                    return Result(HttpStatusCode.NotFound, "Pull request not found")
                } else if (!it.status.isSuccess()) {
                    return Result(HttpStatusCode.InternalServerError, "Error while getting pull request")
                }
            }.body()

            if (pullRequest.merged) {
                return Result.OK
            }

            if (!pullRequest.mergeable) {
                return Result(HttpStatusCode.Conflict, "Head branch cannot be updated")
            }

            //Prevent unnecessary updates by checking if the latest SHA is the same on the remote
            if (latestHeadSha[pullRequest.head.label] == pullRequest.head.sha && latestBaseSha[pullRequest.base.label] == pullRequest.base.sha) {
                return Result.OK
            }

            val result = doUpdate(pullRequest)

            if (result == Result.OK) {
                latestHeadSha[pullRequest.head.label] = pullRequest.head.sha
                latestBaseSha[pullRequest.base.label] = pullRequest.base.sha
            }

            return result
        } finally {
            semaphore.release()
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
        val headUserName = head.user.userName
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
                "$headUserName/$headBranchName",
                headRemoteReference
            )
        } catch (e: ProcessException) {
            if (e.errorOutput.startsWith("fatal: invalid reference")) {
                return Result(HttpStatusCode.NotFound, "Head reference '$headRemoteReference' was not found")
            }
            return Result(HttpStatusCode.InternalServerError, "Error while switching to head branch")
        }

        //Merge base branch into remote branch
        val baseRemoteReference = "$baseRemoteName/$baseBranchName"
        try {
            runProcess(forkPath, "git", "merge", baseRemoteReference)
        } catch (e: ProcessException) {
            if (e.errorOutput.startsWith("fatal: invalid reference")) {
                return Result(HttpStatusCode.NotFound, "Base reference '$baseRemoteReference' was not found")
            }
            return Result(HttpStatusCode.InternalServerError, "Error while switching to base branch")
        }

        //Publish result on our fork
        // Force push is used as the bot takes the remote head branch instead of reusing the local one,
        // meaning the remote branch would always be incompatible on the 2nd update
        runProcess(forkPath, "git", "push", "--force", "origin")

        return Result.OK
    }

    private suspend fun init() {
        if (forkPath.notExists()) {
            val forkPathTmp = forkPath.resolveSibling("JDA-Fork-tmp")
            runProcess(
                workingDirectory = forkPathTmp.parent,
                "git", "clone", "https://${config.gitToken}@github.com/JDA-Fork/JDA", forkPathTmp.name
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