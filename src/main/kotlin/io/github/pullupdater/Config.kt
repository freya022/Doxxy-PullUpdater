package io.github.pullupdater

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

@Serializable
class Config(val gitName: String, val gitEmail: String, val gitToken: String, val forkBotName: String, val forkRepoName: String) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val instance = Json.decodeFromStream<Config>(Config::class.java.getResourceAsStream("/Config.json")!!)
    }
}