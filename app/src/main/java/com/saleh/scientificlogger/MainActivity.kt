package com.saleh.scientificlogger

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

// مدل داده برای ذخیره‌سازی هر رکورد
data class SensorRecord(
    val timestamp: Long,
    val nanoTimestamp: Long, // زمان دقیق سنسور (برای دقت علمی و تفکیک داده‌ها)
    val elapsedTime: Long, // فیلد جدید برای تایمر
    val accX: Float, val accY: Float, val accZ: Float,
    val gyroX: Float, val gyroY: Float, val gyroZ: Float,
    val magX: Float, val magY: Float, val magZ: Float,
    val azimuth: Float, val pitch: Float, val roll: Float,
    val lat: Double, val lon: Double, val alt: Double, val speed: Float, val accuracy: Float
)

class MainActivity : ComponentActivity(), SensorEventListener, LocationListener {

    // مدیریت سنسورها و مکان
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var locationManager: LocationManager? = null

    // متغیرهای نگهداری مقادیر فعلی
    private var lastAcc = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var lastMag = FloatArray(3)
    private var lastLocation: Location? = null

    // متغیرهای محاسبه جهت (Orientation)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // وضعیت ضبط
    private var isRecording = false
    private val recordedData = ArrayList<SensorRecord>()
    private var recordingStartTime = 0L
    // وضعیت‌های UI (استفاده از State برای Jetpack Compose)
    private val uiState = mutableStateOf(UiDashboardState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // فعال کردن Wake Lock برای روشن ماندن صفحه
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // راه‌اندازی سرویس‌های سیستم
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startSensors() // شروع سنسورها بلافاصله بعد از باز شدن برنامه
        setContent {
            ScientificLoggerTheme {
                MainScreen()
            }
        }

        // شروع یک حلقه برای به‌روزرسانی UI جدا از سرعت بالای سنسورها
        // این کار باعث می‌شود UI لگ نزند حتی اگر سنسورها 200Hz باشند
        startUiUpdateLoop()
    }
    override fun onDestroy() {
        super.onDestroy()
        stopSensors()
    }

    private fun startUiUpdateLoop() {
        val uiUpdateScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)
        uiUpdateScope.launch {
            while (true) {
                updateUiState()
                delay(100) // به‌روزرسانی UI هر 100 میلی‌ثانیه (10 فریم بر ثانیه)
            }
        }
    }

    private fun updateUiState() {
        // محاسبه مگنتیک کل و شتاب کل
        val totalAcc = sqrt((lastAcc[0] * lastAcc[0] + lastAcc[1] * lastAcc[1] + lastAcc[2] * lastAcc[2]).toDouble()).toFloat()
        val totalMag = sqrt((lastMag[0] * lastMag[0] + lastMag[1] * lastMag[1] + lastMag[2] * lastMag[2]).toDouble()).toFloat()
// محاسبه متن تایمر
        val timeDiff = if (isRecording) System.currentTimeMillis() - recordingStartTime else 0L
        val seconds = timeDiff / 1000
        val millis = timeDiff % 1000
        val timerStr = String.format("%02d:%02d:%03d", seconds / 60, seconds % 60, millis)
        uiState.value = uiState.value.copy(
            accX = lastAcc[0], accY = lastAcc[1], accZ = lastAcc[2], accTotal = totalAcc,
            gyroX = lastGyro[0], gyroY = lastGyro[1], gyroZ = lastGyro[2],
            magX = lastMag[0], magY = lastMag[1], magZ = lastMag[2], magTotal = totalMag,
            azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat(),
            pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat(),
            roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat(),
            lat = lastLocation?.latitude ?: 0.0,
            lon = lastLocation?.longitude ?: 0.0,
            alt = lastLocation?.altitude ?: 0.0,
            speed = lastLocation?.speed ?: 0f,
            accuracy = lastLocation?.accuracy ?: 0f,
            recordCount = recordedData.size,
            timerText = timerStr, // مقداردهی فیلد جدید
            isRecording = isRecording
        )
    }

    // --- مدیریت سنسورها ---
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        synchronized(this) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, lastAcc, 0, 3)
                Sensor.TYPE_GYROSCOPE -> System.arraycopy(event.values, 0, lastGyro, 0, 3)
                Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, lastMag, 0, 3)
            }

            // محاسبه جهت
            SensorManager.getRotationMatrix(rotationMatrix, null, lastAcc, lastMag)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // لاگ کردن داده‌ها اگر ضبط فعال باشد
// لاگ کردن داده‌ها اگر ضبط فعال باشد
            if (isRecording) {
                val currentTime = System.currentTimeMillis()
                val elapsedTime = currentTime - recordingStartTime // محاسبه تایمر به میلی‌ثانیه
                val sensorNanoTime = event.timestamp
                recordedData.add(
                    SensorRecord(
                        currentTime,
                        sensorNanoTime, // <--- این مقدار جدید است
                        elapsedTime, // ذخیره تایمر
                        lastAcc[0], lastAcc[1], lastAcc[2],
                        lastGyro[0], lastGyro[1], lastGyro[2],
                        lastMag[0], lastMag[1], lastMag[2],
                        Math.toDegrees(orientationAngles[0].toDouble()).toFloat(),
                        Math.toDegrees(orientationAngles[1].toDouble()).toFloat(),
                        Math.toDegrees(orientationAngles[2].toDouble()).toFloat(),
                        lastLocation?.latitude ?: 0.0,
                        lastLocation?.longitude ?: 0.0,
                        lastLocation?.altitude ?: 0.0,
                        lastLocation?.speed ?: 0f,
                        lastLocation?.accuracy ?: 0f
                    )
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- مدیریت مکان (Native) ---
    override fun onLocationChanged(location: Location) {
        lastLocation = location
    }

    // این متدهای LocationListener برای سازگاری با نسخه‌های قدیمی‌تر لازم هستند
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // --- کنترل شروع و پایان ---
    private fun startSensors() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_FASTEST)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // درخواست آپدیت مکان از GPS و شبکه
            locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this)
            locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, this)
        }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        locationManager?.removeUpdates(this)
    }

    private fun toggleRecording(context: Context) {
        if (isRecording) {
            // توقف ضبط
            isRecording = false
           // stopSensors()
            saveToCsv(context)
        } else {
            // شروع ضبط
            recordedData.clear()
            recordingStartTime = System.currentTimeMillis() // ثبت لحظه شروع
            isRecording = true
           // startSensors()
        }
    }

    // --- ذخیره فایل CSV ---
    private fun saveToCsv(context: Context) {
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            val fileName = "SensorLog_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
           // val csvHeader = "Timestamp_ISO,Accel_X,Accel_Y,Accel_Z,Total_Accel,Gyro_X,Gyro_Y,Gyro_Z,Mag_X,Mag_Y,Mag_Z,Azimuth,Pitch,Roll,Lat,Lon,Alt,Speed\n"
           // val csvHeader = "Timestamp_ISO,Timer_ms,Accel_X,Accel_Y,Accel_Z,Total_Accel,Gyro_X,Gyro_Y,Gyro_Z,Mag_X,Mag_Y,Mag_Z,Azimuth,Pitch,Roll,Lat,Lon,Alt,Speed\n"
            val csvHeader = "Timestamp_ISO,Timestamp_Nano,Timer_ms,Accel_X,Accel_Y,Accel_Z,Total_Accel,Gyro_X,Gyro_Y,Gyro_Z,Mag_X,Mag_Y,Mag_Z,Azimuth,Pitch,Roll,Lat,Lon,Alt,Speed\n"

            try {
                val outputStream: OutputStream?
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/SensorLogs")
                    }
                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                    outputStream = uri?.let { resolver.openOutputStream(it) }
                } else {
                    // برای اندرویدهای قدیمی‌تر
                    val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), fileName)
                    outputStream = java.io.FileOutputStream(file)
                }

                outputStream?.use { stream ->
                    stream.write(csvHeader.toByteArray())
                    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

                    // کپی لیست برای جلوگیری از خطای همزمانی
                    val dataToWrite = ArrayList(recordedData)

                    for (record in dataToWrite) {
                        val totalAcc = sqrt(record.accX * record.accX + record.accY * record.accY + record.accZ * record.accZ)
                        val line = "${isoFormat.format(Date(record.timestamp))}," +
                                "${record.nanoTimestamp}," + // نوشتن زمان نانوثانیه
                                "${record.elapsedTime}," + // نوشتن مقدار تایمر
                                "${record.accX},${record.accY},${record.accZ},$totalAcc," +
                                "${record.gyroX},${record.gyroY},${record.gyroZ}," +
                                "${record.magX},${record.magY},${record.magZ}," +
                                "${record.azimuth},${record.pitch},${record.roll}," +
                                "${record.lat},${record.lon},${record.alt},${record.speed}\n"
                        stream.write(line.toByteArray())
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Saved: $fileName", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error saving file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- رابط کاربری Compose ---
    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        val state = uiState.value

        // لانچر درخواست مجوز
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            // بررسی ساده مجوزها
            val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            if (locationGranted) {
                Toast.makeText(context, "Permissions Granted", Toast.LENGTH_SHORT).show()
            }
        }

        LaunchedEffect(Unit) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.HIGH_SAMPLING_RATE_SENSORS // فقط برای اندروید 12+ نادیده گرفته میشود در پایینتر
                )
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color(0xFFF0F2F5)
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Scientific Data Logger",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E88E5)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // نمایش وضعیت WakeLock
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Screen Wake Lock: ON", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // دکمه ضبط
                Button(
                    onClick = { toggleRecording(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isRecording) Color.Red else Color(0xFF1E88E5)
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(if (state.isRecording) "STOP RECORDING" else "START RECORDING", fontSize = 18.sp)
                }

                Text(
                    text = "Buffer Count: ${state.recordCount}",
                    modifier = Modifier.padding(8.dp),
                    fontSize = 14.sp
                )
                Text(
                    text = "Timer: ${state.timerText}",
                    modifier = Modifier.padding(bottom = 8.dp),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isRecording) Color.Red else Color.Gray
                )
                Divider()

                // پنل‌های داده
                SensorCard("Accelerometer (m/s²)",
                    "X: %.2f".format(state.accX), "Y: %.2f".format(state.accY), "Z: %.2f".format(state.accZ), "Total: %.2f".format(state.accTotal))

                SensorCard("Gyroscope (rad/s)",
                    "X: %.2f".format(state.gyroX), "Y: %.2f".format(state.gyroY), "Z: %.2f".format(state.gyroZ))

                SensorCard("Magnetometer (µT)",
                    "X: %.2f".format(state.magX), "Y: %.2f".format(state.magY), "Z: %.2f".format(state.magZ), "Total: %.2f".format(state.magTotal))

                SensorCard("Orientation (Degrees)",
                    "Azimuth: %.1f".format(state.azimuth), "Pitch: %.1f".format(state.pitch), "Roll: %.1f".format(state.roll))

                SensorCard("GPS (Native)",
                    "Lat: %.5f".format(state.lat), "Lon: %.5f".format(state.lon),
                    "Alt: %.1f m".format(state.alt), "Speed: %.1f m/s".format(state.speed), "Acc: %.1f m".format(state.accuracy))
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
                    // تقسیم مقادیر در دو ستون اگر زیاد باشند
                    val half = (values.size + 1) / 2
                    Column {
                        values.take(half).forEach { Text(it, fontSize = 13.sp) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        values.drop(half).forEach { Text(it, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

// کلاس کمکی برای نگهداری وضعیت UI
data class UiDashboardState(
    val accX: Float = 0f, val accY: Float = 0f, val accZ: Float = 0f, val accTotal: Float = 0f,
    val gyroX: Float = 0f, val gyroY: Float = 0f, val gyroZ: Float = 0f,
    val magX: Float = 0f, val magY: Float = 0f, val magZ: Float = 0f, val magTotal: Float = 0f,
    val azimuth: Float = 0f, val pitch: Float = 0f, val roll: Float = 0f,
    val lat: Double = 0.0, val lon: Double = 0.0, val alt: Double = 0.0, val speed: Float = 0f, val accuracy: Float = 0f,
    val recordCount: Int = 0,
    val timerText: String = "00:00:000", // فیلد جدید
    val isRecording: Boolean = false
)

@Composable
fun ScientificLoggerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Color(0xFF1E88E5)),
        content = content
    )
}