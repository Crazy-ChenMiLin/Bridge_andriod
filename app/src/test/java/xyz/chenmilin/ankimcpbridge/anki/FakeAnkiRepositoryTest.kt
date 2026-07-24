package xyz.chenmilin.ankimcpbridge.anki

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeAnkiRepositoryTest {

    private lateinit var repo: FakeAnkiRepository

    @Before
    fun setup() {
        repo = FakeAnkiRepository()
    }

    @Test
    fun `isAnkiDroidInstalled returns true by default`() {
        assertTrue(repo.isAnkiDroidInstalled())
    }

    @Test
    fun `hasPermission returns true by default`() {
        assertTrue(repo.hasPermission())
    }

    @Test
    fun `listDecks returns empty when no decks`() = runTest {
        val decks = repo.listDecks()
        assertTrue(decks.isEmpty())
    }

    @Test
    fun `ensureDeck creates new deck`() = runTest {
        val deck = repo.ensureDeck("TestDeck")
        assertEquals("TestDeck", deck.name)
        assertTrue(deck.id > 0)
    }

    @Test
    fun `ensureDeck is idempotent`() = runTest {
        val deck1 = repo.ensureDeck("TestDeck")
        val deck2 = repo.ensureDeck("TestDeck")
        assertEquals(deck1.id, deck2.id)
        assertEquals(deck1.name, deck2.name)
    }

    @Test
    fun `listDecks returns sorted decks`() = runTest {
        repo.ensureDeck("B")
        repo.ensureDeck("A")
        repo.ensureDeck("C")
        val decks = repo.listDecks()
        assertEquals(listOf("A", "B", "C"), decks.map { it.name })
    }

    @Test
    fun `addBasicNote succeeds`() = runTest {
        val result = repo.addBasicNote(
            AddBasicNoteRequest(deck = "Test", front = "Q?", back = "A!", tags = listOf("tag1"))
        )
        assertTrue(result.success)
        assertNotNull(result.noteId)
        assertEquals("Test", result.deck)
    }

    @Test
    fun `addBasicNote throws on blank front`() = runTest {
        try {
            repo.addBasicNote(AddBasicNoteRequest(deck = "Test", front = "  ", back = "A"))
            fail("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("front"))
        }
    }

    @Test
    fun `addBasicNote throws on blank back`() = runTest {
        try {
            repo.addBasicNote(AddBasicNoteRequest(deck = "Test", front = "Q", back = ""))
            fail("Expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("back"))
        }
    }

    @Test
    fun `addBasicNotes batch succeeds`() = runTest {
        val result = repo.addBasicNotes(
            AddBasicNotesRequest(
                deck = "Test",
                notes = listOf(
                    SingleNoteRequest("Q1", "A1"),
                    SingleNoteRequest("Q2", "A2"),
                    SingleNoteRequest("Q3", "A3")
                )
            )
        )
        assertEquals(3, result.requested)
        assertEquals(3, result.submitted)
        assertEquals(3, result.succeeded)
        assertEquals(0, result.failed)
        // 批量路径不暴露 noteId（与真实实现契约一致）
        assertEquals(false, result.noteIdsAvailable)
        assertEquals(0, result.noteIds.size)
    }

    @Test
    fun `addBasicNotes partial failure on blank front`() = runTest {
        val result = repo.addBasicNotes(
            AddBasicNotesRequest(
                deck = "Test",
                notes = listOf(
                    SingleNoteRequest("Q1", "A1"),
                    SingleNoteRequest("", "A2"),
                    SingleNoteRequest("Q3", "A3")
                )
            )
        )
        assertEquals(3, result.requested)
        assertEquals(2, result.submitted)
        assertEquals(2, result.succeeded)
        assertEquals(1, result.failed)
        assertEquals(AnkiErrors.INVALID_FRONT, result.errors.first().code)
    }

    @Test
    fun `throws when not installed`() = runTest {
        repo.setInstalled(false)
        try {
            repo.listDecks()
            fail("Expected exception")
        } catch (e: AnkiDroidNotInstalledException) {
            // expected
        }
    }

    @Test
    fun `throws when permission denied`() = runTest {
        repo.setPermissionGranted(false)
        try {
            repo.ensureDeck("Test")
            fail("Expected exception")
        } catch (e: AnkiPermissionDeniedException) {
            // expected
        }
    }
}
