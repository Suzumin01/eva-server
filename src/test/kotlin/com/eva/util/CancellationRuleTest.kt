package com.eva.util

import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancellationRuleTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private fun now() = ZonedDateTime.now(zone)

    @Test
    fun `can cancel appointment 25 hours before slot`() {
        val slot = now().plusHours(25)
        assertTrue(canCancelAppointment(slot, now()))
    }

    @Test
    fun `can cancel appointment 48 hours before slot`() {
        val slot = now().plusHours(48)
        assertTrue(canCancelAppointment(slot, now()))
    }

    @Test
    fun `cannot cancel appointment 12 hours before slot`() {
        val slot = now().plusHours(12)
        assertFalse(canCancelAppointment(slot, now()))
    }

    @Test
    fun `cannot cancel appointment 1 hour before slot`() {
        val slot = now().plusHours(1)
        assertFalse(canCancelAppointment(slot, now()))
    }

    @Test
    fun `cannot cancel appointment in the past`() {
        val slot = now().minusHours(2)
        assertFalse(canCancelAppointment(slot, now()))
    }

    @Test
    fun `can cancel appointment exactly 24 hours before slot`() {
        // Duration.between().toHours() усекает дробную часть вниз,
        // поэтому добавляем 1 минуту запаса чтобы надёжно оказаться >= 24
        val slot = now().plusHours(24).plusMinutes(1)
        assertTrue(canCancelAppointment(slot, now()))
    }

    @Test
    fun `cannot cancel appointment 23 hours 59 minutes before slot`() {
        val slot = now().plusMinutes(23 * 60 + 59)
        assertFalse(canCancelAppointment(slot, now()))
    }
}
