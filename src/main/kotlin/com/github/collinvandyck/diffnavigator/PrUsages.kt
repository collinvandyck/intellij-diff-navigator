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
