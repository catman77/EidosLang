package org.eidolang.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EditorHistoryMemoryTest {
    @Test
    fun aLongGestureKeepsOnlyItsInitialUndoHistoryAndRemainsUndoable() {
        val initial = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph("circle.red.m"))
        var moving = initial
        repeat(5_000) {
            moving = EditorReducer.reduce(moving, EditorCommand.TranslateSelected(1, 0), recordUndo = false)
        }
        assertSame(initial.undo, moving.undo)
        assertEquals(505_000, moving.snapshot.instances.single().transform.cxFp)
        // Exactly the same single commit the UI makes when the finger lifts.
        val committed = moving.copy(undo = initial.undo + listOf(initial.actions))
        val undone = EditorReducer.reduce(committed, EditorCommand.Undo)
        assertEquals(initial.actions, undone.actions)
        assertEquals(moving.actions, EditorReducer.reduce(undone, EditorCommand.Redo).actions)
    }

    @Test
    fun standaloneChangesStillHaveTheirOwnUndoSteps() {
        var draft = EditorReducer.reduce(EditorDraft(), EditorCommand.AddGlyph("square.blue.m"))
        repeat(10) { draft = EditorReducer.reduce(draft, EditorCommand.TranslateSelected(1, 0)) }
        assertEquals(11, draft.undo.size)
        repeat(10) { draft = EditorReducer.reduce(draft, EditorCommand.Undo) }
        assertEquals(500_000, draft.snapshot.instances.single().transform.cxFp)
        assertEquals(1, draft.undo.size)
    }
}
