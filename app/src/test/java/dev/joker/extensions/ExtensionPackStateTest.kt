package dev.joker.extensions

import dev.joker.extensions.ExtensionPackState.Installed
import dev.joker.extensions.ExtensionPackState.NotInstalled
import dev.joker.extensions.ExtensionPackState.UpdateAvailable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExtensionPackStateTest {

    private val manifest = { version: String ->
        PackManifest("script-deps", version, "a".repeat(64), 0L)
    }

    private val entry = { version: String ->
        PackIndexEntry("script-deps", version, "script-deps-$version.dex", "a".repeat(64))
    }

    @Test
    fun `no manifest means not installed`() {
        assertEquals(NotInstalled, classifyPackState(null, entry("6b3b3087a5e2")))
    }

    @Test
    fun `unknown latest means installed`() {
        assertEquals(Installed("0123456789ab"), classifyPackState(manifest("0123456789ab"), null))
    }

    @Test
    fun `matching latest means installed`() {
        assertEquals(
            Installed("6b3b3087a5e2"),
            classifyPackState(manifest("6b3b3087a5e2"), entry("6b3b3087a5e2")),
        )
    }

    @Test
    fun `differing latest means update available`() {
        assertEquals(
            UpdateAvailable("0123456789ab", "6b3b3087a5e2"),
            classifyPackState(manifest("0123456789ab"), entry("6b3b3087a5e2")),
        )
    }

    @Test
    fun `legacy date-prefixed version is not an update when content matches`() {
        assertEquals(
            Installed("20260818-6b3b3087a5e2"),
            classifyPackState(manifest("20260818-6b3b3087a5e2"), entry("6b3b3087a5e2")),
        )
    }
}
