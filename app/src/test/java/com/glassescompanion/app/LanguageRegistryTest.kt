package com.glassescompanion.app

import com.glassescompanion.app.lang.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageRegistryTest {
    @Test
    fun hasExactly100DistinctLanguages() {
        val all = LanguageRegistry.all
        assertEquals(100, all.size)
        assertEquals(100, all.map { it.code }.toSet().size)
        val codes = all.map { it.code }.toSet()
        assertTrue(codes.containsAll(listOf("en", "hi", "es", "ur", "ta", "zh-CN", "bn")))
    }
}
