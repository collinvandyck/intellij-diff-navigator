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
