package dev.joker.dextest

import dev.joker.dexkit.abc.IResolveDex
import dev.joker.features.core.DexResolutionTestRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DexResolutionRegistryTest {
    @Test
    fun registryContainsOnlyLazyResolverMetadata() {
        val entries = DexResolutionTestRegistry.ITEMS
        assertTrue(entries.isNotEmpty())
        assertEquals(entries.size, entries.map { it.className }.distinct().size)
        assertTrue(entries.any { it.className.endsWith("DisableTypingStatusUploading") })
        assertFalse(entries.any { it.className.endsWith("MomentsEditorBackOptimization") })

        entries.forEach { entry ->
            val type = Class.forName(entry.className, false, javaClass.classLoader)
            assertTrue(IResolveDex::class.java.isAssignableFrom(type))
        }
    }

    @Test
    fun pathBackedFeaturesCanInitializeOnDesktop() {
        listOf(
            "dev.joker.features.items.beautify.Themes",
            "dev.joker.features.items.scripting_java.JavaScriptingHook",
            "dev.joker.features.items.contacts.CustomLocalFriendAvatars",
        ).forEach { className ->
            assertDoesNotThrow {
                Class.forName(className, true, javaClass.classLoader)
            }
        }
    }
}
