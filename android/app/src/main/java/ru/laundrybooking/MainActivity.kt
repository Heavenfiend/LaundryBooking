package ru.laundrybooking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.laundrybooking.data.FastApiTransport
import ru.laundrybooking.data.LaundryApiAdapter
import ru.laundrybooking.data.SessionStore
import ru.laundrybooking.ui.LaundryApp
import ru.laundrybooking.ui.LaundryTheme
import ru.laundrybooking.ui.LaundryViewModel

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
        setContent {
            LaundryTheme { LaundryApp(model) }
        }
    }
}
