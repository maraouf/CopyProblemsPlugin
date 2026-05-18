package com.moraouf.copyproblems

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.util.Processor
import java.awt.datatransfer.StringSelection

class CopyProblemsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val document = editor.document
        val fileName = psiFile.name
        val settings = CopyProblemsSettings.getInstance()

        val highlights = try {
            ReadAction.compute<List<HighlightInfo>, Throwable> {
                val collected = mutableListOf<HighlightInfo>()
                DaemonCodeAnalyzerEx.processHighlights(
                    document,
                    project,
                    null,
                    0,
                    document.textLength,
                    Processor { info ->
                        collected.add(info)
                        true
                    },
                )
                collected
            }
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                "Could not read diagnostics: ${t.message}",
                "Copy All Problems",
            )
            return
        }

        val filtered: List<HighlightInfo> = highlights
            .filter { it.description != null }
            .filter { isRealProblem(it) }
            .filter { settings.isSeverityEnabled(it.severity.name) }
            .let { list ->
                if (settings.state.sortBySeverityFirst) {
                    list.sortedWith(
                        compareByDescending<HighlightInfo> { it.severity.myVal }
                            .thenBy { it.startOffset },
                    )
                } else {
                    list.sortedBy { it.startOffset }
                }
            }

        if (filtered.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No problems found in $fileName (after applying severity filters).",
                "Copy All Problems",
            )
            return
        }

        val includeColumn = settings.state.includeColumn
        val includeSeverityTag = settings.state.includeSeverityTag

        val output = buildString {
            for (info in filtered) {
                val offset = info.startOffset
                val line = document.getLineNumber(offset) + 1
                append(fileName).append(':').append(line)
                if (includeColumn) {
                    val col = (offset - document.getLineStartOffset(line - 1)) + 1
                    append(':').append(col)
                }
                if (includeSeverityTag) {
                    append(" [").append(info.severity.name).append(']')
                }
                append(' ').append(info.description).append('\n')
            }
        }

        CopyPasteManager.getInstance().setContents(StringSelection(output))
        Messages.showInfoMessage(
            project,
            "Copied ${filtered.size} problem(s) from $fileName to clipboard.",
            "Copy All Problems",
        )
    }

    override fun update(e: AnActionEvent) {
        val hasEditor = e.getData(CommonDataKeys.EDITOR) != null
        val hasFile = e.getData(CommonDataKeys.PSI_FILE) != null
        e.presentation.isEnabledAndVisible = hasEditor && hasFile
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    // Drop pure visual annotations (symbol coloring below INFORMATION, URL/identifier markers at INFORMATION) that the Problems tool window also hides.
    private fun isRealProblem(info: HighlightInfo): Boolean {
        val sev = info.severity.myVal
        if (sev < HighlightSeverity.INFORMATION.myVal) return false
        if (sev >= HighlightSeverity.WEAK_WARNING.myVal) return true
        return (info.inspectionToolId != null) || (info.problemGroup != null)
    }
}
