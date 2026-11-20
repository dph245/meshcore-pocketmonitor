package de.meshcore.pocketmonitor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import de.meshcore.pocketmonitor.ui.CliTerminalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliTerminalStateTest {
    @Test
    fun historyRestoresDraftAndSurvivesClearingOutput() {
        val terminal = CliTerminalState("session")
        terminal.input = TextFieldValue(" get name ")
        terminal.run { command, complete ->
            assertEquals("get name", command)
            complete(Result.success("PocketMonitor"))
        }
        terminal.input = TextFieldValue("get radio")
        terminal.run { _, complete -> complete(Result.success("Radio settings")) }
        assertEquals(listOf("get name", "get radio"), terminal.entries.map { it.command })

        terminal.clearOutput()
        assertTrue(terminal.entries.isEmpty())
        val draft = TextFieldValue("get time", TextRange(3))
        terminal.input = draft
        terminal.recall(-1)
        assertEquals(TextFieldValue("get radio", TextRange(9)), terminal.input)
        terminal.recall(-1)
        assertEquals("get name", terminal.input.text)
        assertFalse(terminal.hasPrevious)
        terminal.recall(1)
        terminal.input = TextFieldValue("get radio edited")
        terminal.recall(1)
        assertEquals(draft, terminal.input)
        assertFalse(terminal.hasNext)
    }

    @Test
    fun pendingCommandKeepsItsPlaceWhenAnotherCommandIsAttempted() {
        val terminal = CliTerminalState("session")
        lateinit var finish: (Result<String?>) -> Unit
        terminal.input = TextFieldValue("get name")
        terminal.run { _, complete -> finish = complete }
        terminal.input = TextFieldValue("get radio")
        terminal.run { _, _ -> error("A command is already running") }
        terminal.clearOutput()
        assertTrue(terminal.waiting)
        assertEquals("get radio", terminal.input.text)
        val error = IllegalStateException("Zeitüberschreitung")
        finish(Result.failure(error))
        assertFalse(terminal.waiting)
        assertEquals(error, terminal.entries.single().result?.exceptionOrNull())
    }
}
