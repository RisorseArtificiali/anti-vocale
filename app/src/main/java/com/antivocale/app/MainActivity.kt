package com.antivocale.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.antivocale.app.di.AppContainer
import com.antivocale.app.ui.MainScreen
import com.antivocale.app.ui.theme.AntiVocaleTheme
import com.antivocale.app.ui.theme.ThemeType

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Collect theme preference and convert to ThemeType
            val themeName by AppContainer.preferencesManager.themePreference.collectAsState(initial = "DEFAULT")
            val theme = try {
                ThemeType.valueOf(themeName)
            } catch (e: IllegalArgumentException) {
                ThemeType.DEFAULT
            }

            AntiVocaleTheme(theme = theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
