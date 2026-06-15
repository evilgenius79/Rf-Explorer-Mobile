package com.rfexplorer.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        val lastCrash = CrashReporter.consume(this)
        super.onCreate(savedInstanceState)
        setContent {
            RfExplorerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showCrash by remember { mutableStateOf(lastCrash != null) }
                    if (showCrash && lastCrash != null) {
                        CrashScreen(lastCrash) { showCrash = false }
                    } else {
                        SpectrumScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun CrashScreen(text: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Last crash report", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text("Screenshot this and send it over, then continue.", fontSize = 12.sp)
        Button(onClick = onDismiss) { Text("Continue to app") }
        Text(text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}
