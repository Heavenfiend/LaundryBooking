package ru.laundrybooking.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import ru.laundrybooking.domain.Booking
import ru.laundrybooking.domain.HourlySlot
import ru.laundrybooking.domain.JoinRequest
import ru.laundrybooking.domain.Laundry
import ru.laundrybooking.domain.Membership
import ru.laundrybooking.domain.Schedule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LaundryDetailsScreen(page: Page.Details, busy: Boolean, model: LaundryViewModel) {
    val laundry = page.laundry
    if (laundry.membership != Membership.ACTIVE) {
        MembershipScreen(laundry, busy, model)
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var slotToBook by remember { mutableStateOf<HourlySlot?>(null) }
    var bookingToCancel by remember { mutableStateOf<Booking?>(null) }
    val tabs = if (laundry.canApprove) listOf("Расписание", "Мои брони", "Заявки")
    else listOf("Расписание", "Мои брони")
    val selectedTab = tab.coerceIn(tabs.indices)

    Column(Modifier.fillMaxSize()) {
        LaundryHeading(laundry)
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { tab = index },
                    text = { Text(title) },
                )
            }
        }
        when (selectedTab) {
            0 -> if (page.schedule != null) {
                ScheduleScreen(
                    schedule = page.schedule,
                    selectedDay = selectedDay,
                    selectDay = { selectedDay = it },
                    busy = busy,
                    book = { slotToBook = it },
                    cancel = { bookingToCancel = it },
                    export = model::prepareExcel,
                )
            } else {
                DetailsPlaceholder(busy, "Расписание не загрузилось")
            }
            1 -> if (page.schedule != null) {
                MyBookings(page.schedule, busy) { bookingToCancel = it }
            } else {
                DetailsPlaceholder(busy, "Брони не загрузились")
            }
            2 -> RequestsScreen(page.requests, busy, model::approve)
        }
    }

    slotToBook?.let { slot ->
        BookingDialog(slot, dismiss = { slotToBook = null }) { note ->
            slotToBook = null
            model.book(slot.start, note)
        }
    }
    bookingToCancel?.let { booking ->
        AlertDialog(
            onDismissRequest = { bookingToCancel = null },
            title = { Text("Отменить бронь?") },
            text = { Text(booking.fullTimeRange()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        bookingToCancel = null
                        model.cancelBooking(booking)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Подтвердить отмену") }
            },
            dismissButton = {
                TextButton(onClick = { bookingToCancel = null }) { Text("Оставить") }
            },
        )
    }
}

@Composable
private fun MembershipScreen(laundry: Laundry, busy: Boolean, model: LaundryViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { LaundryHeading(laundry) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (laundry.membership == Membership.PENDING) {
                    EmptyPanel("Заявка отправлена", "Расписание откроется после одобрения заявки.")
                    FilledTonalButton(
                        onClick = model::refresh,
                        enabled = !busy,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Проверить статус") }
                } else {
                    Button(
                        onClick = model::join,
                        enabled = !busy,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Подать заявку") }
                }
            }
        }
    }
}

@Composable
private fun LaundryHeading(laundry: Laundry) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 12.dp),
        shape = MaterialTheme.shapes.large,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                laundry.name,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (laundry.description.isNotBlank()) {
                Text(laundry.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SelectionContainer {
                Text(
                    "ID ${laundry.code}  ·  ${laundry.memberCount}/${laundry.capacity} участников",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ScheduleScreen(
    schedule: Schedule,
    selectedDay: String?,
    selectDay: (String) -> Unit,
    busy: Boolean,
    book: (HourlySlot) -> Unit,
    cancel: (Booking) -> Unit,
    export: () -> Unit,
) {
    val today = schedule.now.toLocalDate()
    val day = (selectedDay?.let(LocalDate::parse) ?: today).coerceIn(schedule.firstDay, schedule.lastDay)
    val days = remember(schedule.firstDay, schedule.lastDay) {
        val count = ChronoUnit.DAYS.between(schedule.firstDay, schedule.lastDay).toInt() + 1
        List(count) { schedule.firstDay.plusDays(it.toLong()) }
    }
    val slots = remember(schedule, day) { schedule.slots(day) }
    val datesScroll = rememberLazyListState(initialFirstVisibleItemIndex = days.indexOf(day).coerceAtLeast(0))

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            state = datesScroll,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(days, key = { it.toEpochDay() }) { date ->
                FilterChip(
                    selected = date == day,
                    onClick = { selectDay(date.toString()) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    label = {
                        Text(
                            when (date) {
                                today -> "Сегодня"
                                today.plusDays(1) -> "Завтра"
                                else -> date.format(SHORT_DAY)
                            },
                        )
                    },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(day.format(LONG_DAY), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Местное время прачечной",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = export, enabled = !busy) { Text("Сохранить Excel") }
        }
        key(day) {
            val hoursScroll = rememberLazyListState(initialFirstVisibleItemIndex = if (day == today) schedule.now.hour else 0)
            LazyColumn(
                state = hoursScroll,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, top = 6.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(slots, key = { hour, _ -> hour }) { _, slot ->
                    SlotCard(slot, schedule.now, busy, book, cancel)
                }
            }
        }
    }
}

@Composable
private fun SlotCard(
    slot: HourlySlot,
    now: LocalDateTime,
    busy: Boolean,
    book: (HourlySlot) -> Unit,
    cancel: (Booking) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val own = slot.bookings.any { it.mine }
    val occupied = slot.bookings.isNotEmpty()
    val past = !occupied && !slot.canBook
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (past) colors.surfaceContainerLow else colors.surfaceContainerLowest,
        contentColor = if (past) colors.onSurfaceVariant else colors.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${slot.start.format(HOUR)}–${slot.end.format(HOUR)}",
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                when {
                    slot.canBook -> FilledTonalButton(
                        onClick = { book(slot) },
                        enabled = !busy,
                        shape = MaterialTheme.shapes.medium,
                    ) { Text("Забронировать") }
                    own -> StatusLabel("Ваша бронь", colors.primaryContainer, colors.onPrimaryContainer)
                    occupied -> StatusLabel("Занято", colors.tertiaryContainer, colors.onTertiaryContainer)
                    else -> Text("Время прошло", style = MaterialTheme.typography.labelLarge)
                }
            }
            slot.bookings.forEach { booking ->
                Column(Modifier.padding(bottom = 5.dp)) {
                    Text(
                        "${booking.name}  ·  ${booking.timeRange()}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (booking.note.isNotEmpty()) Text(booking.note, style = MaterialTheme.typography.bodyMedium)
                    if (booking.canCancel(now)) {
                        TextButton(onClick = { cancel(booking) }, enabled = !busy) { Text("Отменить бронь") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyBookings(schedule: Schedule, busy: Boolean, cancel: (Booking) -> Unit) {
    val upcoming = remember(schedule) { schedule.myBookings() }
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (upcoming.isEmpty()) {
            item { EmptyPanel("Предстоящих броней нет") }
        }
        items(upcoming, key = { it.id }) { booking ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = colors.surfaceContainerLow,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = colors.primary)
                        Text(booking.fullTimeRange(), style = MaterialTheme.typography.titleMedium)
                    }
                    if (booking.note.isNotEmpty()) {
                        Text(booking.note, modifier = Modifier.padding(top = 4.dp))
                    }
                    TextButton(
                        onClick = { cancel(booking) },
                        enabled = !busy,
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                    ) { Text("Отменить бронь") }
                }
            }
        }
    }
}

@Composable
private fun RequestsScreen(requests: List<JoinRequest>?, busy: Boolean, approve: (Int) -> Unit) {
    if (requests == null) {
        DetailsPlaceholder(busy, "Заявки не загрузились")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (requests.isEmpty()) {
            item { EmptyPanel("Новых заявок нет") }
        }
        items(requests, key = { it.userId }) { request ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(request.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("@${request.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                        FilledTonalButton(
                            onClick = { approve(request.userId) },
                            enabled = !busy,
                            shape = MaterialTheme.shapes.medium,
                        ) { Text("Одобрить") }
                }
            }
        }
    }
}

@Composable
private fun DetailsPlaceholder(busy: Boolean, title: String) {
    if (busy) {
        Loading()
    } else {
        Column(Modifier.padding(20.dp)) {
            EmptyPanel(title, "Нажмите «Обновить» в верхней панели.")
        }
    }
}

@Composable
private fun BookingDialog(slot: HourlySlot, dismiss: () -> Unit, confirm: (String) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    val noteTooLong = note.trim().let { it.codePointCount(0, it.length) > 200 }
    AlertDialog(
        onDismissRequest = dismiss,
        icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
        title = { Text("Забронировать час?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${slot.start.format(DATE_AND_HOUR)}–${slot.end.format(HOUR)}")
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    label = { Text("Заметка (необязательно)") },
                    maxLines = 3,
                    isError = noteTooLong,
                    supportingText = {
                        Text(if (noteTooLong) "До 200 символов" else "Заметку увидят участники прачечной")
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { confirm(note) },
                enabled = !noteTooLong,
                shape = MaterialTheme.shapes.medium,
            ) { Text("Подтвердить") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Назад") } },
    )
}

private val RUSSIAN = Locale.forLanguageTag("ru")
private val SHORT_DAY = DateTimeFormatter.ofPattern("EE d.MM", RUSSIAN)
private val LONG_DAY = DateTimeFormatter.ofPattern("d MMMM, EEEE", RUSSIAN)
private val DATE_AND_HOUR = DateTimeFormatter.ofPattern("d MMM, HH:mm", RUSSIAN)
private val HOUR = DateTimeFormatter.ofPattern("HH:mm", RUSSIAN)

private fun Booking.timeRange(): String {
    val endFormat = if (start.toLocalDate() == end.toLocalDate()) HOUR else DATE_AND_HOUR
    return "${start.format(HOUR)}–${end.format(endFormat)}"
}

private fun Booking.fullTimeRange(): String {
    val endFormat = if (start.toLocalDate() == end.toLocalDate()) HOUR else DATE_AND_HOUR
    return "${start.format(DATE_AND_HOUR)}–${end.format(endFormat)}"
}
