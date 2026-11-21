package ru.laundrybooking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.laundrybooking.BuildConfig
import ru.laundrybooking.domain.Account

internal enum class ThemeMode(val label: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная"),
}

@Composable
internal fun SettingsScreen(
    account: Account,
    theme: ThemeMode,
    selectTheme: (ThemeMode) -> Unit,
    busy: Boolean,
    logout: () -> Unit,
) {
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = MaterialTheme.shapes.medium, color = colors.surface) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = colors.primary)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Аккаунт", style = MaterialTheme.typography.labelLarge)
                        Text(account.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Тема оформления",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = colors.surfaceContainerLow,
                ) {
                    Column(Modifier.selectableGroup()) {
                        ThemeMode.entries.forEachIndexed { index, option ->
                            if (index != 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = option == theme,
                                        role = Role.RadioButton,
                                        onClick = { selectTheme(option) },
                                    )
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = option == theme, onClick = null)
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { confirmLogout = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Выйти из аккаунта", modifier = Modifier.padding(start = 8.dp))
            }
        }
        item {
            Text(
                "LaundryBooking · версия ${BuildConfig.VERSION_NAME}",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        confirmLogout = false
                        logout()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Отмена") }
            },
        )
    }
}
