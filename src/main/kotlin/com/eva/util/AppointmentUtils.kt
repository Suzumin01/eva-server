package com.eva.util

import java.time.Duration
import java.time.ZonedDateTime

/**
 * Возвращает true, если запись можно отменить:
 * до начала приёма остаётся строго больше 24 часов.
 */
fun canCancelAppointment(slotDateTime: ZonedDateTime, now: ZonedDateTime): Boolean =
    Duration.between(now, slotDateTime).toHours() >= 24
