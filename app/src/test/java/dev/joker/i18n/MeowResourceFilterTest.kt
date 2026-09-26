package dev.joker.i18n

import dev.joker.R
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeowResourceFilterTest {
    @Test
    fun acceptsOnlyJokerResourcePackageIds() {
        assertTrue(MeowResourceFilter.isJokerResource(R.string.settings_title))
        assertFalse(MeowResourceFilter.isJokerResource(0x7f010001))
        assertFalse(MeowResourceFilter.isJokerResource(android.R.string.ok))
    }
}
