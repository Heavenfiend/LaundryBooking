package ru.laundrybooking.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import ru.laundrybooking.R
import ru.laundrybooking.domain.Membership

private val LightColors = lightColorScheme(
    primary = Color(0xFF125953),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F1E9),
    onPrimaryContainer = Color(0xFF083A34),
    secondary = Color(0xFF4C635D),
    secondaryContainer = Color(0xFFE2EDE8),
    onSecondaryContainer = Color(0xFF253A33),
    tertiaryContainer = Color(0xFFF7EBD3),
    onTertiaryContainer = Color(0xFF503A15),
    background = Color(0xFFF6FAF8),
    surface = Color(0xFFF6FAF8),
    surfaceContainer = Color(0xFFE9F1ED),
    surfaceContainerLow = Color(0xFFF0F6F2),
    surfaceContainerLowest = Color.White,
    onSurface = Color(0xFF172623),
    onSurfaceVariant = Color(0xFF435651),
    outline = Color(0xFF6D807A),
    outlineVariant = Color(0xFFC4D3CD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF89DBCC),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF174B42),
    onPrimaryContainer = Color(0xFFAAEEE1),
    secondaryContainer = Color(0xFF354A43),
    onSecondaryContainer = Color(0xFFD8E9E3),
    tertiaryContainer = Color(0xFF58451F),
    onTertiaryContainer = Color(0xFFF7E3B5),
    background = Color(0xFF101C19),
    surface = Color(0xFF101C19),
    surfaceContainer = Color(0xFF20302B),
    surfaceContainerLow = Color(0xFF182620),
    surfaceContainerLowest = Color(0xFF0D1714),
    onSurface = Color(0xFFE2ECE7),
    onSurfaceVariant = Color(0xFFBACBC3),
    outline = Color(0xFF899C94),
    outlineVariant = Color(0xFF45574F),
)

@Composable
internal fun LaundryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

@Composable
internal fun LaundryApp(model: LaundryViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    ) { uri ->
        model.finishExcel(uri?.let { destination ->
            { bytes ->
                val output = context.contentResolver.openOutputStream(destination, "wt")
                    ?: throw IOException("Document provider did not open an output stream")
                output.use { it.write(bytes) }
            }
        })
    }
    LaunchedEffect(state.exportFilename, state.busy) {
        val filename = state.exportFilename
        if (filename != null && !state.busy) {
            model.excelPickerOpened()
            exportPicker.launch(filename)
        }
    }
    BackHandler(enabled = state.auth is AuthState.SignedIn && state.page != Page.Home) {
        if (!state.busy) model.backToHome()
    }

    Scaffold(topBar = {
        if (state.auth is AuthState.SignedIn) {
            LaundryTopBar(
                page = state.page,
                busy = state.busy,
                back = model::backToHome,
                refresh = model::refresh,
                logout = model::logout,
                share = { code ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "ID прачечной в LaundryBooking — $code")
                    }
                    context.startActivity(Intent.createChooser(send, null))
                },
            )
        }
    }) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Feedback(it, isError = true, dismiss = model::clearFeedback) }
                state.notice?.let { Feedback(it, isError = false, dismiss = model::clearFeedback) }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val auth = state.auth) {
                        AuthState.Restoring -> Loading()
                        AuthState.RetryRestore -> if (state.busy) Loading() else RestoreSession(model)
                        AuthState.SignedOut -> AuthScreen(state.busy, model)
                        is AuthState.SignedIn -> when (val page = state.page) {
                            Page.Home -> HomeScreen(auth.account, state.laundries, state.busy, model)
                            Page.Create -> CreateLaundryScreen(state.busy, model::create)
                            Page.Find -> FindLaundryScreen(state.busy, model::find)
                            is Page.Details -> key(page.laundry.code) {
                                LaundryDetailsScreen(page, state.busy, model)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaundryTopBar(
    page: Page,
    busy: Boolean,
    back: () -> Unit,
    refresh: () -> Unit,
    logout: () -> Unit,
    share: (String) -> Unit,
) {
    TopAppBar(
        title = {
            when (page) {
                Page.Home -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LaundryLogo(Modifier.size(32.dp))
                    Text("LaundryBooking", fontWeight = FontWeight.SemiBold)
                }
                Page.Create -> Text("Новая прачечная")
                Page.Find -> Text("Поиск прачечной")
                is Page.Details -> Text("Прачечная")
            }
        },
        navigationIcon = {
            if (page != Page.Home) {
                IconButton(onClick = back, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                }
            }
        },
        actions = {
            if (page is Page.Details) {
                IconButton(onClick = { share(page.laundry.code) }, enabled = !busy) {
                    Icon(Icons.Default.Share, contentDescription = "Поделиться ID")
                }
            }
            if (page == Page.Home || (page is Page.Details && page.laundry.membership == Membership.ACTIVE)) {
                IconButton(onClick = refresh, enabled = !busy) {
                    Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                }
            }
            if (page == Page.Home) {
                IconButton(onClick = logout, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Выйти из аккаунта")
                }
            }
        },
    )
}

@Composable
internal fun LaundryLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_laundry), contentDescription = null, modifier = modifier)
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun RestoreSession(model: LaundryViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Восстановление сеанса", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = model::restoreSession) { Text("Повторить") }
        TextButton(onClick = model::logout) { Text("Войти заново") }
    }
}

@Composable
private fun Feedback(message: String, isError: Boolean, dismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = if (isError) colors.errorContainer else colors.primaryContainer,
        contentColor = if (isError) colors.onErrorContainer else colors.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            IconButton(onClick = dismiss) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть сообщение")
            }
        }
    }
}
