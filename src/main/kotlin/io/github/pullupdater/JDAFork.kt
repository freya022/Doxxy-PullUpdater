package io.github.pullupdater

import io.ktor.utils.io.errors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
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

    private val semaphore = Semaphore(1)

    suspend fun requestUpdate(prNumber: Int): Boolean {
        if (!semaphore.tryAcquire()) {
            return false
        }

        try {
            init()

            //TODO

            return true
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