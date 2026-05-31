package com.moraouf.copyproblems

import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.hint.HintManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtilCore
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
        // Path relative to the project root for the clipboard output — disambiguates files that share
        // a name across directories. '/' keeps it stable across OSes. Files outside the project root
        // (library sources, scratch files) have no relative path; for those fall back to either the
        // full absolute path or the bare name, per the fullPathForExternalFiles setting.
        val displayPath = run {
            val virtualFile = psiFile.virtualFile
            val baseDir = project.guessProjectDir()
            val relativePath = if (virtualFile != null && baseDir != null) {
                VfsUtilCore.getRelativePath(virtualFile, baseDir, '/')
            } else {
                null
            }
            relativePath
                ?: if (settings.state.fullPathForExternalFiles && virtualFile != null) virtualFile.path else fileName
        }

        // Optionally reformat the file first so whitespace/formatting warnings are fixed and
        // therefore don't show up in the copied list. Deleting the offending whitespace removes
        // its highlighter ranges, so the read below already reflects the cleaned-up file.
        if (settings.state.reformatBeforeCopy) {
            try {
                PsiDocumentManager.getInstance(project).commitDocument(document)
                ReformatCodeProcessor(psiFile, false).run()
                PsiDocumentManager.getInstance(project).commitDocument(document)
            } catch (t: Throwable) {
                showError(project, editor, "Could not reformat $fileName: ${t.message}", settings)
                return
            }
        }

        val highlights = try {
            ApplicationManager.getApplication().runReadAction(
                Computable<List<HighlightInfo>> {
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
                },
            )
        } catch (t: Throwable) {
            showError(project, editor, "Could not read diagnostics: ${t.message}", settings)
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
            showInfo(
                project,
                editor,
                "No problems found in $fileName (after applying severity filters).",
                settings,
            )
            return
        }

        val includeColumn = settings.state.includeColumn
        val includeSeverityTag = settings.state.includeSeverityTag

        val output = buildString {
            for (info in filtered) {
                val offset = info.startOffset
                val line = document.getLineNumber(offset) + 1
                append(displayPath).append(':').append(line)
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
        showInfo(
            project,
            editor,
            "Copied ${filtered.size} problem(s) from $fileName to clipboard.",
            settings,
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

    private fun showInfo(project: Project, editor: Editor, message: String, settings: CopyProblemsSettings) {
        when (settings.state.notificationStyle) {
            CopyProblemsSettings.NotificationStyle.MODAL ->
                Messages.showInfoMessage(project, message, TITLE)

            CopyProblemsSettings.NotificationStyle.BALLOON ->
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(message, NotificationType.INFORMATION)
                    .notify(project)

            CopyProblemsSettings.NotificationStyle.EDITOR_HINT ->
                HintManager.getInstance().showInformationHint(editor, message)

            CopyProblemsSettings.NotificationStyle.SILENT -> Unit
        }
    }

    private fun showError(project: Project, editor: Editor, message: String, settings: CopyProblemsSettings) {
        when (settings.state.notificationStyle) {
            CopyProblemsSettings.NotificationStyle.MODAL ->
                Messages.showErrorDialog(project, message, TITLE)

            CopyProblemsSettings.NotificationStyle.BALLOON ->
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(message, NotificationType.ERROR)
                    .notify(project)

            CopyProblemsSettings.NotificationStyle.EDITOR_HINT ->
                HintManager.getInstance().showErrorHint(editor, message)

            CopyProblemsSettings.NotificationStyle.SILENT -> Unit
        }
    }

    private companion object {
        const val TITLE = "Copy All Problems"
        const val NOTIFICATION_GROUP = "Copy All Problems"
    }
}
