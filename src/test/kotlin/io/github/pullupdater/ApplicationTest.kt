package io.github.pullupdater

import io.github.pullupdater.plugins.configureRouting
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ApplicationTest {
    @Test
    fun testRoot() = testApplication {
        application {
            configureRouting()
        }
        client.get("/update/1878").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        client.get("/update/-1").apply {
            assertEquals(HttpStatusCode.NotFound, status)
        }

        client.get("/update/2463").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        assertFails {
            client.get("/update/2465")
        }
    }
}
