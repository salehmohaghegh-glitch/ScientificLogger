package com.saleh.scientificlogger

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saleh.scientificlogger.ui.theme.ScientificLoggerAppTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            ScientificLoggerAppTheme {
                MainScreen(viewModel)
            }
        }
    }
}

@SuppressLint("InlinedApi")
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            // Handle permission denial if needed
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFFF0F2F5)
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Scientific Data Logger", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E88E5))
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.onToggleRecordingClick() },
                colors = ButtonDefaults.buttonColors(containerColor = if (state.isRecording) Color.Red else Color(0xFF1E88E5)),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (state.isRecording) "STOP RECORDING" else "START RECORDING", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.onStartNavigationClick() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        state.isCalibrating -> Color(0xFFFFA000)
                        state.isNavigating -> Color(0xFFE91E63)
                        else -> Color(0xFF673AB7)
                    }
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                val text = when {
                    state.isCalibrating -> "CALIBRATING..."
                    state.isNavigating -> "STOP NAVIGATION"
                    else -> "START NAVIGATION (INS)"
                }
                Text(text, fontSize = 18.sp)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Count: ${state.recordCount}", modifier = Modifier.padding(8.dp), fontSize = 14.sp)
                Text("Timer: ${state.timerText}", modifier = Modifier.padding(8.dp), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (state.isRecording) Color.Red else Color.Gray)
            }
            HorizontalDivider()

            if (state.isNavigating || state.isCalibrating) {
                SensorCard("INS Navigation (Kalman Filter)",
                    "Status: ${if (state.isCalibrating) "Calibrating" else "Navigating"}",
                    "X: %.2f m".format(state.navX), "Y: %.2f m".format(state.navY), "Z: %.2f m".format(state.navZ),
                    "Vx: %.2f m/s".format(state.navVelX), "Vy: %.2f m/s".format(state.navVelY), "Vz: %.2f m/s".format(state.navVelZ),
                    "Nav Az: %.1f°".format(state.navAzimuth), "Nav Pi: %.1f°".format(state.navPitch), "Nav Ro: %.1f°".format(state.navRoll)
                )
            }

            SensorCard("Accelerometer (m/s²)", "X: %.2f".format(state.accX), "Y: %.2f".format(state.accY), "Z: %.2f".format(state.accZ), "Total: %.2f".format(state.accTotal))
            SensorCard("Gyroscope (rad/s)", "X: %.2f".format(state.gyroX), "Y: %.2f".format(state.gyroY), "Z: %.2f".format(state.gyroZ))
            SensorCard("Magnetometer (µT)", "X: %.2f".format(state.magX), "Y: %.2f".format(state.magY), "Z: %.2f".format(state.magZ))
            SensorCard("Orientation (Fused)", "Az: %.1f°".format(state.azimuth), "Pi: %.1f°".format(state.pitch), "Ro: %.1f°".format(state.roll))
            SensorCard("GPS (Native)", "Lat: %.5f".format(state.lat), "Lon: %.5f".format(state.lon), "Alt: %.1f m".format(state.alt), "Speed: %.1f m/s".format(state.speed))
        }
    }
}

@Composable
fun SensorCard(title: String, vararg values: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val half = (values.size + 1) / 2
                Column { values.take(half).forEach { Text(it, fontSize = 13.sp) } }
                Column(horizontalAlignment = Alignment.End) { values.drop(half).forEach { Text(it, fontSize = 13.sp) } }
            }
        }
    }
}
