package com.saleh.scientificlogger

data class SensorRecord(
    val timestamp: Long,
    val nanoTimestamp: Long,
    val elapsedTime: Long,
    val accX: Float, val accY: Float, val accZ: Float,
    val gyroX: Float, val gyroY: Float, val gyroZ: Float,
    val magX: Float, val magY: Float, val magZ: Float,
    val azimuth: Float, val pitch: Float, val roll: Float,
    val lat: Double, val lon: Double, val alt: Double, val speed: Float, val accuracy: Float,
    val navX: Float, val navY: Float, val navZ: Float,
    val navVelX: Float, val navVelY: Float, val navVelZ: Float,
    val navAzimuth: Float, val navPitch: Float, val navRoll: Float,
    val gpsNavDiff: Float
)

data class UiDashboardState(
    val accX: Float = 0f, val accY: Float = 0f, val accZ: Float = 0f, val accTotal: Float = 0f,
    val gyroX: Float = 0f, val gyroY: Float = 0f, val gyroZ: Float = 0f,
    val magX: Float = 0f, val magY: Float = 0f, val magZ: Float = 0f, val magTotal: Float = 0f,
    val azimuth: Float = 0f, val pitch: Float = 0f, val roll: Float = 0f,
    val lat: Double = 0.0, val lon: Double = 0.0, val alt: Double = 0.0, val speed: Float = 0f, val accuracy: Float = 0f,
    val recordCount: Int = 0,
    val timerText: String = "00:00:000",
    val isRecording: Boolean = false,
    val navX: Float = 0f, val navY: Float = 0f, val navZ: Float = 0f,
    val navVelX: Float = 0f, val navVelY: Float = 0f, val navVelZ: Float = 0f,
    val navAzimuth: Float = 0f, val navPitch: Float = 0f, val navRoll: Float = 0f,
    val gpsDiff: Float = 0f,
    val isNavigating: Boolean = false,
    val isCalibrating: Boolean = false
)
