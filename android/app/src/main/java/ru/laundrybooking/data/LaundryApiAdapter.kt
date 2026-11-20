package ru.laundrybooking.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.laundrybooking.domain.Account
import ru.laundrybooking.domain.Booking
import ru.laundrybooking.domain.JoinRequest
import ru.laundrybooking.domain.Laundry
import ru.laundrybooking.domain.LaundryDraft
import ru.laundrybooking.domain.LaundryService
import ru.laundrybooking.domain.Membership
import ru.laundrybooking.domain.Role
import ru.laundrybooking.domain.Schedule
import ru.laundrybooking.domain.Session

internal class LaundryApiAdapter(private val http: FastApiTransport) : LaundryService {
    override suspend fun login(username: String, password: String): Session =
        post<AuthResponse>(listOf("auth", "login")) { encodeToString(LoginBody(username, password)) }
            .toSession()

    override suspend fun register(username: String, name: String, password: String): Session =
        post<AuthResponse>(listOf("auth", "register")) {
            encodeToString(RegisterBody(username, name, password))
        }.toSession()

    override suspend fun account(token: String): Account =
        http.json<UserResponse>(listOf("auth", "me"), token).toAccount()

    override suspend fun laundries(token: String): List<Laundry> =
        http.json<List<LaundryResponse>>(listOf("laundries"), token).map { it.toLaundry() }

    override suspend fun laundry(token: String, code: String): Laundry =
        http.json<LaundryResponse>(laundryPath(code), token).toLaundry()

    override suspend fun createLaundry(token: String, draft: LaundryDraft): Laundry =
        post<LaundryResponse>(listOf("laundries"), token) {
            encodeToString(CreateLaundryBody(draft.name, draft.description, draft.capacity))
        }.toLaundry()

    override suspend fun join(token: String, code: String): Laundry =
        http.json<LaundryResponse>(laundryPath(code, "join"), token, "POST").toLaundry()

    override suspend fun requests(token: String, code: String): List<JoinRequest> =
        http.json<List<MemberResponse>>(laundryPath(code, "members"), token)
            .filter { it.status == "pending" }
            .map { JoinRequest(it.userId, it.username, it.displayName) }

    override suspend fun approve(token: String, code: String, userId: Int) {
        http.request(laundryPath(code, "members", userId.toString(), "approve"), token, "POST") { }
    }

    override suspend fun schedule(token: String, code: String): Schedule =
        http.json<ScheduleResponse>(laundryPath(code, "bookings"), token).toSchedule()

    override suspend fun book(token: String, code: String, start: LocalDateTime, note: String): Booking {
        // FastAPI передаёт местное время прачечной без смещения часового пояса.
        val format = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val body = BookingBody(start.format(format), start.plusHours(1).format(format), note)
        return post<BookingResponse>(laundryPath(code, "bookings"), token) {
            encodeToString(body)
        }.toBooking()
    }

    override suspend fun cancelBooking(token: String, code: String, bookingId: Int) {
        http.request(laundryPath(code, "bookings", bookingId.toString()), token, "DELETE") { }
    }

    override suspend fun exportSchedule(token: String, code: String): ByteArray =
        http.request(laundryPath(code, "export"), token) { it.bytes() }

    private fun laundryPath(code: String, vararg suffix: String) = listOf("laundries", code) + suffix

    private suspend inline fun <reified Response> post(
        path: List<String>,
        token: String? = null,
        encode: Json.() -> String,
    ): Response = http.json(path, token, method = "POST", payload = http.format.encode())
}

@Serializable
private class LoginBody(val username: String, val password: String)

@Serializable
private class RegisterBody(
    val username: String,
    @SerialName("display_name") val displayName: String,
    val password: String,
)

@Serializable
private class CreateLaundryBody(val name: String, val description: String, val capacity: Int)

@Serializable
private class BookingBody(val start: String, val end: String, val note: String)

@Serializable
private class AuthResponse(val token: String, val user: UserResponse) {
    fun toSession() = Session(token, user.toAccount())
}

@Serializable
private class UserResponse(@SerialName("display_name") val displayName: String) {
    fun toAccount() = Account(displayName)
}

@Serializable
private class LaundryResponse(
    val code: String,
    val name: String,
    val description: String,
    val capacity: Int,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("my_role") val myRole: String?,
    @SerialName("my_status") val myStatus: String?,
) {
    fun toLaundry() = Laundry(
        code, name, description, capacity, memberCount,
        membership = when (myStatus) {
            "active" -> Membership.ACTIVE
            "pending" -> Membership.PENDING
            else -> Membership.NONE
        },
        role = when (myRole) {
            "owner" -> Role.OWNER
            "admin" -> Role.ADMIN
            "member" -> Role.MEMBER
            else -> null
        },
    )
}

@Serializable
private class MemberResponse(
    @SerialName("user_id") val userId: Int,
    val username: String,
    @SerialName("display_name") val displayName: String,
    val status: String,
)

@Serializable
private class BookingResponse(
    val id: Int,
    val name: String,
    val start: String,
    val end: String,
    val note: String,
    val mine: Boolean,
) {
    fun toBooking() = Booking(id, name, LocalDateTime.parse(start), LocalDateTime.parse(end), note, mine)
}

@Serializable
private class ScheduleResponse(
    @SerialName("date_from") val dateFrom: String,
    @SerialName("date_to") val dateTo: String,
    val now: String,
    val bookings: List<BookingResponse>,
) {
    fun toSchedule() = Schedule(
        LocalDate.parse(dateFrom),
        LocalDate.parse(dateTo),
        LocalDateTime.parse(now),
        bookings.map { it.toBooking() },
    )
}
