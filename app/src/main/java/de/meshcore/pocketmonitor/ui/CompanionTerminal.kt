package de.meshcore.pocketmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.meshcore.pocketmonitor.ble.ManagementStatus
import de.meshcore.pocketmonitor.protocol.CompanionManagement

private const val CLI_HISTORY_LIMIT = 100
private val terminalBackground = Color(0xFF101615)
private val terminalInputBackground = Color(0xFF19221F)
private val terminalText = Color(0xFFE2EDE6)
private val terminalMuted = Color(0xFFA7BBB0)
private val terminalPrompt = Color(0xFF9CE3B8)
private val terminalError = Color(0xFFFFB4A9)
private val terminalDivider = Color(0xFF35463D)

internal data class CliEntry(
    val id: Int,
    val command: String,
    val result: Result<String?>? = null,
)

internal class CliTerminalState(val session: String) {
    private var history = emptyList<String>()
    private var historyPosition by mutableStateOf(0)
    private var draft = TextFieldValue()
    private var nextEntryId = 0

    var input by mutableStateOf(TextFieldValue())
    var entries by mutableStateOf(emptyList<CliEntry>())
        private set

    val waiting: Boolean get() = entries.lastOrNull()?.let { it.result == null } == true
    val hasPrevious: Boolean get() = historyPosition > 0
    val hasNext: Boolean get() = historyPosition < history.size

    fun recall(offset: Int) {
        val position = historyPosition + offset
        if (position !in 0..history.size) return
        if (historyPosition == history.size) draft = input
        historyPosition = position
        input = history.getOrNull(position)?.let { TextFieldValue(it, TextRange(it.length)) } ?: draft
    }

    fun clearOutput() {
        if (!waiting) entries = emptyList()
    }

    fun run(onRun: (String, (Result<String?>) -> Unit) -> Unit) {
        if (waiting) return
        val command = input.text.trim()
        val entry = CliEntry(nextEntryId++, command)
        history = (history + command).takeLast(CLI_HISTORY_LIMIT)
        historyPosition = history.size
        input = TextFieldValue()
        draft = input
        entries = (entries + entry).takeLast(CLI_HISTORY_LIMIT)
        onRun(command) { result ->
            entries = entries.dropLast(1) + entry.copy(result = result)
        }
    }
}

@Composable
internal fun CompanionTerminal(
    status: ManagementStatus,
    terminal: CliTerminalState,
    onRun: (String, (Result<String?>) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val limit = status.cliTextLimit
    val command = terminal.input.text
    fun validInput() = limit != null && CompanionManagement.cliCommand(terminal.input.text, "00|", limit) != null
    val ready = status.available && !status.busy && !terminal.waiting
    val submit = { if (ready && validInput()) terminal.run(onRun) }
    val listState = rememberLazyListState()
    val newestEntry = terminal.entries.lastOrNull()
    val terminalStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val buttonColors = ButtonDefaults.textButtonColors(
        contentColor = terminalPrompt,
        disabledContentColor = terminalMuted.copy(alpha = 0.55f),
    )
    LaunchedEffect(newestEntry) {
        if (newestEntry != null) listState.scrollToItem(0)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(terminalBackground).safeDrawingPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Companion-CLI", color = terminalText, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = terminal::clearOutput, enabled = newestEntry != null && !terminal.waiting,
                    colors = buttonColors) { Text("Leeren") }
                TextButton(onClick = onDismiss, colors = buttonColors) { Text("Schließen") }
            }
            HorizontalDivider(color = terminalDivider)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
            ) {
                items(terminal.entries.asReversed(), key = { it.id }) { entry ->
                    val result = entry.result
                    val failure = result?.exceptionOrNull()
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("> ${entry.command}", color = terminalPrompt, style = terminalStyle)
                            val output = when {
                                result == null -> "Warte auf die CLI-Antwort …"
                                failure != null -> failure.message ?: "Kommando fehlgeschlagen."
                                else -> result.getOrNull()?.ifEmpty { "Leere Antwort" } ?: "Leere Antwort"
                            }
                            Text(
                                output,
                                color = when {
                                    result == null -> terminalMuted
                                    failure != null -> terminalError
                                    else -> terminalText
                                },
                                style = terminalStyle,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = terminalDivider)
            val statusText = when {
                !status.available -> "Companion erneut verbinden."
                limit == null -> "Benötigt Companion-Protokollversion 14 oder höher."
                status.busy && !terminal.waiting -> "Ein Companion-Vorgang läuft …"
                command.isNotBlank() && !validInput() -> "Ungültiges Kommando oder BLE-Längenlimit überschritten."
                else -> null
            }
            statusText?.let {
                Text(it, Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                    color = terminalMuted, style = MaterialTheme.typography.bodySmall)
            }
            if (limit != null) {
                Row(
                    Modifier.fillMaxWidth().background(terminalInputBackground).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("> ", color = terminalPrompt, style = terminalStyle)
                    BasicTextField(
                        value = terminal.input,
                        onValueChange = { terminal.input = it },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Kommando" }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> terminal.recall(-1)
                                    Key.DirectionDown -> terminal.recall(1)
                                    Key.Enter, Key.NumPadEnter -> submit()
                                    else -> return@onPreviewKeyEvent false
                                }
                                true
                            },
                        singleLine = true,
                        textStyle = terminalStyle.copy(color = terminalText),
                        cursorBrush = SolidColor(terminalPrompt),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        decorationBox = { field ->
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                if (command.isEmpty()) Text("Befehl eingeben", color = terminalMuted, style = terminalStyle,
                                    maxLines = 1)
                                field()
                            }
                        },
                    )
                    IconButton(onClick = { terminal.recall(-1) }, enabled = terminal.hasPrevious,
                        modifier = Modifier.semantics { contentDescription = "Älterer Befehl" }) {
                        Text("↑", color = if (terminal.hasPrevious) terminalPrompt else terminalMuted.copy(alpha = 0.55f))
                    }
                    IconButton(onClick = { terminal.recall(1) }, enabled = terminal.hasNext,
                        modifier = Modifier.semantics { contentDescription = "Neuerer Befehl" }) {
                        Text("↓", color = if (terminal.hasNext) terminalPrompt else terminalMuted.copy(alpha = 0.55f))
                    }
                    TextButton(onClick = submit, enabled = ready && validInput(), colors = buttonColors) { Text("Senden") }
                }
            }
        }
    }
}
