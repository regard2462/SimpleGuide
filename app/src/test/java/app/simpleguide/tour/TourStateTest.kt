package app.simpleguide.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TourStateTest {
    @Test
    fun verlaufNeuesteZuerstUndBegrenzt() {
        TourState.history.value = emptyList()
        repeat(70) { i -> TourState.remember("Ort $i", listOf("Satz $i."), voiced = i % 2 == 0) }
        val h = TourState.history.value
        assertEquals(60, h.size)
        assertEquals("Ort 69", h.first().title)
        assertFalse(h.first().voiced)
        assertEquals(listOf("Satz 69."), h.first().lines)
    }
}
