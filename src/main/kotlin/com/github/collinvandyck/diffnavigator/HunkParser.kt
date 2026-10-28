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
