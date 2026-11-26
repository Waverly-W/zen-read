package com.zenread.data.ai.prompt

import com.zenread.data.ai.ApiKeyRotator
import com.zenread.data.ai.chat.ChatMessage
import com.zenread.data.ai.chat.ChatRole
import com.zenread.data.ai.history.Citation
import com.zenread.data.ai.history.MessageStatus
import com.zenread.data.ai.history.StoredMessage
import com.zenread.data.ai.history.toChatHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {
    private val book = BookInfo("Moby-Dick", "Herman Melville")

    @Test
    fun `system prompt names the book, author and reply language`() {
        val prompt = PromptAssembler.systemPrompt(book, "Chinese (China)")
        assertTrue(prompt.contains("\"Moby-Dick\" by Herman Melville"))
        assertTrue(prompt.contains("Reply in Chinese (China)"))
        assertTrue(prompt.contains("<book_context>"))
        assertTrue(prompt.contains("[n]"))
        assertTrue(PromptAssembler.systemPrompt(BookInfo("", ""), "English").contains("an untitled book"))
    }

    @Test
    fun `a request without context is sent as is`() {
        assertEquals("What is a whale?", PromptAssembler.userTurn("What is a whale?", ReadingContext()))
    }

    @Test
    fun `context is wrapped in tags before the request`() {
        val turn = PromptAssembler.userTurn(
            "Explain this",
            ReadingContext(
                chapterTitle = "Loomings",
                chapterText = "Call me Ishmael.",
                selection = " Ishmael ",
                passages = listOf(Citation(1, "Chapter 1", "cfi-1", "Some years ago"), Citation(2, "", "cfi-2", "never mind how long")),
            ),
        )
        val expected = """
            <book_context>
            Current chapter: Loomings
            <quoted_passage>
            Ishmael
            </quoted_passage>
            <chapter_text>
            Call me Ishmael.
            </chapter_text>
            <retrieved_passages note="Excerpts retrieved from the book's index for this question; not complete chapters.">
            [1] (Chapter 1) Some years ago

            [2] never mind how long

            </retrieved_passages>
            </book_context>

            Explain this
        """.trimIndent()
        assertEquals(expected, turn)
    }

    @Test
    fun `long chapters are truncated with a note`() {
        val turn = PromptAssembler.userTurn("q", ReadingContext(chapterText = "a".repeat(PromptAssembler.CHAPTER_BUDGET + 500)))
        assertTrue(turn.contains("note=\"truncated"))
        assertFalse(turn.contains("a".repeat(PromptAssembler.CHAPTER_BUDGET + 1)))
    }

    @Test
    fun `outline passages are labelled as an outline`() {
        val turn = PromptAssembler.userTurn("sum up", ReadingContext(passages = listOf(Citation(1, "I", "c", "t")), passagesAreOutline = true))
        assertTrue(turn.contains("This is an outline, not the complete book."))
    }

    @Test
    fun `messages keep the system prompt first and trim old history`() {
        val history = (1..40).map { if (it % 2 == 1) ChatMessage.user("u$it") else ChatMessage.assistant("a$it") }
        val messages = PromptAssembler.messages(book, "English", history, "now", ReadingContext())
        assertEquals(ChatRole.SYSTEM, messages.first().role)
        assertEquals(ChatMessage.user("now"), messages.last())
        assertEquals(1 + PromptAssembler.HISTORY_TURNS * 2 + 1, messages.size)
        assertEquals("u17", messages[1].content)
    }

    @Test
    fun `citations become links only when they exist`() {
        val citations = listOf(Citation(1, "c", "cfi", "t"), Citation(3, "c", "cfi3", "t"))
        val linked = PromptAssembler.linkCitations("A [1], B [2] and C [3][1]. Link [1](http://x).", citations)
        assertEquals("A [1](cite:1), B [2] and C [3](cite:3)[1](cite:1). Link [1](http://x).", linked)
        assertEquals("no change [1]", PromptAssembler.linkCitations("no change [1]", emptyList()))
    }

    @Test
    fun `cited numbers are distinct in first-use order`() {
        assertEquals(listOf(3, 1), PromptAssembler.citedNumbers("x [3] y [1] z [3]"))
    }

    @Test
    fun `history replays quotes and skips empty answers`() {
        fun stored(id: Long, role: ChatRole, content: String, quote: String? = null) =
            StoredMessage(id, role, content, quote, null, null, emptyList(), MessageStatus.DONE, null)
        val history = listOf(
            stored(1, ChatRole.USER, "What does this mean?", quote = "Call me Ishmael."),
            stored(2, ChatRole.ASSISTANT, "It is the narrator."),
            stored(3, ChatRole.USER, "And then?"),
            stored(4, ChatRole.ASSISTANT, ""),
        ).toChatHistory()
        assertEquals(3, history.size)
        assertEquals("Quoted passage:\n\"\"\"\nCall me Ishmael.\n\"\"\"\n\nWhat does this mean?", history[0].content)
    }
}

class ApiKeyRotatorTest {
    @Test
    fun `parses keys from lines and commas`() {
        assertEquals(listOf("a", "b", "c"), ApiKeyRotator.parseKeys(" a ,b\n\nc,a "))
        assertEquals(emptyList<String>(), ApiKeyRotator.parseKeys(null))
    }

    @Test
    fun `rotates the starting key per provider`() {
        val rotator = ApiKeyRotator()
        assertEquals(listOf("a", "b", "c"), rotator.order("p", "a,b,c"))
        assertEquals(listOf("b", "c", "a"), rotator.order("p", "a,b,c"))
        assertEquals(listOf("c", "a", "b"), rotator.order("p", "a,b,c"))
        assertEquals(listOf("a", "b"), rotator.order("other", "a,b"))
    }

    @Test
    fun `a missing key still yields one keyless attempt`() {
        assertEquals(listOf(""), ApiKeyRotator().order("p", null))
        assertEquals(listOf("only"), ApiKeyRotator().order("p", "only"))
    }
}
