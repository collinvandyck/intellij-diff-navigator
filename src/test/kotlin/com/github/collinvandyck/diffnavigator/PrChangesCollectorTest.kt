package com.github.collinvandyck.diffnavigator

import kotlin.test.Test
import kotlin.test.assertEquals

class PrChangesCollectorTest {
    // A healthy repository yields its base and parsed hunks, and diff runs against the merge base.
    @Test
    fun `collects hunks from one repo`() {
        val git = RepoGit(diff = DIFF)
        val collected = PrChangesCollector.collect(listOf(RepoInput("key", "repo", git)))
        assertEquals(
            Collected(
                changes = listOf(
                    RepoChanges("key", Base("origin/main", "abcdef1234"), listOf(FileHunks("a.go", listOf(Hunk(3, 2))))),
                ),
                errors = emptyList(),
            ),
            collected,
        )
        assertEquals(PrChangesCollector.DIFF_ARGS + listOf("abcdef1234", "--"), git.diffArgs)
    }

    // A repository that fails is reported by name while the others still contribute hunks.
    @Test
    fun `one failing repo does not hide the others`() {
        val collected = PrChangesCollector.collect(
            listOf(
                RepoInput(1, "broken", RepoGit(hasMain = false)),
                RepoInput(2, "ok", RepoGit(diff = DIFF)),
            ),
        )
        assertEquals(listOf("broken: ${BaseResolver.NO_BASE_MESSAGE}"), collected.errors)
        assertEquals(listOf(2), collected.changes.map { it.key })
    }

    // A failing git diff surfaces its stderr.
    @Test
    fun `diff failure reports stderr`() {
        val collected = PrChangesCollector.collect(listOf(RepoInput(1, "repo", RepoGit(diffError = "fatal: bad object"))))
        assertEquals(listOf("repo: git diff failed: fatal: bad object"), collected.errors)
        assertEquals(emptyList(), collected.changes)
    }

    // The title names the first base, counts extra bases, and notes skipped hunks.
    @Test
    fun `title describes bases and skips`() {
        val one = RepoChanges(1, Base("origin/main", "1111111aaa"), emptyList())
        val two = RepoChanges(2, Base("origin/master", "2222222bbb"), emptyList())
        assertEquals("PR changes vs origin/main (1111111)", Collected(listOf(one), emptyList()).title(skipped = 0))
        assertEquals(
            "PR changes vs origin/main (1111111) +1 more base, 3 hunks skipped",
            Collected(listOf(one, two, one.copy(key = 3)), emptyList()).title(skipped = 3),
        )
    }

    private class RepoGit(
        private val hasMain: Boolean = true,
        private val diff: List<String> = emptyList(),
        private val diffError: String? = null,
    ) : GitRunner {
        var diffArgs: List<String>? = null

        override fun run(command: String, args: List<String>): GitResult = when (command) {
            "config" -> GitResult(1, emptyList(), "")
            "rev-parse" -> GitResult(if (hasMain && args.last() == "origin/main^{commit}") 0 else 1, emptyList(), "")
            "merge-base" -> GitResult(0, listOf("abcdef1234"), "")
            "diff" -> {
                diffArgs = args
                if (diffError != null) GitResult(128, emptyList(), diffError) else GitResult(0, diff, "")
            }
            else -> error("unexpected git $command $args")
        }
    }

    private companion object {
        val DIFF = listOf(
            "diff --git a/a.go b/a.go",
            "--- a/a.go",
            "+++ b/a.go",
            "@@ -3 +3,2 @@",
            "-old",
            "+new",
            "+newer",
        )
    }
}
