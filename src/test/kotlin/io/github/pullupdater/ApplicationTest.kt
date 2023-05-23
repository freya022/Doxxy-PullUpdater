package io.github.pullupdater

import io.github.pullupdater.plugins.configureAuth
import io.github.pullupdater.plugins.configureRouting
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        application {
            configureAuth()
            configureRouting()
        }

        val client = client.config {
            defaultRequest {
                bearerAuth(Config.instance.doxxyToken)
            }
        }
        //Already up to date
        client.get("/update/JDA/1878").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        client.get("/update/JDA/-1").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }

        client.get("/update/JDA/2463").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        //Already merged
        client.get("/update/JDA/2465").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        //Invalid repo
        client.get("/update/BotCommands/2465").apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
    }
}
