package ru.laundrybooking.domain

import java.time.LocalDate
import java.time.LocalDateTime

internal interface LaundryService {
    suspend fun login(username: String, password: String): Session
    suspend fun register(username: String, name: String, password: String): Session
    suspend fun account(token: String): Account
    suspend fun laundries(token: String): List<Laundry>
    suspend fun laundry(token: String, code: String): Laundry
    suspend fun createLaundry(token: String, draft: LaundryDraft): Laundry
    suspend fun join(token: String, code: String): Laundry
    suspend fun requests(token: String, code: String): List<JoinRequest>
    suspend fun approve(token: String, code: String, userId: Int)
    suspend fun schedule(token: String, code: String): Schedule
    suspend fun book(token: String, code: String, start: LocalDateTime, note: String): Booking
    suspend fun cancelBooking(token: String, code: String, bookingId: Int)
    suspend fun exportSchedule(token: String, code: String): ByteArray
}

internal class Session(val token: String, val account: Account)

internal data class Account(val displayName: String)

internal enum class Role { OWNER, ADMIN, MEMBER }

internal enum class Membership { ACTIVE, PENDING, NONE }

internal data class Laundry(
    val code: String,
    val name: String,
    val description: String,
    val capacity: Int,
    val memberCount: Int,
    val membership: Membership,
    val role: Role?,
) {
    val canApprove: Boolean
        get() = membership == Membership.ACTIVE && (role == Role.OWNER || role == Role.ADMIN)
}

internal data class LaundryDraft(val name: String, val description: String, val capacity: Int)

internal data class JoinRequest(val userId: Int, val username: String, val displayName: String)

internal data class Booking(
    val id: Int,
    val name: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val note: String,
    val mine: Boolean,
) {
    fun canCancel(now: LocalDateTime): Boolean = mine && end > now
}

internal data class Schedule(
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val now: LocalDateTime,
    val bookings: List<Booking>,
) {
    fun slots(day: LocalDate): List<HourlySlot> {
        val dayStart = day.atStartOfDay()
        val dayEnd = dayStart.plusDays(1)
        val bookingsOnDay = bookings.filter { it.start < dayEnd && it.end > dayStart }

        return List(24) { hour ->
            val start = dayStart.plusHours(hour.toLong())
            val end = start.plusHours(1)
            val overlapping = bookingsOnDay.filter { it.start < end && it.end > start }
            HourlySlot(start, overlapping, canBook = overlapping.isEmpty() && end > now)
        }
    }

    fun myBookings(): List<Booking> = bookings.filter { it.canCancel(now) }.sortedBy { it.start }
}

internal data class HourlySlot(
    val start: LocalDateTime,
    val bookings: List<Booking>,
    val canBook: Boolean,
) {
    val end: LocalDateTime get() = start.plusHours(1)
}

internal class ApiHttpError(val status: Int, message: String) : Exception(message)
internal class ApiUnavailable(cause: Throwable) : Exception(cause)
internal class InvalidApiResponse(cause: Throwable? = null) : Exception(cause)
