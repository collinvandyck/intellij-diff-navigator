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
