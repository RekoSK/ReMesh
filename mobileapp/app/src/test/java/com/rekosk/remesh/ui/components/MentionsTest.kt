package com.rekosk.remesh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionsTest {

    @Test
    fun mentionsName_matchesTokenCaseInsensitively() {
        assertTrue(mentionsName("hey @[Tino] there", "Tino"))
        assertTrue(mentionsName("hey @[Tino] there", "tino"))
        assertTrue(mentionsName("multi @[Ann] @[Bob]", "bob"))
        assertFalse(mentionsName("hey @[Bob]", "Tino"))
        assertFalse(mentionsName("hey Tino", "Tino")) // plain text, not a token
        assertFalse(mentionsName("hey @[Tino]", null))
        assertFalse(mentionsName("hey @[Tino]", "  "))
    }

    @Test
    fun findActiveMention_detectsRawQuery() {
        assertEquals(3 to "bo", findActiveMention("hi @bo", 6))
        assertEquals(0 to "bob", findActiveMention("@bob", 4))
        assertEquals(3 to "", findActiveMention("hi @", 4)) // just typed '@'
    }

    @Test
    fun findActiveMention_ignoresNonBoundaryAndCompletedTokens() {
        assertNull(findActiveMention("a@b", 3)) // '@' not at a word boundary
        assertNull(findActiveMention("hi @bob and", 11)) // a space breaks the query
        assertNull(findActiveMention("hi @[bob]", 9)) // already a completed token
        assertNull(findActiveMention("hi @[bob] ", 10))
        assertNull(findActiveMention("plain text", 5))
    }

    @Test
    fun insertMention_wrapsAndAppendsSpace() {
        val (text, cursor) = insertMention("hi @bo", 3, 6, "Bob")
        assertEquals("hi @[Bob] ", text)
        assertEquals(text.length, cursor)
    }

    @Test
    fun normalize_spaceConfirmWrapsRawMention() {
        val n = normalizeMentionTyping("hi @bob ", 8)
        assertEquals("hi @[bob] ", n.text)
        assertEquals(10, n.cursor)
    }

    @Test
    fun normalize_backspaceRevertUnwrapsBrokenToken() {
        // The closing ']' was just deleted from "hi @[bob]".
        val n = normalizeMentionTyping("hi @[bob", 8)
        assertEquals("hi bob", n.text)
        assertEquals(6, n.cursor)
    }

    @Test
    fun normalize_leavesWellFormedTokenAndPlainTextAlone() {
        val a = normalizeMentionTyping("hi @[bob] ", 10)
        assertEquals("hi @[bob] ", a.text)
        val b = normalizeMentionTyping("hello world", 5)
        assertEquals("hello world", b.text)
        assertEquals(5, b.cursor)
    }

    @Test
    fun normalize_keepsMultiWordTokenIntact() {
        // A name with spaces must survive: the inner space is not the token's end.
        val inserted = "@[Ivan OM2IDN] "
        val n = normalizeMentionTyping(inserted, inserted.length)
        assertEquals(inserted, n.text)

        val mid = "hey @[Ivan OM2IDN] there"
        assertEquals(mid, normalizeMentionTyping(mid, 18).text)
    }

    @Test
    fun normalize_unwrapsBrokenMultiWordToken() {
        // The closing ']' of "@[Ivan OM2IDN]" was deleted.
        val n = normalizeMentionTyping("hey @[Ivan OM2IDN ", 18)
        assertEquals("hey Ivan OM2IDN ", n.text)
    }

    @Test
    fun mentionsName_matchesMultiWordName() {
        assertTrue(mentionsName("yo @[Ivan OM2IDN] hi", "Ivan OM2IDN"))
    }
}
