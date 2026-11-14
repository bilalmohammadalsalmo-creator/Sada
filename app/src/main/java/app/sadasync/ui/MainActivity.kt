package app.sadasync.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.sadasync.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SadaTheme { Root() } }
    }
}

@Composable
private fun Root() {
    var activated by remember { mutableStateOf(App.instance.prefs.activated) }
    if (!activated) ActivationScreen { activated = true } else MainScreen()
}
