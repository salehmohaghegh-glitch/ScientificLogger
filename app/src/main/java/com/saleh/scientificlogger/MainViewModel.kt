package com.saleh.scientificlogger

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainViewModel(application: Application) : AndroidViewModel(application), SensorEventListener, LocationListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var lastAcc = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var lastMag = FloatArray(3)
    private var lastLocation: Location? = null

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val navRotationMatrix = FloatArray(9)
    private val navOrientationAngles = FloatArray(3)

    private var kalmanFilter: KalmanFilter? = null
    private var lastNavNanoTime = 0L
    private var lastGyroNanoTime = 0L
    private val accelBias = FloatArray(3)
    private val gyroBias = FloatArray(3)
    private val calibrationAccSum = FloatArray(3)
    private val calibrationGyroSum = FloatArray(3)
    private var calibrationCount = 0
    private var worldOriginLocation: Location? = null

    private val recordedData = ArrayList<SensorRecord>()
    private var recordingStartTime = 0L

    val uiState = mutableStateOf(UiDashboardState())

    init {
        startSensors()
        startUiUpdateLoop()
    }

    override fun onCleared() {
        super.onCleared()
        stopSensors()
    }

    fun onStartNavigationClick() {
        if (uiState.value.isNavigating || uiState.value.isCalibrating) {
            stopNavigation()
        } else {
            startNavigationProcess()
        }
    }

    fun onToggleRecordingClick() {
        val context = getApplication<Application>().applicationContext
        if (uiState.value.isRecording) {
            if (recordedData.isNotEmpty()) {
                saveToCsv(context)
            }
            uiState.value = uiState.value.copy(isRecording = false)
        } else {
            recordedData.clear()
            recordingStartTime = System.currentTimeMillis()
            uiState.value = uiState.value.copy(isRecording = true, recordCount = 0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSensors() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0.5f, this)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500L, 0.5f, this)
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    private fun startUiUpdateLoop() {
        viewModelScope.launch {
            while (true) {
                val kfState = kalmanFilter?.getState() ?: FloatArray(6)
                val navPosition = floatArrayOf(kfState[0], kfState[1], kfState[2])
                val navVelocity = floatArrayOf(kfState[3], kfState[4], kfState[5])

                val totalAcc = sqrt(lastAcc[0] * lastAcc[0] + lastAcc[1] * lastAcc[1] + lastAcc[2] * lastAcc[2])
                val totalMag = sqrt(lastMag[0] * lastMag[0] + lastMag[1] * lastMag[1] + lastMag[2] * lastMag[2])

                val timeDiff = if (uiState.value.isRecording) System.currentTimeMillis() - recordingStartTime else 0L
                val seconds = timeDiff / 1000
                val millis = timeDiff % 1000
                val timerStr = String.format(Locale.US, "%02d:%02d:%03d", seconds / 60, seconds % 60, millis)

                SensorManager.getOrientation(navRotationMatrix, navOrientationAngles)

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
                    timerText = timerStr,
                    navX = navPosition[0], navY = navPosition[1], navZ = navPosition[2],
                    navVelX = navVelocity[0], navVelY = navVelocity[1], navVelZ = navVelocity[2],
                    navAzimuth = Math.toDegrees(navOrientationAngles[0].toDouble()).toFloat(),
                    navPitch = Math.toDegrees(navOrientationAngles[1].toDouble()).toFloat(),
                    navRoll = Math.toDegrees(navOrientationAngles[2].toDouble()).toFloat(),
                )
                delay(100)
            }
        }
    }

    private fun startNavigationProcess() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            Toast.makeText(context, "Calibrating... Keep Still for 5s", Toast.LENGTH_LONG).show()

            calibrationAccSum.fill(0f)
            calibrationGyroSum.fill(0f)
            calibrationCount = 0
            uiState.value = uiState.value.copy(isCalibrating = true)

            delay(5000)

            if (calibrationCount > 0) {
                accelBias[0] = calibrationAccSum[0] / calibrationCount
                accelBias[1] = calibrationAccSum[1] / calibrationCount
                accelBias[2] = calibrationAccSum[2] / calibrationCount - SensorManager.GRAVITY_EARTH

                gyroBias[0] = calibrationGyroSum[0] / calibrationCount
                gyroBias[1] = calibrationGyroSum[1] / calibrationCount
                gyroBias[2] = calibrationGyroSum[2] / calibrationCount
            }

            kalmanFilter = KalmanFilter()
            System.arraycopy(rotationMatrix, 0, navRotationMatrix, 0, 9)
            worldOriginLocation = lastLocation
            lastNavNanoTime = 0L
            lastGyroNanoTime = 0L
            uiState.value = uiState.value.copy(isCalibrating = false, isNavigating = true)
            Toast.makeText(context, "Navigation Started!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopNavigation() {
        uiState.value = uiState.value.copy(isNavigating = false, isCalibrating = false)
        kalmanFilter = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        val currentNanoTime = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAcc, 0, 3)
                if (uiState.value.isCalibrating) {
                    val worldAcc = FloatArray(3)
                    worldAcc[0] = rotationMatrix[0] * lastAcc[0] + rotationMatrix[1] * lastAcc[1] + rotationMatrix[2] * lastAcc[2]
                    worldAcc[1] = rotationMatrix[3] * lastAcc[0] + rotationMatrix[4] * lastAcc[1] + rotationMatrix[5] * lastAcc[2]
                    worldAcc[2] = rotationMatrix[6] * lastAcc[0] + rotationMatrix[7] * lastAcc[1] + rotationMatrix[8] * lastAcc[2]
                    calibrationAccSum[0] += worldAcc[0]
                    calibrationAccSum[1] += worldAcc[1]
                    calibrationAccSum[2] += worldAcc[2]
                    calibrationCount++
                }
                if (uiState.value.isNavigating) {
                    processIns(currentNanoTime)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                System.arraycopy(event.values, 0, lastGyro, 0, 3)
                if (uiState.value.isCalibrating) {
                    calibrationGyroSum[0] += lastGyro[0]
                    calibrationGyroSum[1] += lastGyro[1]
                    calibrationGyroSum[2] += lastGyro[2]
                }
                if (uiState.value.isNavigating) {
                    updateNavRotationMatrix(currentNanoTime)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, lastMag, 0, 3)
        }

        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        if (uiState.value.isRecording) {
            val currentTime = System.currentTimeMillis()
            val elapsedTime = currentTime - recordingStartTime
            val kfState = kalmanFilter?.getState() ?: FloatArray(6)

            var diff = 0f
            if (worldOriginLocation != null && lastLocation != null) {
                val gpsDist = worldOriginLocation!!.distanceTo(lastLocation!!)
                val navDist = sqrt(kfState[0] * kfState[0] + kfState[1] * kfState[1] + kfState[2] * kfState[2])
                diff = kotlin.math.abs(navDist - gpsDist)
            }

            recordedData.add(
                SensorRecord(
                    timestamp = currentTime,
                    nanoTimestamp = currentNanoTime,
                    elapsedTime = elapsedTime,
                    accX = lastAcc[0], accY = lastAcc[1], accZ = lastAcc[2],
                    gyroX = lastGyro[0], gyroY = lastGyro[1], gyroZ = lastGyro[2],
                    magX = lastMag[0], magY = lastMag[1], magZ = lastMag[2],
                    azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat(),
                    pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat(),
                    roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat(),
                    lat = lastLocation?.latitude ?: 0.0,
                    lon = lastLocation?.longitude ?: 0.0,
                    alt = lastLocation?.altitude ?: 0.0,
                    speed = lastLocation?.speed ?: 0f,
                    accuracy = lastLocation?.accuracy ?: 0f,
                    navX = kfState[0], navY = kfState[1], navZ = kfState[2],
                    navVelX = kfState[3], navVelY = kfState[4], navVelZ = kfState[5],
                    navAzimuth = Math.toDegrees(navOrientationAngles[0].toDouble()).toFloat(),
                    navPitch = Math.toDegrees(navOrientationAngles[1].toDouble()).toFloat(),
                    navRoll = Math.toDegrees(navOrientationAngles[2].toDouble()).toFloat(),
                    gpsNavDiff = diff
                )
            )
        }
    }

    private fun processIns(currentNanoTime: Long) {
        if (kalmanFilter == null) return
        if (lastNavNanoTime == 0L) {
            lastNavNanoTime = currentNanoTime
            return
        }

        val dt = (currentNanoTime - lastNavNanoTime) * 1.0e-9f
        if (dt <= 0) return
        lastNavNanoTime = currentNanoTime

        val worldAcc = FloatArray(3)
        worldAcc[0] = rotationMatrix[0] * lastAcc[0] + rotationMatrix[1] * lastAcc[1] + rotationMatrix[2] * lastAcc[2]
        worldAcc[1] = rotationMatrix[3] * lastAcc[0] + rotationMatrix[4] * lastAcc[1] + rotationMatrix[5] * lastAcc[2]
        worldAcc[2] = rotationMatrix[6] * lastAcc[0] + rotationMatrix[7] * lastAcc[1] + rotationMatrix[8] * lastAcc[2]

        val linearAcc = floatArrayOf(
            worldAcc[0] - accelBias[0],
            worldAcc[1] - accelBias[1],
            worldAcc[2] - (SensorManager.GRAVITY_EARTH + accelBias[2])
        )

        kalmanFilter!!.predict(linearAcc, dt)
    }

    private fun updateNavRotationMatrix(currentNanoTime: Long) {
        if (lastGyroNanoTime == 0L) {
            lastGyroNanoTime = currentNanoTime
            return
        }

        val dt = (currentNanoTime - lastGyroNanoTime) * 1.0e-9f
        if (dt <= 0) return
        lastGyroNanoTime = currentNanoTime

        val gyro = floatArrayOf(
            lastGyro[0] - gyroBias[0],
            lastGyro[1] - gyroBias[1],
            lastGyro[2] - gyroBias[2]
        )

        val angle = sqrt(gyro[0] * gyro[0] + gyro[1] * gyro[1] + gyro[2] * gyro[2]) * dt
        if (angle > 1e-5) {
            val axisX = gyro[0] * dt / angle
            val axisY = gyro[1] * dt / angle
            val axisZ = gyro[2] * dt / angle

            val c = cos(angle)
            val s = sin(angle)
            val t = 1 - c

            val deltaRotationMatrix = floatArrayOf(
                t * axisX * axisX + c, t * axisX * axisY - s * axisZ, t * axisX * axisZ + s * axisY,
                t * axisX * axisY + s * axisZ, t * axisY * axisY + c, t * axisY * axisZ - s * axisX,
                t * axisX * axisZ - s * axisY, t * axisY * axisZ + s * axisX, t * axisZ * axisZ + c
            )

            val newRotationMatrix = Matrix.multiply(navRotationMatrix, 3, 3, deltaRotationMatrix, 3, 3)
            System.arraycopy(newRotationMatrix, 0, navRotationMatrix, 0, 9)
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        if (uiState.value.isNavigating && worldOriginLocation != null && kalmanFilter != null) {
            val bearing = worldOriginLocation!!.bearingTo(location)
            val distance = worldOriginLocation!!.distanceTo(location)

            val dx = distance * sin(Math.toRadians(bearing.toDouble())).toFloat()
            val dy = distance * cos(Math.toRadians(bearing.toDouble())).toFloat()
            val dz = (location.altitude - (worldOriginLocation?.altitude ?: 0.0)).toFloat()

            val measurement = floatArrayOf(dx, dy, dz)
            val measurementCovariance = floatArrayOf(
                location.accuracy * location.accuracy, 0f, 0f,
                0f, location.accuracy * location.accuracy, 0f,
                0f, 0f, (location.verticalAccuracyMeters * location.verticalAccuracyMeters)
            )
            kalmanFilter!!.update(measurement, measurementCovariance)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun saveToCsv(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = "SensorLog_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
            val csvHeader = "Timestamp_ISO,Timestamp_Nano,Timer_ms,Accel_X,Accel_Y,Accel_Z,Gyro_X,Gyro_Y,Gyro_Z,Mag_X,Mag_Y,Mag_Z,Azimuth,Pitch,Roll,Nav_Azimuth,Nav_Pitch,Nav_Roll,Lat,Lon,Alt,Speed,Accuracy,Nav_X,Nav_Y,Nav_Z,Nav_Vel_X,Nav_Vel_Y,Nav_Vel_Z,GPS_Nav_Diff\n"

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
                    @Suppress("DEPRECATION")
                    val file = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), fileName)
                    outputStream = java.io.FileOutputStream(file)
                }

                outputStream?.use { stream ->
                    stream.write(csvHeader.toByteArray())
                    val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                    val dataToWrite = ArrayList(recordedData)

                    for (record in dataToWrite) {
                        val line = "${isoFormat.format(Date(record.timestamp))}," +
                                "${record.nanoTimestamp}," +
                                "${record.elapsedTime}," +
                                "${record.accX},${record.accY},${record.accZ}," +
                                "${record.gyroX},${record.gyroY},${record.gyroZ}," +
                                "${record.magX},${record.magY},${record.magZ}," +
                                "${record.azimuth},${record.pitch},${record.roll}," +
                                "${record.navAzimuth},${record.navPitch},${record.navRoll}," +
                                "${record.lat},${record.lon},${record.alt},${record.speed},${record.accuracy}," +
                                "${record.navX},${record.navY},${record.navZ}," +
                                "${record.navVelX},${record.navVelY},${record.navVelZ}," +
                                "${record.gpsNavDiff}\n"
                        stream.write(line.toByteArray())
                    }
                }
                withContext(Dispatchers.Main) { Toast.makeText(context, "Saved: $fileName", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
