package dev.laundry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.laundry.app.ui.LaundryApp
import dev.laundry.app.ui.LaundryViewModel
import dev.laundry.app.ui.theme.LaundryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LaundryApplication).container
        setContent {
            LaundryTheme {
                val viewModel = viewModel<LaundryViewModel>(
                    factory = viewModelFactory {
                        initializer {
                            LaundryViewModel(
                                weather = container.weatherRepository,
                                locations = container.locationRepository,
                                loadPreferences = container.loadPreferences,
                                searchPlaces = container::searchPlaces,
                            )
                        }
                    },
                )
                LaundryApp(viewModel)
            }
        }
    }
}
