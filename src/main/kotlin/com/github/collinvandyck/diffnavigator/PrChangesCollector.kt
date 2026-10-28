package com.github.collinvandyck.diffnavigator

data class RepoInput<K>(val key: K, val name: String, val git: GitRunner)

data class RepoChanges<K>(val key: K, val base: Base, val files: List<FileHunks>)

data class Collected<K>(val changes: List<RepoChanges<K>>, val errors: List<String>) {
    val hunkCount: Int get() = changes.sumOf { repo -> repo.files.sumOf { it.hunks.size } }

    fun title(skipped: Int): String {
        val bases = changes.map { it.base }.distinct()
        val first = bases.first()
        val extra = bases.size - 1
        val more = when {
            extra == 1 -> " +1 more base"
            extra > 1 -> " +$extra more bases"
            else -> ""
        }
        val skips = if (skipped > 0) ", $skipped hunks skipped" else ""
        return "PR changes vs ${first.ref} (${first.shortCommit})$more$skips"
    }
}

object PrChangesCollector {
    // Each flag overrides a user config that would change the output format: color.diff, diff.external, textconv
    // drivers, diff.noprefix/mnemonicPrefix, and diff.interHunkContext (which merges hunks and adds context lines).
    val DIFF_ARGS = listOf(
        "-U0", "--inter-hunk-context=0", "--no-color", "--no-ext-diff", "--no-textconv",
        "--src-prefix=a/", "--dst-prefix=b/",
    )

    fun <K> collect(repos: List<RepoInput<K>>): Collected<K> {
        val changes = mutableListOf<RepoChanges<K>>()
        val errors = mutableListOf<String>()
        for (repo in repos) {
            val base = when (val resolved = BaseResolver(repo.git).resolve()) {
                is BaseResult.Found -> resolved.base
                is BaseResult.Failed -> {
                    errors += "${repo.name}: ${resolved.message}"
                    continue
                }
            }
            val diff = repo.git.run("diff", DIFF_ARGS + listOf(base.commit, "--"))
            if (diff.exitCode != 0) {
                errors += "${repo.name}: git diff failed: ${diff.stderr.trim()}"
                continue
            }
            changes += RepoChanges(repo.key, base, HunkParser.parse(diff.stdout))
        }
        return Collected(changes, errors)
    }
}
