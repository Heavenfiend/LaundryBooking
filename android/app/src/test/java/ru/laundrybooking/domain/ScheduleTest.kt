package ru.laundrybooking.domain

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val now = today.atTime(8, 30)

    @Test
    fun nonHourlyBookingOccupiesEveryOverlappingSlotAcrossMidnight() {
        val end = today.atTime(1, 10)
        val booking = booking(1, today.minusDays(1).atTime(23, 40), end)
        val schedule = schedule(booking)
        val previousDay = schedule.slots(today.minusDays(1))
        val currentDay = schedule.slots(today)

        assertEquals(listOf(booking), previousDay[23].bookings)
        assertEquals(listOf(booking), currentDay[0].bookings)
        assertEquals(listOf(booking), currentDay[1].bookings)
        assertTrue(currentDay[2].bookings.isEmpty())
    }

    @Test
    fun adjacentHoursStayFreeAndCurrentHourCanBeBooked() {
        val booking = booking(1, today.atTime(9, 0), today.atTime(10, 0))
        val slots = schedule(booking).slots(today)

        assertFalse(slots[7].canBook)
        assertTrue(slots[8].canBook)
        assertFalse(slots[9].canBook)
        assertTrue(slots[10].canBook)
    }

    @Test
    fun myBookingsIncludeOnlyOwnOngoingAndFutureBookings() {
        val finished = booking(1, now.minusHours(1), now, mine = true)
        val ongoing = booking(2, now.minusMinutes(30), now.plusMinutes(30), mine = true)
        val someoneElses = booking(3, now.plusHours(1), now.plusHours(2))
        val future = booking(4, now.plusDays(1), now.plusDays(1).plusHours(1), mine = true)

        assertEquals(listOf(ongoing, future), schedule(future, finished, ongoing, someoneElses).myBookings())
        assertFalse(someoneElses.canCancel(now))
    }

    private fun schedule(vararg bookings: Booking) =
        Schedule(today.minusDays(7), today.plusDays(21), now, bookings.toList())

    private fun booking(id: Int, start: LocalDateTime, end: LocalDateTime, mine: Boolean = false) =
        Booking(id, "Участник", start, end, note = "", mine = mine)
}
