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
