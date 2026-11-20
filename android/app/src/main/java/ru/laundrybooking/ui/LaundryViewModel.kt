package ru.laundrybooking.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.laundrybooking.data.SessionStore
import ru.laundrybooking.domain.Account
import ru.laundrybooking.domain.ApiHttpError
import ru.laundrybooking.domain.ApiUnavailable
import ru.laundrybooking.domain.Booking
import ru.laundrybooking.domain.InvalidApiResponse
import ru.laundrybooking.domain.JoinRequest
import ru.laundrybooking.domain.Laundry
import ru.laundrybooking.domain.LaundryDraft
import ru.laundrybooking.domain.LaundryService
import ru.laundrybooking.domain.Membership
import ru.laundrybooking.domain.Schedule
import ru.laundrybooking.domain.Session

internal sealed interface AuthState {
    data object Restoring : AuthState
    data object RetryRestore : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val account: Account) : AuthState
}

internal sealed interface Page {
    data object Home : Page
    data object Create : Page
    data object Find : Page
    data class Details(
        val laundry: Laundry,
        val schedule: Schedule? = null,
        val requests: List<JoinRequest>? = null,
    ) : Page
}

internal data class UiState(
    val auth: AuthState = AuthState.Restoring,
    val page: Page = Page.Home,
    val laundries: List<Laundry>? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val exportFilename: String? = null,
)

internal class LaundryViewModel(
    private val service: LaundryService,
    private val sessions: SessionStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(UiState())
    val state = mutableState.asStateFlow()

    private var token: String? = null
    private var excelFile: ByteArray? = null

    init {
        restoreSession()
    }

    fun restoreSession() = request {
        val saved = sessions.load()
        if (saved == null) {
            mutableState.update { it.copy(auth = AuthState.SignedOut) }
        } else {
            token = saved
            mutableState.update { it.copy(auth = AuthState.RetryRestore) }
            showHome(saved, service.account(saved))
        }
    }

    fun login(username: String, password: String) = request {
        if (username.isBlank() || password.isEmpty()) {
            showError("Введите логин и пароль.")
            return@request
        }
        startSession(service.login(username.trim(), password))
    }

    fun register(username: String, name: String, password: String) = request {
        val login = username.trim()
        val displayName = name.trim()
        val error = when {
            !USERNAME.matches(login) -> "Логин: от 3 до 32 латинских букв, цифр или символов . _ -"
            displayName.isEmpty() || displayName.characters() > 64 -> "Введите имя длиной от 1 до 64 символов."
            password.characters() !in 6..128 -> "Пароль должен содержать от 6 до 128 символов."
            else -> null
        }
        if (error != null) {
            showError(error)
            return@request
        }
        startSession(service.register(login, displayName, password))
    }

    fun logout() = request { endSession() }

    fun openCreate() = navigate(Page.Create)
    fun openFind() = navigate(Page.Find)

    fun backToHome() = authenticated { token ->
        mutableState.update { it.copy(page = Page.Home) }
        loadLaundries(token)
    }

    fun refresh() = authenticated { token ->
        when (val page = state.value.page) {
            Page.Home -> loadLaundries(token)
            is Page.Details -> {
                val updated = service.laundry(token, page.laundry.code)
                setLaundry(updated)
                loadDetailsContent(token, updated)
            }
            else -> Unit
        }
    }

    fun create(name: String, description: String, capacity: String) = authenticated { token ->
        val title = name.trim()
        val info = description.trim()
        val limit = capacity.toIntOrNull()
        when {
            title.isEmpty() || title.characters() > 100 -> showError("Введите название длиной от 1 до 100 символов.")
            info.characters() > 1000 -> showError("Максимальная длина описания — 1000 символов.")
            limit == null || limit !in 1..1000 -> showError("Лимит участников должен быть от 1 до 1000.")
            else -> showLaundry(token, service.createLaundry(token, LaundryDraft(title, info, limit)))
        }
    }

    fun find(code: String) = authenticated { token ->
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (!CODE.matches(normalized)) {
            showError("Введите ID из шести латинских букв и цифр.")
            return@authenticated
        }
        showLaundry(token, service.laundry(token, normalized))
    }

    fun openLaundry(code: String) = authenticated { token ->
        showLaundry(token, service.laundry(token, code))
    }

    fun join() = authenticated { token ->
        val laundry = details()?.laundry ?: return@authenticated
        if (laundry.membership != Membership.NONE) return@authenticated
        val updated = service.join(token, laundry.code)
        setLaundry(updated)
        loadDetailsContent(token, updated)
    }

    fun approve(userId: Int) = authenticated { token ->
        val page = details() ?: return@authenticated
        val laundry = page.laundry
        if (!laundry.canApprove || page.requests.orEmpty().none { it.userId == userId }) {
            return@authenticated
        }
        service.approve(token, laundry.code, userId)
        updateDetails { it.copy(requests = it.requests?.filterNot { request -> request.userId == userId }) }
        showNotice("Заявка одобрена.")
        setLaundry(service.laundry(token, laundry.code))
        loadRequests(token, laundry.code)
    }

    fun book(start: LocalDateTime, note: String) = authenticated { token ->
        val page = details() ?: return@authenticated
        val schedule = page.schedule ?: return@authenticated
        val slot = schedule.slots(start.toLocalDate()).firstOrNull { it.start == start }
        if (slot?.canBook != true || start.toLocalDate() !in schedule.firstDay..schedule.lastDay) {
            showError("Это время недоступно. Обновите расписание.")
            return@authenticated
        }
        if (note.trim().characters() > 200) {
            showError("Максимальная длина заметки — 200 символов.")
            return@authenticated
        }
        try {
            val booking = service.book(token, page.laundry.code, start, note.trim())
            updateDetails { it.copy(schedule = schedule.copy(bookings = schedule.bookings + booking)) }
            showNotice("Бронь создана.")
        } catch (e: ApiHttpError) {
            if (e.status == 409 || e.status == 400) resyncSchedule(token, page.laundry.code)
            throw e
        }
    }

    fun cancelBooking(booking: Booking) = authenticated { token ->
        val page = details() ?: return@authenticated
        val schedule = page.schedule ?: return@authenticated
        if (schedule.bookings.none { it.id == booking.id && it.canCancel(schedule.now) }) {
            return@authenticated
        }
        try {
            service.cancelBooking(token, page.laundry.code, booking.id)
            updateDetails {
                it.copy(schedule = schedule.copy(bookings = schedule.bookings.filterNot { b -> b.id == booking.id }))
            }
            showNotice("Бронь отменена.")
        } catch (e: ApiHttpError) {
            if (e.status == 404 || e.status == 400) resyncSchedule(token, page.laundry.code)
            throw e
        }
    }

    fun prepareExcel() = authenticated { token ->
        val laundry = details()?.laundry ?: return@authenticated
        if (laundry.membership != Membership.ACTIVE || excelFile != null) return@authenticated
        excelFile = service.exportSchedule(token, laundry.code)
        mutableState.update { it.copy(exportFilename = "laundry-${laundry.code}.xlsx") }
    }

    fun excelPickerOpened() {
        mutableState.update { it.copy(exportFilename = null) }
    }

    fun finishExcel(save: ((ByteArray) -> Unit)?) {
        val file = excelFile ?: return
        excelFile = null
        if (save == null) return
        request {
            try {
                withContext(Dispatchers.IO) { save(file) }
                showNotice("Расписание сохранено в Excel.")
            } catch (_: IOException) {
                showError("Не удалось сохранить файл. Выберите другую папку и повторите.")
            } catch (_: SecurityException) {
                showError("Нет доступа к выбранной папке.")
            }
        }
    }

    fun clearFeedback() {
        mutableState.update { it.copy(error = null, notice = null) }
    }

    private fun showError(message: String) {
        mutableState.update { it.copy(error = message, notice = null) }
    }

    private fun showNotice(message: String) {
        mutableState.update { it.copy(notice = message) }
    }

    private fun navigate(page: Page) {
        if (!state.value.busy) mutableState.update { it.copy(page = page, error = null, notice = null) }
    }

    private suspend fun startSession(session: Session) {
        val saved = sessions.save(session.token)
        token = session.token
        showHome(session.token, session.account)
        if (!saved) showNotice("При следующем запуске потребуется снова войти.")
    }

    private suspend fun endSession(message: String? = null) {
        sessions.clear()
        token = null
        excelFile = null
        mutableState.value = UiState(auth = AuthState.SignedOut, busy = true, error = message)
    }

    private suspend fun showHome(token: String, account: Account) {
        mutableState.update { it.copy(auth = AuthState.SignedIn(account), page = Page.Home, laundries = null) }
        loadLaundries(token)
    }

    private suspend fun loadLaundries(token: String) {
        val laundries = service.laundries(token)
        mutableState.update { it.copy(laundries = laundries) }
    }

    private suspend fun showLaundry(token: String, laundry: Laundry) {
        mutableState.update { it.copy(page = Page.Details(laundry)) }
        loadDetailsContent(token, laundry)
    }

    private suspend fun loadDetailsContent(token: String, laundry: Laundry) {
        if (laundry.membership != Membership.ACTIVE) return
        loadSchedule(token, laundry.code)
        if (laundry.canApprove) loadRequests(token, laundry.code)
    }

    private suspend fun loadRequests(token: String, code: String) {
        val requests = service.requests(token, code)
        updateDetails { it.copy(requests = requests) }
    }

    private suspend fun loadSchedule(token: String, code: String) {
        val schedule = service.schedule(token, code)
        updateDetails { it.copy(schedule = schedule) }
    }

    private suspend fun resyncSchedule(token: String, code: String) {
        try {
            loadSchedule(token, code)
        } catch (e: ApiHttpError) {
            if (e.status == 401) throw e
        } catch (_: ApiUnavailable) {
            // Сохраняем сообщение об отклонённой операции, даже если обновление не удалось.
        } catch (_: InvalidApiResponse) {
            // Расписание можно обновить вручную после восстановления API.
        }
    }

    private fun setLaundry(laundry: Laundry) = updateDetails {
        it.copy(
            laundry = laundry,
            schedule = it.schedule.takeIf { laundry.membership == Membership.ACTIVE },
            requests = it.requests.takeIf { laundry.canApprove },
        )
    }

    private fun details(): Page.Details? = state.value.page as? Page.Details

    private fun updateDetails(transform: (Page.Details) -> Page.Details) {
        mutableState.update {
            val page = it.page as? Page.Details ?: return@update it
            it.copy(page = transform(page))
        }
    }

    private fun authenticated(block: suspend (String) -> Unit) {
        val currentToken = token ?: return
        request { block(currentToken) }
    }

    private fun request(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiHttpError) {
                if (e.status == 401 && token != null) {
                    endSession("Сессия истекла. Войдите снова.")
                } else {
                    showError(e.message ?: "Сервер отклонил запрос.")
                }
            } catch (_: ApiUnavailable) {
                showError("Сервер недоступен. Проверьте подключение и повторите.")
            } catch (_: InvalidApiResponse) {
                showError("Сервер вернул данные в неожиданном формате.")
            } catch (_: DateTimeException) {
                showError("Сервер вернул некорректную дату или время.")
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun String.characters(): Int = codePointCount(0, length)

    private companion object {
        val USERNAME = Regex("[A-Za-z0-9_.-]{3,32}")
        val CODE = Regex("[A-Z0-9]{6}")
    }
}
