# PR Changes Navigator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A GoLand plugin action, `PrChanges.Show`, that lists every hunk of the current branch's PR in the Find tool window so `NextOccurence`/`PreviousOccurence` walk them.

**Architecture:** Three pure Kotlin units (`HunkParser`, `BaseResolver`, `PrChangesCollector`) do the git-output work behind a small `GitRunner` interface, so they are unit-tested without the IDE. A thin platform layer (`Git4IdeaRunner`, `PrUsages`, `ShowPrChangesAction`) runs git through Git4Idea and hands `UsageInfo2UsageAdapter` objects to `UsageViewManager.showUsages`.

**Tech Stack:** Kotlin 2.4.0, IntelliJ Platform Gradle Plugin 2.19.0, Gradle 9.7.1, JDK 25 (Temurin via mise), GoLand 2026.2.3 (build 262) installed at `/Applications/GoLand.app`, bundled `Git4Idea` plugin, `kotlin("test")` on JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-27-pr-changes-navigator-design.md`

## Global Constraints

- Repository root: `/Users/collin/code/intellij-diff-navigator`. Run every command from there.
- Run Gradle and Java through mise: prefix commands with `mise exec --`. `mise.toml` already pins `java = "temurin-25"`.
- Package: `com.github.collinvandyck.diffnavigator`. Plugin id: `com.github.collinvandyck.diffnavigator`. Action id: `PrChanges.Show`. Action text: `Show PR Changes`.
- Notification group id: `Diff Navigator`, balloon display.
- Target `sinceBuild = "262"`, no `untilBuild`.
- Base ref order: `git config --get prchanges.base`, then `origin/main`, then `origin/master`.
- Diff command: `git diff -U0 --inter-hunk-context=0 --no-color --no-ext-diff --no-textconv --src-prefix=a/ --dst-prefix=b/ <merge-base> --`. Git4Idea's `GitHandler` already adds `-c core.quotepath=false`.
- Missing-base message, verbatim: ``No base branch found. Set one with `git config prchanges.base <ref>`.``
- Empty-diff message: `No changes vs <ref> (<short hash>).` Short hash is the first 7 characters.
- Tab title: `PR changes vs <ref> (<short hash>)`, plus ` +N more base` (or `bases` when N > 1) when repositories resolved to more than one distinct base, plus `, N hunks skipped` when any hunk could not be resolved to a file.
- Every test function gets a one-line comment saying what it verifies. Tests come first in each test file, helpers below them.
- Comments only where they explain non-obvious logic.
- Commit after each task with an imperative, sentence-style message. Never push.

## File Structure

| Path | Responsibility |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat` | Build. |
| `.gitignore` | Ignore build output and IDE state. |
| `src/main/kotlin/com/github/collinvandyck/diffnavigator/HunkParser.kt` | `Hunk`, `FileHunks`, `HunkParser`, and `Hunk.zeroBasedLines`. Pure. |
| `src/main/kotlin/com/github/collinvandyck/diffnavigator/GitRunner.kt` | `GitResult` and the `GitRunner` interface. Pure. |
| `src/main/kotlin/com/github/collinvandyck/diffnavigator/BaseResolver.kt` | `Base`, `BaseResult`, `BaseResolver`. Pure. |
| `src/main/kotlin/com/github/collinvandyck/diffnavigator/PrChangesCollector.kt` | Per-repository resolve, diff, parse, and the tab title. Pure. |
| `src/main/kotlin/com/github/collinvandyck/diffnavigator/Git4IdeaRunner.kt` | `GitRunner` backed by Git4Idea. Platform. |
| `src/main/kotlin/com/github/collinvandyck/diffnavigator/PrUsages.kt` | Turns collected hunks into usages. Platform. |
| `src/main/kotlin/com/github/collinvandyck/diffnavigator/ShowPrChangesAction.kt` | The action, background task, notifications, and Find window. Platform. |
| `src/main/resources/META-INF/plugin.xml` | Plugin descriptor. |
| `src/test/kotlin/com/github/collinvandyck/diffnavigator/*Test.kt` | Unit tests for the pure units. |
| `README.md` | Build, install, and the `.ideavimrc` binding. |

---

### Task 1: Build scaffold and HunkParser

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, Gradle wrapper files
- Create: `src/main/resources/META-INF/plugin.xml`
- Create: `src/main/kotlin/com/github/collinvandyck/diffnavigator/HunkParser.kt`
- Test: `src/test/kotlin/com/github/collinvandyck/diffnavigator/HunkParserTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class Hunk(val startLine: Int, val lineCount: Int)`: 1-based start line on the new side, and the new-side line count (0 for a pure deletion).
  - `fun Hunk.zeroBasedLines(documentLineCount: Int): IntRange?`: 0-based inclusive line span clamped to the document, or `null` for an empty document.
  - `data class FileHunks(val path: String, val hunks: List<Hunk>)`: `path` is relative to the repository root.
  - `object HunkParser { fun parse(lines: List<String>): List<FileHunks> }`

- [ ] **Step 1: Write the build files**

`.gitignore`:

```gitignore
.gradle/
build/
.idea/
.kotlin/
.intellijPlatform/
```

`settings.gradle.kts`:

```kotlin
rootProject.name = "intellij-diff-navigator"
```

`gradle.properties`:

```properties
kotlin.stdlib.default.dependency=false
org.gradle.jvmargs=-Xmx2g
```

`build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.collinvandyck.diffnavigator"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Applications/GoLand.app")
        bundledPlugin("Git4Idea")
        // GitRepositoryManager extends dvcs classes that are not on the compile classpath by default. Both are needed.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
    }
    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks.test {
    useJUnitPlatform()
}
```

`src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>com.github.collinvandyck.diffnavigator</id>
    <name>Diff Navigator</name>
    <vendor>collinvandyck</vendor>
    <description><![CDATA[
    Lists every hunk of the current branch's PR in the Find tool window, so Next/Previous Occurrence walks them.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>Git4Idea</depends>
</idea-plugin>
```

- [ ] **Step 2: Generate the Gradle wrapper**

Run: `mise exec gradle@9.7.1 -- gradle wrapper --gradle-version 9.7.1`
Expected: `BUILD SUCCESSFUL`, and `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` exist.

- [ ] **Step 3: Write the failing test**

The fixture is real `git diff -U0` output from the spec's diff command, captured from a scratch repo, with one hand-written file (`plus.txt`) added. `⇥` marks a literal tab, and `fixture()` replaces it. `"b/tab\tname.txt"` keeps a literal backslash-t, because that is how git quotes it.

`src/test/kotlin/com/github/collinvandyck/diffnavigator/HunkParserTest.kt`:

```kotlin
package com.github.collinvandyck.diffnavigator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HunkParserTest {
    // Every case in the captured diff parses to the expected files and hunks, in order.
    @Test
    fun `parses captured diff`() {
        val expected = listOf(
            FileHunks("added.txt", listOf(Hunk(1, 2))),
            FileHunks("ctx.txt", listOf(Hunk(1, 4))),
            FileHunks("dash.txt", listOf(Hunk(1, 0))),
            FileHunks("mod.txt", listOf(Hunk(1, 1), Hunk(5, 1), Hunk(8, 2))),
            FileHunks("nonl.txt", listOf(Hunk(2, 1))),
            FileHunks("plus.txt", listOf(Hunk(2, 1))),
            FileHunks("renamed_new.txt", listOf(Hunk(2, 1))),
            FileHunks("sp ace.txt", listOf(Hunk(1, 1))),
            FileHunks("tab\tname.txt", listOf(Hunk(1, 1))),
        )
        assertEquals(expected, HunkParser.parse(fixture()))
    }

    // An added line that looks like a +++ header stays inside its hunk and does not rename the file.
    @Test
    fun `added line that looks like a header is not a path`() {
        val lines = listOf(
            "diff --git a/plus.txt b/plus.txt",
            "--- a/plus.txt",
            "+++ b/plus.txt",
            "@@ -1,0 +2 @@ keep",
            "+++ b/bogus.txt",
        )
        assertEquals(listOf(FileHunks("plus.txt", listOf(Hunk(2, 1)))), HunkParser.parse(lines))
    }

    // A hunk header that promises more lines than follow does not swallow the next file.
    @Test
    fun `resyncs at the next file header`() {
        val lines = listOf(
            "diff --git a/short.txt b/short.txt",
            "--- a/short.txt",
            "+++ b/short.txt",
            "@@ -1,3 +1,3 @@",
            "-a",
            "+A",
            "diff --git a/next.txt b/next.txt",
            "--- a/next.txt",
            "+++ b/next.txt",
            "@@ -4 +4 @@",
            "-x",
            "+y",
        )
        assertEquals(
            listOf(FileHunks("short.txt", listOf(Hunk(1, 3))), FileHunks("next.txt", listOf(Hunk(4, 1)))),
            HunkParser.parse(lines),
        )
    }

    // A deletion at the top of a file (+0,0) anchors on line 1.
    @Test
    fun `deletion at top of file anchors on line one`() {
        val lines = listOf(
            "diff --git a/top.txt b/top.txt",
            "--- a/top.txt",
            "+++ b/top.txt",
            "@@ -1 +0,0 @@",
            "-first",
        )
        assertEquals(listOf(FileHunks("top.txt", listOf(Hunk(1, 0)))), HunkParser.parse(lines))
    }

    // Octal escapes in a quoted path decode as UTF-8 bytes.
    @Test
    fun `quoted path decodes octal utf8`() {
        assertEquals("café.txt", HunkParser.parsePath("\"b/caf\\303\\251.txt\""))
    }

    // A multi-line hunk spans its lines, clamped to the document.
    @Test
    fun `zero based lines spans and clamps`() {
        assertEquals(4..6, Hunk(5, 3).zeroBasedLines(documentLineCount = 100))
        assertEquals(4..4, Hunk(5, 0).zeroBasedLines(documentLineCount = 100))
        assertEquals(8..9, Hunk(9, 5).zeroBasedLines(documentLineCount = 10))
        assertEquals(9..9, Hunk(20, 1).zeroBasedLines(documentLineCount = 10))
        assertNull(Hunk(1, 1).zeroBasedLines(documentLineCount = 0))
    }

    private fun fixture(): List<String> = """
        diff --git a/added.txt b/added.txt
        new file mode 100644
        index 0000000..5786b13
        --- /dev/null
        +++ b/added.txt
        @@ -0,0 +1,2 @@
        +brand
        +new
        diff --git a/bin.dat b/bin.dat
        index 88768ef..3e3315e 100644
        Binary files a/bin.dat and b/bin.dat differ
        diff --git a/ctx.txt b/ctx.txt
        index 3333333..4444444 100644
        --- a/ctx.txt
        +++ b/ctx.txt
        @@ -1,4 +1,4 @@
        -a
        +A
         b
         c
        -d
        +D
        diff --git a/dash.txt b/dash.txt
        index c93c039..4031952 100644
        --- a/dash.txt
        +++ b/dash.txt
        @@ -2 +1,0 @@ keep
        --- dashes
        diff --git a/deleted.txt b/deleted.txt
        deleted file mode 100644
        index 286c5f5..0000000
        --- a/deleted.txt
        +++ /dev/null
        @@ -1 +0,0 @@
        -gone
        diff --git a/mod.txt b/mod.txt
        index b00a0f1..0f499cc 100644
        --- a/mod.txt
        +++ b/mod.txt
        @@ -1 +1 @@
        -one
        +ONE
        @@ -5 +5 @@ four
        -five
        +FIVE
        @@ -8 +8,2 @@ seven
        -eight
        +new8
        +nine
        diff --git a/nonl.txt b/nonl.txt
        index b77b4eb..6e94b48 100644
        --- a/nonl.txt
        +++ b/nonl.txt
        @@ -2 +2 @@ x
        -y
        +z
        \ No newline at end of file
        diff --git a/plus.txt b/plus.txt
        index 1111111..2222222 100644
        --- a/plus.txt
        +++ b/plus.txt
        @@ -1,0 +2 @@ keep
        +++ b/bogus.txt
        diff --git a/renamed_old.txt b/renamed_new.txt
        similarity index 66%
        rename from renamed_old.txt
        rename to renamed_new.txt
        index de98044..7be73ce 100644
        --- a/renamed_old.txt
        +++ b/renamed_new.txt
        @@ -2 +2 @@ a
        -b
        +B
        diff --git a/same_old.txt b/same_new.txt
        similarity index 100%
        rename from same_old.txt
        rename to same_new.txt
        diff --git a/sp ace.txt b/sp ace.txt
        index 587be6b..975fbec 100644
        --- a/sp ace.txt⇥
        +++ b/sp ace.txt⇥
        @@ -1 +1 @@
        -x
        +y
        diff --git "a/tab\tname.txt" "b/tab\tname.txt"
        index 587be6b..b680253 100644
        --- "a/tab\tname.txt"
        +++ "b/tab\tname.txt"
        @@ -1 +1 @@
        -x
        +z
    """.trimIndent().replace("⇥", "\t").lines()
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `mise exec -- ./gradlew test --tests '*HunkParserTest*' > build-test.log 2>&1; echo exit=$?`
Expected: non-zero exit. `build-test.log` shows `Unresolved reference` errors for `HunkParser`, `FileHunks`, and `Hunk`. The first run also downloads Gradle plugins, which can take a few minutes.

- [ ] **Step 5: Implement HunkParser**

`src/main/kotlin/com/github/collinvandyck/diffnavigator/HunkParser.kt`:

```kotlin
package com.github.collinvandyck.diffnavigator

import java.io.ByteArrayOutputStream

data class Hunk(val startLine: Int, val lineCount: Int)

data class FileHunks(val path: String, val hunks: List<Hunk>)

fun Hunk.zeroBasedLines(documentLineCount: Int): IntRange? {
    if (documentLineCount <= 0) return null
    val last = documentLineCount - 1
    val first = (startLine - 1).coerceIn(0, last)
    val end = (startLine - 1 + maxOf(lineCount, 1) - 1).coerceIn(first, last)
    return first..end
}

object HunkParser {
    private val header = Regex("""^@@ -\d+(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
    private val simpleEscapes = mapOf(
        'a' to '\u0007', 'b' to '\b', 'f' to '\u000c', 'n' to '\n',
        'r' to '\r', 't' to '\t', 'v' to '\u000b', '"' to '"', '\\' to '\\',
    )

    fun parse(lines: List<String>): List<FileHunks> {
        val result = mutableListOf<FileHunks>()
        var path: String? = null
        var hunks = mutableListOf<Hunk>()
        var oldLeft = 0
        var newLeft = 0

        fun flush() {
            val p = path
            if (p != null && hunks.isNotEmpty()) result += FileHunks(p, hunks.toList())
            path = null
            hunks = mutableListOf()
        }

        for (line in lines) {
            // Inside a hunk every line is content, even one that looks like a header. Content lines start with -, +,
            // space, or \, so a diff header here means the counts were off: resync instead of swallowing the next file.
            if (oldLeft > 0 || newLeft > 0) {
                if (!line.startsWith("diff --git ")) {
                    when {
                        line.startsWith("-") -> oldLeft--
                        line.startsWith("+") -> newLeft--
                        line.startsWith(" ") -> {
                            oldLeft--
                            newLeft--
                        }
                    }
                    continue
                }
                oldLeft = 0
                newLeft = 0
            }
            when {
                line.startsWith("diff --git ") -> flush()
                line.startsWith("+++ ") -> path = parsePath(line.removePrefix("+++ "))
                line.startsWith("@@ ") -> {
                    val m = header.find(line) ?: continue
                    oldLeft = m.groupValues[1].ifEmpty { "1" }.toInt()
                    newLeft = m.groupValues[3].ifEmpty { "1" }.toInt()
                    if (path != null) hunks += Hunk(maxOf(m.groupValues[2].toInt(), 1), newLeft)
                }
            }
        }
        flush()
        return result
    }

    internal fun parsePath(raw: String): String? {
        // Git appends a tab to header paths that contain a space.
        val trimmed = raw.removeSuffix("\t")
        if (trimmed == "/dev/null") return null
        val unquoted = if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            unquote(trimmed.substring(1, trimmed.length - 1))
        } else {
            trimmed
        }
        return unquoted.removePrefix("b/")
    }

    private fun unquote(s: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.writeBytes(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            val octal = s.substring(i + 1, minOf(i + 4, s.length))
            if (octal.length == 3 && octal.all { it in '0'..'7' }) {
                out.write(octal.toInt(8))
                i += 4
                continue
            }
            val next = s[i + 1]
            out.writeBytes((simpleEscapes[next] ?: next).toString().toByteArray(Charsets.UTF_8))
            i += 2
        }
        return out.toString(Charsets.UTF_8)
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `mise exec -- ./gradlew test --tests '*HunkParserTest*' > build-test.log 2>&1; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 7: Prove the line counting matters**

Temporarily delete the whole `if (oldLeft > 0 || newLeft > 0) { ... }` block, comment included. Run the same command.
Expected: non-zero exit, with `parses captured diff` and `added line that looks like a header is not a path` failing because `+++ b/bogus.txt` renames the file. Restore the block, rerun, and confirm `exit=0`.

- [ ] **Step 8: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle src
git commit -m "Scaffold the plugin build and parse git diff -U0 hunks."
```

---

### Task 2: GitRunner and BaseResolver

**Files:**
- Create: `src/main/kotlin/com/github/collinvandyck/diffnavigator/GitRunner.kt`
- Create: `src/main/kotlin/com/github/collinvandyck/diffnavigator/BaseResolver.kt`
- Test: `src/test/kotlin/com/github/collinvandyck/diffnavigator/BaseResolverTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `data class GitResult(val exitCode: Int, val stdout: List<String>, val stderr: String)`
  - `fun interface GitRunner { fun run(command: String, args: List<String>): GitResult }`: `command` is one of `config`, `rev-parse`, `merge-base`, or `diff`.
  - `data class Base(val ref: String, val commit: String) { val shortCommit: String }`
  - `sealed interface BaseResult { data class Found(val base: Base); data class Failed(val message: String) }`
  - `class BaseResolver(git: GitRunner) { fun resolve(): BaseResult }`
  - `BaseResolver.NO_BASE_MESSAGE` holds the verbatim missing-base message.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/github/collinvandyck/diffnavigator/BaseResolverTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mise exec -- ./gradlew test --tests '*BaseResolverTest*' > build-test.log 2>&1; echo exit=$?`
Expected: non-zero exit, with `Unresolved reference` errors for `GitRunner`, `GitResult`, `BaseResolver`, `BaseResult`, and `Base`.

- [ ] **Step 3: Implement GitRunner and BaseResolver**

`src/main/kotlin/com/github/collinvandyck/diffnavigator/GitRunner.kt`:

```kotlin
package com.github.collinvandyck.diffnavigator

data class GitResult(val exitCode: Int, val stdout: List<String>, val stderr: String)

fun interface GitRunner {
    fun run(command: String, args: List<String>): GitResult
}
```

`src/main/kotlin/com/github/collinvandyck/diffnavigator/BaseResolver.kt`:

```kotlin
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
```

- [ ] **Step 4: Run all tests to verify they pass**

Run: `mise exec -- ./gradlew test > build-test.log 2>&1; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Resolve the PR base from git config, origin/main, or origin/master."
```

---

### Task 3: PrChangesCollector

**Files:**
- Create: `src/main/kotlin/com/github/collinvandyck/diffnavigator/PrChangesCollector.kt`
- Test: `src/test/kotlin/com/github/collinvandyck/diffnavigator/PrChangesCollectorTest.kt`

**Interfaces:**
- Consumes: `HunkParser.parse`, `FileHunks`, and `Hunk` from Task 1. `GitRunner`, `GitResult`, `BaseResolver`, `BaseResult`, and `Base` from Task 2.
- Produces:
  - `data class RepoInput<K>(val key: K, val name: String, val git: GitRunner)`: `key` lets the platform layer carry the repository root without the pure code knowing its type.
  - `data class RepoChanges<K>(val key: K, val base: Base, val files: List<FileHunks>)`
  - `data class Collected<K>(val changes: List<RepoChanges<K>>, val errors: List<String>)` with `val hunkCount: Int` and `fun title(skipped: Int): String`.
  - `object PrChangesCollector { val DIFF_ARGS: List<String>; fun <K> collect(repos: List<RepoInput<K>>): Collected<K> }`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/github/collinvandyck/diffnavigator/PrChangesCollectorTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mise exec -- ./gradlew test --tests '*PrChangesCollectorTest*' > build-test.log 2>&1; echo exit=$?`
Expected: non-zero exit, with `Unresolved reference` errors for `PrChangesCollector`, `RepoInput`, `RepoChanges`, and `Collected`.

- [ ] **Step 3: Implement PrChangesCollector**

`src/main/kotlin/com/github/collinvandyck/diffnavigator/PrChangesCollector.kt`:

```kotlin
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
```

- [ ] **Step 4: Run all tests to verify they pass**

Run: `mise exec -- ./gradlew test > build-test.log 2>&1; echo exit=$?`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

```bash
git add src
git commit -m "Collect PR hunks across repositories and build the tab title."
```

---

### Task 4: Platform layer, action, and install

**Files:**
- Create: `src/main/kotlin/com/github/collinvandyck/diffnavigator/Git4IdeaRunner.kt`
- Create: `src/main/kotlin/com/github/collinvandyck/diffnavigator/PrUsages.kt`
- Create: `src/main/kotlin/com/github/collinvandyck/diffnavigator/ShowPrChangesAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Create: `README.md`

**Interfaces:**
- Consumes: `RepoInput`, `Collected`, `PrChangesCollector.collect`, `FileHunks`, `Hunk.zeroBasedLines`, and `GitRunner`/`GitResult`.
- Produces: action `PrChanges.Show`, and the installable zip `build/distributions/intellij-diff-navigator-0.1.0.zip`.

These platform APIs were checked against the installed GoLand 2026.2.3 jars with `javap`:
- `GitLineHandler(Project, VirtualFile, GitCommand)`, `addConfigParameters(String...)`, `addParameters(List<String>)`, `setSilent(boolean)`, `setStdoutSuppressed(boolean)`
- `Git.getInstance().runCommand(GitLineHandler)` returns a `GitCommandResult` with `getExitCode()`, `getOutput()`, and `getErrorOutputAsJoinedString()`
- `GitCommand.CONFIG`, `REV_PARSE`, `MERGE_BASE`, `DIFF`, and `GitRepositoryManager.getInstance(project).getRepositories()`
- `UsageViewManager.getInstance(project).showUsages(UsageTarget[], Usage[], UsageViewPresentation)`, `UsageInfo(PsiElement, int, int)`, `UsageInfo2UsageAdapter(UsageInfo)`
- `UsageViewPresentation.setTabText`, `setTabName`, `setToolwindowTitle`, `setOpenInNewTab`, `setShowCancelButton`, `setSearchString`, `setCodeUsagesString`. `setUsagesString` is deprecated in 262, so the plan doesn't use it.
- `UsageView.close()`, `VfsUtil.markDirtyAndRefresh(boolean, boolean, boolean, Path...)`, `ReadAction.nonBlocking(Callable).executeSynchronously()`. `ReadAction.compute` is deprecated in 262.
- `GitRepositoryManager` needs `intellij.platform.vcs.dvcs` and `intellij.platform.vcs.dvcs.impl` on the compile classpath (see `build.gradle.kts`).

There are no unit tests in this task. The layer is glue over platform APIs, and the spec calls for manual verification. The task's gate is a clean `buildPlugin` plus the manual checks in Step 6.

- [ ] **Step 1: Write Git4IdeaRunner**

`src/main/kotlin/com/github/collinvandyck/diffnavigator/Git4IdeaRunner.kt`:

```kotlin
package com.github.collinvandyck.diffnavigator

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

class Git4IdeaRunner(private val project: Project, private val root: VirtualFile) : GitRunner {
    override fun run(command: String, args: List<String>): GitResult {
        val handler = GitLineHandler(project, root, COMMANDS.getValue(command))
        handler.addParameters(args)
        handler.setSilent(true)
        handler.setStdoutSuppressed(true)
        val result = Git.getInstance().runCommand(handler)
        return GitResult(result.exitCode, result.output, result.errorOutputAsJoinedString)
    }

    private companion object {
        val COMMANDS = mapOf(
            "config" to GitCommand.CONFIG,
            "rev-parse" to GitCommand.REV_PARSE,
            "merge-base" to GitCommand.MERGE_BASE,
            "diff" to GitCommand.DIFF,
        )
    }
}
```

- [ ] **Step 2: Write PrUsages**

`src/main/kotlin/com/github/collinvandyck/diffnavigator/PrUsages.kt`:

```kotlin
package com.github.collinvandyck.diffnavigator

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter

data class PrUsages(val usages: List<Usage>, val skipped: Int)

// Must run inside a read action.
fun buildPrUsages(project: Project, collected: Collected<VirtualFile>): PrUsages {
    val psiManager = PsiManager.getInstance(project)
    val documents = FileDocumentManager.getInstance()
    val usages = mutableListOf<Usage>()
    var skipped = 0
    for (repo in collected.changes) {
        for (file in repo.files) {
            val vf = repo.key.findFileByRelativePath(file.path)
            val psi = vf?.let(psiManager::findFile)
            val document = vf?.let(documents::getDocument)
            if (psi == null || document == null) {
                skipped += file.hunks.size
                continue
            }
            for (hunk in file.hunks) {
                val lines = hunk.zeroBasedLines(document.lineCount)
                if (lines == null) {
                    skipped++
                    continue
                }
                val start = document.getLineStartOffset(lines.first)
                val end = document.getLineEndOffset(lines.last)
                usages += UsageInfo2UsageAdapter(UsageInfo(psi, start, end))
            }
        }
    }
    return PrUsages(usages, skipped)
}
```

- [ ] **Step 3: Write ShowPrChangesAction**

`src/main/kotlin/com/github/collinvandyck/diffnavigator/ShowPrChangesAction.kt`:

```kotlin
package com.github.collinvandyck.diffnavigator

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageView
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import git4idea.repo.GitRepositoryManager

class ShowPrChangesAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // The diff reads the working tree from disk, so unsaved edits would shift line numbers.
        FileDocumentManager.getInstance().saveAllDocuments()
        LoadTask(project).queue()
    }

    private class LoadTask(project: Project) : Task.Backgroundable(project, "Loading PR changes", true) {
        private var collected: Collected<VirtualFile>? = null
        private var prUsages: PrUsages? = null

        override fun run(indicator: ProgressIndicator) {
            val repos = GitRepositoryManager.getInstance(project).repositories
            if (repos.isEmpty()) {
                notify(project, "No git repository found.", NotificationType.WARNING)
                return
            }
            val result = PrChangesCollector.collect(
                repos.map { RepoInput(it.root, it.root.name, Git4IdeaRunner(project, it.root)) },
            )
            // Files changed outside the IDE (checkout, pull, rebase) can be stale in the VFS. Refresh only the changed
            // paths, synchronously, and outside any read action.
            val changedPaths = result.changes.flatMap { repo -> repo.files.map { repo.key.toNioPath().resolve(it.path) } }
            if (changedPaths.isNotEmpty()) {
                VfsUtil.markDirtyAndRefresh(false, false, false, *changedPaths.toTypedArray())
            }
            collected = result
            prUsages = ReadAction.nonBlocking<PrUsages> { buildPrUsages(project, result) }.executeSynchronously()
        }

        override fun onSuccess() {
            val result = collected ?: return
            val built = prUsages ?: return
            val errors = result.errors
            if (built.usages.isEmpty()) {
                val base = result.changes.firstOrNull()?.base
                val summary = when {
                    base == null -> null
                    result.hunkCount == 0 -> "No changes vs ${base.ref} (${base.shortCommit})."
                    else -> "${built.skipped} hunks vs ${base.ref} (${base.shortCommit}) could not be resolved to project files."
                }
                val lines = listOfNotNull(summary) + errors
                if (lines.isNotEmpty()) {
                    val quiet = errors.isEmpty() && result.hunkCount == 0
                    notify(project, lines.joinToString("\n"), if (quiet) NotificationType.INFORMATION else NotificationType.WARNING)
                }
                return
            }
            if (errors.isNotEmpty()) notify(project, errors.joinToString("\n"), NotificationType.WARNING)
            val title = result.title(built.skipped)
            val presentation = UsageViewPresentation()
            presentation.setTabText(title)
            presentation.setTabName(title)
            presentation.setToolwindowTitle(title)
            presentation.setSearchString("PR changes")
            presentation.setCodeUsagesString("PR changes")
            // A new tab plus closing our own previous one. Reusing the tab (openInNewTab=false) would also close the
            // user's unpinned Find Usages tabs.
            presentation.setOpenInNewTab(true)
            presentation.setShowCancelButton(false)
            val holder = project.service<PrChangesViewHolder>()
            holder.view?.close()
            holder.view = UsageViewManager.getInstance(project)
                .showUsages(emptyArray<UsageTarget>(), built.usages.toTypedArray(), presentation)
        }

        override fun onThrowable(error: Throwable) {
            notify(project, "Show PR Changes failed: ${error.message ?: error.javaClass.simpleName}", NotificationType.ERROR)
        }
    }

    private companion object {
        fun notify(project: Project, message: String, type: NotificationType) {
            val html = StringUtil.escapeXmlEntities(message).replace("\n", "<br>")
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Diff Navigator")
                .createNotification(html, type)
                .notify(project)
        }
    }
}

@Service(Service.Level.PROJECT)
class PrChangesViewHolder {
    var view: UsageView? = null
}
```

- [ ] **Step 4: Register the action and notification group**

Replace `src/main/resources/META-INF/plugin.xml` with:

```xml
<idea-plugin>
    <id>com.github.collinvandyck.diffnavigator</id>
    <name>Diff Navigator</name>
    <vendor>collinvandyck</vendor>
    <description><![CDATA[
    Lists every hunk of the current branch's PR in the Find tool window, so Next/Previous Occurrence walks them.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>Git4Idea</depends>

    <extensions defaultExtensionNs="com.intellij">
        <notificationGroup id="Diff Navigator" displayType="BALLOON"/>
    </extensions>

    <actions>
        <action id="PrChanges.Show"
                class="com.github.collinvandyck.diffnavigator.ShowPrChangesAction"
                text="Show PR Changes"
                description="List every hunk of the current branch's PR in the Find tool window"/>
    </actions>
</idea-plugin>
```

- [ ] **Step 5: Build the plugin**

Run: `mise exec -- ./gradlew test buildPlugin > build-plugin.log 2>&1; echo exit=$?`
Expected: `exit=0`, and `build/distributions/intellij-diff-navigator-0.1.0.zip` exists. If compilation fails on a platform API, check its signature with `mise exec -- javap -cp <jar> <class>` against the jars under `/Applications/GoLand.app/Contents/lib` and `/Applications/GoLand.app/Contents/plugins/vcs-git/lib`. Adjust the call to match, without changing behavior.

- [ ] **Step 6: Manual verification (human, in the IDE)**

These need the GoLand UI, so the plugin's user runs them:
1. Install the zip with Settings, Plugins, the gear icon, Install Plugin from Disk. Restart GoLand.
2. Open a project checked out on a PR branch. Run Find Action, then "Show PR Changes".
3. Expected: the Find tool window opens a tab titled `PR changes vs origin/main (<hash>)` that lists each hunk, grouped by file.
4. With the caret in the editor, `<C-j>`/`<C-k>` (`NextOccurence`/`PreviousOccurence`) step through the hunks and move the editor to each one.
5. Run the action again. The tab is replaced rather than duplicated, and an unrelated unpinned Find Usages tab stays open. Close the PR tab by hand and run the action again, which must work without an error.
6. In a repo with no `origin/main` or `origin/master`, the action shows the missing-base notification.

- [ ] **Step 7: Write the README**

`README.md`:

````markdown
# intellij-diff-navigator

A GoLand plugin that lists every hunk of the current branch's PR in the Find tool window, so Next/Previous Occurrence walks them without unstaging anything.

## Build and install

```sh
mise exec -- ./gradlew buildPlugin
```

Install `build/distributions/intellij-diff-navigator-0.1.0.zip` with Settings, Plugins, the gear icon, Install Plugin from Disk.

## Use

Run "Show PR Changes" from Find Action, or bind it in `.ideavimrc`:

```vim
nmap <leader>gp <Action>(PrChanges.Show)
```

Then step through the hunks with Next/Previous Occurrence.

The base is the merge base of `HEAD` and `origin/main`, or `origin/master` when there is no `origin/main`. Override it per repository:

```sh
git config prchanges.base origin/develop
```

Uncommitted edits are included. Untracked files are not.
````

- [ ] **Step 8: Commit**

```bash
git add src README.md
git commit -m "Show PR hunks in the Find tool window."
```
