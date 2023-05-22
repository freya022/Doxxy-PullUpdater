package io.github.pullupdater

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.errors.*
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

object JDAFork {
    private val logger = LoggerFactory.getLogger(JDAFork::class.java)
    private val config = Config.instance
    private val forkPath = Path(System.getProperty("user.home"), "Bots", "Doxxy", "JDA-Fork")

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json = Json { ignoreUnknownKeys = true })
        }
    }
    private val semaphore = Semaphore(1)

    suspend fun requestUpdate(prNumber: Int): HttpStatusCode {
        if (!semaphore.tryAcquire()) {
            return HttpStatusCode.TooManyRequests
        }

        try {
            init()

            val pullRequest: PullRequest = client.get("https://api.github.com/repos/DV8FromTheWorld/JDA/pulls/$prNumber") {
                header("Accept", "applications/vnd.github.v3+json")
            }.also {
                if (it.status == HttpStatusCode.NotFound) {
                    return HttpStatusCode.NotFound
                }
            }.body()

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
            runProcess(forkPath, "git", "switch", "--force-create", "$headUserName/$headBranchName", "refs/remotes/$headRemoteName/$headBranchName")

            //Merge base branch into remote branch
            runProcess(forkPath, "git", "merge", "$baseRemoteName/$baseBranchName")

            //Publish result on our fork
            runProcess(forkPath, "git", "push", "origin")

            return HttpStatusCode.OK
        } finally {
            semaphore.release()
        }
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

            throw IOException("Process exited with code $exitCode: ${command.joinToString(" ") { if (it.contains("github_pat_")) "[bot_repo]" else it }}")
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