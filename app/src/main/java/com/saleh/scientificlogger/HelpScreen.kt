package com.saleh.scientificlogger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen() {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("This app is a scientific data logger that records sensor data from your device. It also provides a navigation system that uses a Kalman filter to fuse data from the accelerometer, gyroscope, and GPS to provide a more accurate location than any single sensor could provide.")
    }
}
