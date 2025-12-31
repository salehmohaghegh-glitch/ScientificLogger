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
import com.patrykandpatryk.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatryk.vico.core.entry.entryOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.*

enum class SensorType {
    ACCELEROMETER,
    GYROSCOPE
}

class MainViewModel(application: Application) : AndroidViewModel(application), SensorEventListener, LocationListener {

    private val sensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // -- Sensor Listeners --
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // -- Data Buffers for Averaging --
    private val accBuffer = mutableListOf<FloatArray>()
    private val gyroBuffer = mutableListOf<FloatArray>()
    private val magBuffer = mutableListOf<FloatArray>()
    private val rotVecBuffer = mutableListOf<FloatArray>()
    private val bufferLock = Any()

    // -- Averaged Sensor Data --
    private var lastAcc = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var lastMag = FloatArray(3)
    private var lastLocation: Location? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // -- Navigation & Kalman Filter --
    private val navRotationMatrix = FloatArray(9)
    private val navOrientationAngles = FloatArray(3)
    private var kalmanFilter: KalmanFilter? = null
    private var lastTickNanoTime = 0L
    private var lastGyroNanoTime = 0L
    private val accelBias = FloatArray(3)
    private val gyroBias = FloatArray(3)
    private val calibrationAccSum = FloatArray(3)
    private val calibrationGyroSum = FloatArray(3)
    private var calibrationCount = 0
    private var worldOriginLocation: Location? = null

    // -- GPS Derived Data --
    private val gpsPositionEnu = FloatArray(3)
    private var gpsCourse = 0f
    private val locationHistory = LinkedList<Location>()

    // -- Recording --
    private val recordedData = ArrayList<SensorRecord>()
    private var recordingStartTime = 0L

    // -- UI State --
    val uiState = mutableStateOf(UiDashboardState())
    val chartToShow = mutableStateOf<SensorType?>(null)
    val chartModelProducer = ChartEntryModelProducer()

    private val historySize = 200 // Approx. 10 seconds at SENSOR_DELAY_GAME
    private val accXHistory = LinkedList<Float>()
    private val accYHistory = LinkedList<Float>()
    private val accZHistory = LinkedList<Float>()
    private val accTotalHistory = LinkedList<Float>()
    private val gyroXHistory = LinkedList<Float>()
    private val gyroYHistory = LinkedList<Float>()
    private val gyroZHistory = LinkedList<Float>()
    private val gyroTotalHistory = LinkedList<Float>()

    init {
        startSensors()
        startFixedRateSampler()
    }

    override fun onCleared() {
        super.onCleared()
        stopSensors()
    }

    // --- Public UI Actions --- 
    fun showChart(sensorType: SensorType) {
        updateChartModel(sensorType)
        chartToShow.value = sensorType
    }

    fun hideChart() {
        chartToShow.value = null
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
            uiState.value = uiState.value.copy(isRecording = false)
            if (recordedData.isNotEmpty()) {
                saveToCsv(context)
            }
        } else {
            recordedData.clear()
            recordingStartTime = System.currentTimeMillis()
            uiState.value = uiState.value.copy(isRecording = true, recordCount = 0)
        }
    }

    // --- Sensor and Timer Management ---
    @SuppressLint("MissingPermission")
    private fun startSensors() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, this)
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
    }

    private fun startFixedRateSampler() {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val processingStartTime = System.nanoTime()
                processSensorBuffers()
                updateUiState()
                if (uiState.value.isRecording) {
                    recordData(processingStartTime)
                }
                val processingTime = (System.nanoTime() - processingStartTime) / 1_000_000
                delay(maxOf(0, 10 - processingTime))
            }
        }
    }

    // --- Core Logic --- 

    private fun processSensorBuffers() {
        synchronized(bufferLock) {
            if (accBuffer.isNotEmpty()) {
                lastAcc = averageFloatArrays(accBuffer)
                accBuffer.clear()
                updateHistory(accXHistory, lastAcc[0])
                updateHistory(accYHistory, lastAcc[1])
                updateHistory(accZHistory, lastAcc[2])
                updateHistory(accTotalHistory, sqrt(lastAcc[0] * lastAcc[0] + lastAcc[1] * lastAcc[1] + lastAcc[2] * lastAcc[2]))
            }
            if (gyroBuffer.isNotEmpty()) {
                lastGyro = averageFloatArrays(gyroBuffer)
                gyroBuffer.clear()
                updateHistory(gyroXHistory, lastGyro[0])
                updateHistory(gyroYHistory, lastGyro[1])
                updateHistory(gyroZHistory, lastGyro[2])
                updateHistory(gyroTotalHistory, sqrt(lastGyro[0] * lastGyro[0] + lastGyro[1] * lastGyro[1] + lastGyro[2] * lastGyro[2]))
            }
            if (magBuffer.isNotEmpty()) {
                lastMag = averageFloatArrays(magBuffer)
                magBuffer.clear()
            }
            if (rotVecBuffer.isNotEmpty()) {
                val avgRotVec = averageFloatArrays(rotVecBuffer)
                if (avgRotVec.size >= 3) SensorManager.getRotationMatrixFromVector(rotationMatrix, avgRotVec)
                rotVecBuffer.clear()
            }
        }
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        if (uiState.value.isNavigating) {
            processIns()
            updateNavRotationMatrix()
        }
    }

    private fun updateUiState() {
        val kfState = kalmanFilter?.getState() ?: FloatArray(6)
        val navPosition = floatArrayOf(kfState[0], kfState[1], kfState[2])
        val navVelocity = floatArrayOf(kfState[3], kfState[4], kfState[5])
        val totalAcc = sqrt(lastAcc[0] * lastAcc[0] + lastAcc[1] * lastAcc[1] + lastAcc[2] * lastAcc[2])
        val totalMag = sqrt(lastMag[0] * lastMag[0] + lastMag[1] * lastMag[1] + lastMag[2] * lastMag[2])
        val timeDiff = if (uiState.value.isRecording) System.currentTimeMillis() - recordingStartTime else 0L
        val timerStr = String.format(Locale.US, "%02d:%02d:%03d", timeDiff / 1000 / 60, timeDiff / 1000 % 60, timeDiff % 1000)

        SensorManager.getOrientation(navRotationMatrix, navOrientationAngles)

        viewModelScope.launch(Dispatchers.Main) {
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
                gpsX = gpsPositionEnu[0], gpsY = gpsPositionEnu[1], gpsZ = gpsPositionEnu[2],
                gpsCourse = gpsCourse
            )
        }
    }

    // --- Navigation and Processing ---
    private fun startNavigationProcess() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            Toast.makeText(context, "Calibrating... Keep Still for 10s", Toast.LENGTH_LONG).show()

            synchronized(bufferLock) {
                calibrationAccSum.fill(0f)
                calibrationGyroSum.fill(0f)
                calibrationCount = 0
                uiState.value = uiState.value.copy(isCalibrating = true)
            }

            delay(10000) // Increased calibration time

            synchronized(bufferLock) {
                uiState.value = uiState.value.copy(isCalibrating = false, isNavigating = true)
                if (calibrationCount > 0) {
                    accelBias[0] = calibrationAccSum[0] / calibrationCount
                    accelBias[1] = calibrationAccSum[1] / calibrationCount
                    accelBias[2] = calibrationAccSum[2] / calibrationCount - SensorManager.GRAVITY_EARTH

                    gyroBias[0] = calibrationGyroSum[0] / calibrationCount
                    gyroBias[1] = calibrationGyroSum[1] / calibrationCount
                    gyroBias[2] = calibrationGyroSum[2] / calibrationCount
                }

                kalmanFilter = KalmanFilter()
                worldOriginLocation = lastLocation
                locationHistory.clear()
                lastLocation?.let { locationHistory.add(it) }
                System.arraycopy(rotationMatrix, 0, navRotationMatrix, 0, 9)
                lastTickNanoTime = 0L
            }
            Toast.makeText(context, "Navigation Started!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopNavigation() {
        uiState.value = uiState.value.copy(isNavigating = false, isCalibrating = false)
        kalmanFilter = null
    }

    private fun processIns() {
        if (kalmanFilter == null) return
        val currentNanoTime = System.nanoTime()
        if (lastTickNanoTime == 0L) {
            lastTickNanoTime = currentNanoTime
            return
        }

        val dt = (currentNanoTime - lastTickNanoTime) * 1.0e-9f
        if (dt <= 0) return
        lastTickNanoTime = currentNanoTime

        val worldAcc = FloatArray(3)
        worldAcc[0] = rotationMatrix[0] * lastAcc[0] + rotationMatrix[1] * lastAcc[1] + rotationMatrix[2] * lastAcc[2]
        worldAcc[1] = rotationMatrix[3] * lastAcc[0] + rotationMatrix[4] * lastAcc[1] + rotationMatrix[5] * lastAcc[2]
        worldAcc[2] = rotationMatrix[6] * lastAcc[0] + rotationMatrix[7] * lastAcc[1] + rotationMatrix[8] * lastAcc[2]

        val linearAcc = floatArrayOf(worldAcc[0] - accelBias[0], worldAcc[1] - accelBias[1], worldAcc[2] - accelBias[2])

        kalmanFilter!!.predict(linearAcc, dt)
    }

    private fun updateNavRotationMatrix() {
        val currentNanoTime = System.nanoTime()
        if (lastGyroNanoTime == 0L) {
            lastGyroNanoTime = currentNanoTime
            return
        }

        val dt = (currentNanoTime - lastGyroNanoTime) * 1.0e-9f
        if (dt <= 0) return
        lastGyroNanoTime = currentNanoTime

        val gyro = floatArrayOf(lastGyro[0] - gyroBias[0], lastGyro[1] - gyroBias[1], lastGyro[2] - gyroBias[2])

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
        val origin = worldOriginLocation ?: return

        if (locationHistory.isNotEmpty()) {
            val lastLoc = locationHistory.last()
            if (lastLoc.distanceTo(location) > 0.5f) {
                gpsCourse = lastLoc.bearingTo(location)
                locationHistory.add(location)
                if (locationHistory.size > 2) {
                    locationHistory.removeFirst()
                }
            }
        }

        val enu = lla2enu(location, origin)
        gpsPositionEnu[0] = enu[0]
        gpsPositionEnu[1] = enu[1]
        gpsPositionEnu[2] = enu[2]

        if (uiState.value.isNavigating && kalmanFilter != null) {
            val measurement = floatArrayOf(enu[0], enu[1], enu[2])
            val measurementCovariance = floatArrayOf(
                location.accuracy * location.accuracy, 0f, 0f,
                0f, location.accuracy * location.accuracy, 0f,
                0f, 0f, (location.verticalAccuracyMeters * location.verticalAccuracyMeters)
            )
            kalmanFilter!!.update(measurement, measurementCovariance)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        synchronized(bufferLock) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> accBuffer.add(event.values.clone())
                Sensor.TYPE_GYROSCOPE -> gyroBuffer.add(event.values.clone())
                Sensor.TYPE_MAGNETIC_FIELD -> magBuffer.add(event.values.clone())
                Sensor.TYPE_ROTATION_VECTOR -> rotVecBuffer.add(event.values.clone())
            }
        }
    }

    private fun updateHistory(history: MutableList<Float>, value: Float) {
        history.add(value)
        if (history.size > historySize) {
            (history as LinkedList).removeFirst()
        }
    }

    private fun updateChartModel(sensorType: SensorType) {
        val entries = when (sensorType) {
            SensorType.ACCELEROMETER -> listOf(accXHistory, accYHistory, accZHistory, accTotalHistory)
            SensorType.GYROSCOPE -> listOf(gyroXHistory, gyroYHistory, gyroZHistory, gyroTotalHistory)
        }
        chartModelProducer.setEntries(entries.map { it.mapIndexed { index, value -> entryOf(index, value) } })
    }

    private fun recordData(currentNanoTime: Long) {
        val kfState = kalmanFilter?.getState() ?: FloatArray(6)
        recordedData.add(
            SensorRecord(
                timestamp = System.currentTimeMillis(),
                nanoTimestamp = currentNanoTime,
                elapsedTime = System.currentTimeMillis() - recordingStartTime,
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
                gpsX = gpsPositionEnu[0], gpsY = gpsPositionEnu[1], gpsZ = gpsPositionEnu[2],
                gpsCourse = gpsCourse, gpsPitch = 0f, // Pitch from GPS is not implemented
                gpsNavDiff = 0f
            )
        )
    }

    private fun averageFloatArrays(buffer: List<FloatArray>): FloatArray {
        if (buffer.isEmpty()) return floatArrayOf()
        val first = buffer.first()
        val sum = FloatArray(first.size)
        for (values in buffer) {
            if(values.size == first.size) {
                for (i in values.indices) {
                    sum[i] += values[i]
                }
            }
        }
        for (i in sum.indices) {
            sum[i] /= buffer.size
        }
        return sum
    }

    private fun lla2enu(lla: Location, origin: Location): FloatArray {
        val a = 6378137.0 // WGS84 major axis
        val f = 1.0 / 298.257223563 // WGS84 flattening
        val e2 = f * (2 - f)

        val lat0Rad = Math.toRadians(origin.latitude)
        val lon0Rad = Math.toRadians(origin.longitude)
        val h0 = origin.altitude

        val latRad = Math.toRadians(lla.latitude)
        val lonRad = Math.toRadians(lla.longitude)
        val h = lla.altitude

        val n0 = a / sqrt(1 - e2 * sin(lat0Rad) * sin(lat0Rad))
        val x0 = (n0 + h0) * cos(lat0Rad) * cos(lon0Rad)
        val y0 = (n0 + h0) * cos(lat0Rad) * sin(lon0Rad)
        val z0 = (n0 * (1 - e2) + h0) * sin(lat0Rad)

        val n = a / sqrt(1 - e2 * sin(latRad) * sin(latRad))
        val x = (n + h) * cos(latRad) * cos(lonRad)
        val y = (n + h) * cos(latRad) * sin(lonRad)
        val z = (n * (1 - e2) + h) * sin(latRad)

        val dx = x - x0
        val dy = y - y0
        val dz = z - z0

        val sLat = sin(lat0Rad)
        val cLat = cos(lat0Rad)
        val sLon = sin(lon0Rad)
        val cLon = cos(lon0Rad)

        val e = -sLon * dx + cLon * dy
        val n_enu = -sLat * cLon * dx - sLat * sLon * dy + cLat * dz
        val u = cLat * cLon * dx + cLat * sLon * dy + sLat * dz

        return floatArrayOf(e.toFloat(), n_enu.toFloat(), u.toFloat())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    private fun saveToCsv(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = "SensorLog_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
            val csvHeader = "Timestamp_ISO,Timestamp_Nano,Timer_ms,Accel_X,Accel_Y,Accel_Z,Gyro_X,Gyro_Y,Gyro_Z,Mag_X,Mag_Y,Mag_Z,Azimuth,Pitch,Roll,Nav_Azimuth,Nav_Pitch,Nav_Roll,Lat,Lon,Alt,Speed,Accuracy,Nav_X,Nav_Y,Nav_Z,Nav_Vel_X,Nav_Vel_Y,Nav_Vel_Z,GPS_X,GPS_Y,GPS_Z,GPS_Course,GPS_Pitch,GPS_Nav_Diff\n"

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
                        val line = "${isoFormat.format(Date(record.timestamp))},${record.nanoTimestamp},${record.elapsedTime},${record.accX},${record.accY},${record.accZ},${record.gyroX},${record.gyroY},${record.gyroZ},${record.magX},${record.magY},${record.magZ},${record.azimuth},${record.pitch},${record.roll},${record.navAzimuth},${record.navPitch},${record.navRoll},${record.lat},${record.lon},${record.alt},${record.speed},${record.accuracy},${record.navX},${record.navY},${record.navZ},${record.navVelX},${record.navVelY},${record.navVelZ},${record.gpsX},${record.gpsY},${record.gpsZ},${record.gpsCourse},${record.gpsPitch},${record.gpsNavDiff}\n"
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
