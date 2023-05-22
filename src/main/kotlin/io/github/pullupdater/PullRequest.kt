package io.github.pullupdater

import kotlinx.serialization.Serializable

@Serializable
data class GithubUser(val login: String) {
    val userName get() = login
}

@Serializable
data class GithubRepository(val name: String)

@Serializable
data class PullRequest(val head: Branch, val base: Branch, val merged: Boolean, val mergeable: Boolean) {
    @Serializable
    data class Branch(val ref: String, val user: GithubUser, val repo: GithubRepository) {
        val branchName get() = ref
    }
}