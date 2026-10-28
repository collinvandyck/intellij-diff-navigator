package com.github.collinvandyck.diffnavigator

data class Base(val ref: String, val commit: String) {
    val shortCommit: String get() = commit.take(7)
}

sealed interface BaseResult {
    data class Found(val base: Base) : BaseResult
    data class Failed(val message: String) : BaseResult
}

class BaseResolver(private val git: GitRunner) {
    fun resolve(): BaseResult {
        val ref = configuredRef() ?: DEFAULT_REFS.firstOrNull(::refExists)
            ?: return BaseResult.Failed(NO_BASE_MESSAGE)
        val result = git.run("merge-base", listOf("HEAD", ref))
        val commit = result.stdout.firstOrNull()?.trim().orEmpty()
        if (result.exitCode != 0 || commit.isEmpty()) {
            return BaseResult.Failed("git merge-base HEAD $ref failed: ${result.stderr.trim()}")
        }
        return BaseResult.Found(Base(ref, commit))
    }

    private fun configuredRef(): String? {
        val result = git.run("config", listOf("--get", CONFIG_KEY))
        if (result.exitCode != 0) return null
        return result.stdout.firstOrNull()?.trim()?.ifEmpty { null }
    }

    private fun refExists(ref: String): Boolean =
        git.run("rev-parse", listOf("--verify", "--quiet", "$ref^{commit}")).exitCode == 0

    companion object {
        const val CONFIG_KEY = "prchanges.base"
        const val NO_BASE_MESSAGE = "No base branch found. Set one with `git config prchanges.base <ref>`."
        val DEFAULT_REFS = listOf("origin/main", "origin/master")
    }
}
