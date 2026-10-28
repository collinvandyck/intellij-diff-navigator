package com.github.collinvandyck.diffnavigator

data class GitResult(val exitCode: Int, val stdout: List<String>, val stderr: String)

fun interface GitRunner {
    fun run(command: String, args: List<String>): GitResult
}
