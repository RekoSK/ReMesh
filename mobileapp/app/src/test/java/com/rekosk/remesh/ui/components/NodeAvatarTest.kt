package com.rekosk.remesh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAvatarTest {
    @Test
    fun `glyph is the upper-cased first letter for a plain name`() {
        assertEquals("A", avatarGlyph("alice"))
        assertEquals("B", avatarGlyph("  bob"))
        assertEquals("Ř", avatarGlyph("řadič"))
    }

    @Test
    fun `an emoji anywhere in the name wins over letters`() {
        assertEquals("🚀", avatarGlyph("Rocket 🚀 node"))
        assertEquals("🐸", avatarGlyph("frog 🐸"))
        // Leading emoji is used directly.
        assertEquals("⭐", avatarGlyph("⭐ Star"))
    }

    @Test
    fun `flag emoji is kept whole`() {
        assertEquals("🇨🇿", avatarGlyph("CZ repeater 🇨🇿"))
    }

    @Test
    fun `blank name degrades to a placeholder`() {
        assertEquals("?", avatarGlyph("   "))
    }

    @Test
    fun `colour is stable for the same seed across calls`() {
        val a = avatarColor("pubkey-abc", dark = true)
        val b = avatarColor("pubkey-abc", dark = true)
        assertEquals(a, b)
    }

    @Test
    fun `light and dark seeds pick a matched but distinct shade`() {
        // Same index, different palette -> different colour, never transparent.
        val light = avatarColor("node-42", dark = false)
        val dark = avatarColor("node-42", dark = true)
        assertNotEquals(light, dark)
        assertTrue(light.alpha == 1f && dark.alpha == 1f)
    }
}
