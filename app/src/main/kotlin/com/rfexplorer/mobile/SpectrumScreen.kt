package com.rfexplorer.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * SKELETON — phase 5. Will host the Canvas spectrum trace (live / peak-hold / average)
 * plus controls (start/stop, span, amplitude window, module switch, sweep points).
 */
@Composable
fun SpectrumScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "RF Explorer 6G Combo",
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Spectrum view — phase 5",
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SpectrumScreenPreview() {
    SpectrumScreen()
}
