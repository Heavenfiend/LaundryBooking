package ru.laundrybooking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.laundrybooking.domain.Account
import ru.laundrybooking.domain.Laundry
import ru.laundrybooking.domain.Membership
import ru.laundrybooking.domain.Role

@Composable
internal fun AuthScreen(busy: Boolean, model: LaundryViewModel) {
    var register by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        if (register) model.register(username, name, password) else model.login(username, password)
    }

    FormColumn {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LaundryLogo(Modifier.size(40.dp))
                    Text("LaundryBooking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    if (register) "Создайте аккаунт" else "Войдите в аккаунт",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            label = { Text("Логин") },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
        )
        if (register) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                label = { Text("Имя для расписания") },
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            label = { Text("Пароль") },
            supportingText = if (register) ({ Text("От 6 символов") }) else null,
            enabled = !busy,
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }, enabled = !busy) {
                    Text(if (showPassword) "Скрыть" else "Показать")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
        )
        Button(
            onClick = submit,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(if (register) "Зарегистрироваться" else "Войти")
        }
        TextButton(
            onClick = {
                register = !register
                password = ""
                showPassword = false
                model.clearFeedback()
            },
            enabled = !busy,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(if (register) "Уже есть аккаунт" else "Создать аккаунт")
        }
    }
}

@Composable
internal fun HomeScreen(account: Account, laundries: List<Laundry>?, busy: Boolean, model: LaundryViewModel) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Мои прачечные",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Button(onClick = model::openCreate, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Создать", modifier = Modifier.padding(start = 8.dp))
                        }
                        OutlinedButton(onClick = model::openFind, enabled = !busy, shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Найти по ID", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
        when {
            laundries == null && !busy -> item {
                EmptyPanel("Не удалось загрузить прачечные", "Нажмите «Обновить» в верхней панели.")
            }
            laundries?.isEmpty() == true -> item {
                EmptyPanel("Здесь пока пусто", "Создайте прачечную или найдите её по ID из приглашения.")
            }
            laundries != null -> items(laundries, key = { it.code }) { laundry ->
                Card(
                    onClick = { model.openLaundry(laundry.code) },
                    enabled = !busy,
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(laundry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (laundry.description.isNotEmpty()) {
                            Text(laundry.description, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            "ID ${laundry.code}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        if (laundry.membership == Membership.PENDING) {
                            StatusLabel(laundry.membershipLabel(), colors.tertiaryContainer, colors.onTertiaryContainer)
                        } else {
                            StatusLabel(laundry.membershipLabel(), colors.secondaryContainer, colors.onSecondaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CreateLaundryScreen(busy: Boolean, create: (String, String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var capacity by rememberSaveable { mutableStateOf("30") }
    val focus = LocalFocusManager.current
    FormColumn {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            label = { Text("Название") },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            label = { Text("Описание (необязательно)") },
            enabled = !busy,
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        OutlinedTextField(
            value = capacity,
            onValueChange = { capacity = it },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            label = { Text("Лимит участников") },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button(
            onClick = {
                focus.clearFocus()
                create(name, description, capacity)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("Создать прачечную")
        }
    }
}

@Composable
internal fun FindLaundryScreen(busy: Boolean, find: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val submit = {
        focus.clearFocus()
        find(code)
    }
    FormColumn {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            label = { Text("ID прачечной") },
            supportingText = { Text("Шесть букв или цифр из приглашения") },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { if (!busy) submit() }),
        )
        Button(
            onClick = submit,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
        ) { Text("Найти") }
    }
}

@Composable
private fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
internal fun StatusLabel(label: String, color: Color, contentColor: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color, contentColor = contentColor) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun EmptyPanel(title: String, helpfulText: String? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            helpfulText?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

internal fun Laundry.membershipLabel(): String = when (membership) {
    Membership.NONE -> "Доступ по заявке"
    Membership.PENDING -> "Заявка на рассмотрении"
    Membership.ACTIVE -> when (role) {
        Role.OWNER -> "Создатель"
        Role.ADMIN -> "Администратор"
        else -> "Участник"
    }
}
