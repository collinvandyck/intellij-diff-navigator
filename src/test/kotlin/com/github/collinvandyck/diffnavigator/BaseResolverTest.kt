package com.github.collinvandyck.diffnavigator

import kotlin.test.Test
import kotlin.test.assertEquals

class BaseResolverTest {
    // The prchanges.base config wins over the default branches.
    @Test
    fun `config override wins`() {
        val git = FakeGit(config = "origin/develop", refs = setOf("origin/main", "origin/develop"))
        assertEquals(found("origin/develop"), BaseResolver(git).resolve())
    }

    // Without config, origin/main is used when it exists.
    @Test
    fun `defaults to origin main`() {
        val git = FakeGit(refs = setOf("origin/main", "origin/master"))
        assertEquals(found("origin/main"), BaseResolver(git).resolve())
    }

    // Without config or origin/main, origin/master is used.
    @Test
    fun `falls back to origin master`() {
        val git = FakeGit(refs = setOf("origin/master"))
        assertEquals(found("origin/master"), BaseResolver(git).resolve())
    }

    // With no config and no default branch, resolution fails with the setup hint.
    @Test
    fun `no base found`() {
        assertEquals(BaseResult.Failed(BaseResolver.NO_BASE_MESSAGE), BaseResolver(FakeGit()).resolve())
    }

    // A merge-base failure surfaces git's error output.
    @Test
    fun `merge base failure reports stderr`() {
        val git = FakeGit(refs = setOf("origin/main"), mergeBaseError = "fatal: no merge base")
        assertEquals(
            BaseResult.Failed("git merge-base HEAD origin/main failed: fatal: no merge base"),
            BaseResolver(git).resolve(),
        )
    }

    // The short commit is the first seven characters.
    @Test
    fun `short commit is seven chars`() {
        assertEquals("3376f51", Base("origin/main", "3376f5167662c4c70d0ee5c3cf29da84f83170e4").shortCommit)
    }

    private fun found(ref: String) = BaseResult.Found(Base(ref, "merge-of-$ref"))

    private class FakeGit(
        private val config: String? = null,
        private val refs: Set<String> = emptySet(),
        private val mergeBaseError: String? = null,
    ) : GitRunner {
        override fun run(command: String, args: List<String>): GitResult = when (command) {
            "config" ->
                if (config != null) GitResult(0, listOf(config), "") else GitResult(1, emptyList(), "")
            "rev-parse" -> {
                val ref = args.last().removeSuffix("^{commit}")
                if (ref in refs) GitResult(0, listOf("sha-$ref"), "") else GitResult(1, emptyList(), "")
            }
            "merge-base" ->
                if (mergeBaseError != null) GitResult(128, emptyList(), mergeBaseError)
                else GitResult(0, listOf("merge-of-${args.last()}"), "")
            else -> error("unexpected git $command $args")
        }
    }
}
