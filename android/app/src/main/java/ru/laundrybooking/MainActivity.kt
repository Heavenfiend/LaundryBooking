package ru.laundrybooking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.laundrybooking.data.FastApiTransport
import ru.laundrybooking.data.LaundryApiAdapter
import ru.laundrybooking.data.SessionStore
import ru.laundrybooking.ui.LaundryApp
import ru.laundrybooking.ui.LaundryTheme
import ru.laundrybooking.ui.LaundryViewModel
import ru.laundrybooking.ui.ThemeMode

class MainActivity : ComponentActivity() {
    private val model: LaundryViewModel by viewModels {
        viewModelFactory {
            initializer {
                val url = BuildConfig.API_URL
                LaundryViewModel(
                    LaundryApiAdapter(FastApiTransport(url)),
                    SessionStore(applicationContext, url),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = getSharedPreferences("laundry_appearance", MODE_PRIVATE)
        setContent {
            var theme by remember {
                val saved = preferences.getString("theme", null)
                mutableStateOf(ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM)
            }
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            LaundryTheme(dark) {
                LaundryApp(model, theme) { selected ->
                    theme = selected
                    preferences.edit().putString("theme", selected.name).apply()
                }
            }
        }
    }
}
