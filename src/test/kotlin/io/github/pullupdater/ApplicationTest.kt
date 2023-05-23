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

        val client = client.config { this.defaultRequest { this.bearerAuth(Config.instance.doxxyToken) } }
        //Already up to date
        client.get("/update/1878").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        client.get("/update/-1").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }

        client.get("/update/2463").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        //Already merged
        client.get("/update/2465").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
