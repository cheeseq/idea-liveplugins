import com.intellij.codeInsight.daemon.impl.EditorTrackerListener
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Constraints.FIRST
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors.HIGHLIGHTED_REFERENCE
import com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsActions.ActionText
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import liveplugin.*
import java.util.regex.Matcher
import java.util.regex.Pattern

//SPECIFY YOUR PREFIX HERE
val VAULT_ADDRESS = ""
val VAULT_STRIP_PREFIX = ""

/* Declare shared state and a function to update it */
lateinit var vaultSubstrings: MutableList<IntRange>
lateinit var docText: String
val editorHighlighters: MutableList<RangeHighlighter> = mutableListOf()
val vaultRegex: Pattern = Pattern.compile("vault:.+#.+")

fun refresh(project: Project) {
    if (project.currentFile?.fileType?.defaultExtension != "yml") {
        return
    }

    vaultSubstrings = mutableListOf<IntRange>()
    docText = project.currentDocument?.text!!
    val matcher: Matcher = vaultRegex.matcher(docText)
    matcher.results().forEach {
        vaultSubstrings.add(IntRange(it.start(), it.end() - 1))
        val highlighter = project.currentEditor?.markupModel
            ?.addRangeHighlighter(HIGHLIGHTED_REFERENCE, it.start(), it.end(), 1, EXACT_RANGE)
        //todo this seems to be not efficient, needs a lot of memory
        editorHighlighters.add(highlighter!!)
    }
}

/* Declare dynamic IDEA action */
fun AnAction(@ActionText text: String? = null, performAction: (AnActionEvent) -> Unit, updateState: (AnActionEvent) -> Unit) =
    object: com.intellij.openapi.actionSystem.AnAction(text) {
        override fun actionPerformed(event: AnActionEvent) = performAction(event)
        override fun update(event: AnActionEvent) = updateState(event)
        override fun isDumbAware() = true
    }

val updateActionVisibility: (AnActionEvent) -> Unit = { event ->
    //todo this better be placed in some form of event context
    val offset = event.editor?.caretModel?.primaryCaret?.offset!!
    val pair: IntRange? = vaultSubstrings.find { offset >= it.first && offset <= it.last }

    event.presentation.isEnabledAndVisible = pair != null
}

val execute: (AnActionEvent) -> Unit = { event ->
    val offset = event.editor?.caretModel?.primaryCaret?.offset!!
    val pair: IntRange? = vaultSubstrings.find { offset >= it.first && offset <= it.last }

    var vaultSubstring = docText.substring(pair!!)
        .substringAfter("vault:")
        .substringBefore("#")

    if (vaultSubstring.startsWith(VAULT_STRIP_PREFIX)) {
        vaultSubstring = vaultSubstring.substringAfter(VAULT_STRIP_PREFIX)
    }

    liveplugin.openInBrowser(VAULT_ADDRESS + vaultSubstring)
}

/* Register action */
registerAction("Open In Vault Browser", null, ActionGroupIds.EditorPopupMenu, FIRST,
    AnAction("Open In Vault Browser", execute, updateActionVisibility))


/* Connect to IDE events for every opened project*/
registerProjectOpenListener(pluginDisposable) { openedProject ->
    //showInConsole("connect project " + openedProject.name, project!!)
    val connection = openedProject
        .messageBus
        .connect(pluginDisposable)

    connection.subscribe(EditorTrackerListener.TOPIC, EditorTrackerListener {
 //       showInConsole("editor tracker event in project " + openedProject.name, project!!)
        refresh(openedProject)
    })
    connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
        override fun after(events: MutableList<out VFileEvent>) {
            refresh(openedProject)
        }
    })

}

pluginDisposable.whenDisposed {
   // showInConsole("dispose", project!!)
    editorHighlighters.forEach { it.dispose() }
}
