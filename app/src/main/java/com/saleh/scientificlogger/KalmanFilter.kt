package com.saleh.scientificlogger

// --- Kalman Filter Implementation ---

class KalmanFilter {
    private var x = FloatArray(6) { 0f } // State: [px, py, pz, vx, vy, vz]
    private var P = Matrix.identity(6).data // Covariance
    private var F = Matrix.identity(6).data // State transition
    private var B = FloatArray(6 * 3) { 0f } // Control input
    private val Q: Matrix.MatrixData // Process noise

    init {
        Q = Matrix.MatrixData(6, 6).apply {
            val qPos = 0.01f
            val qVel = 0.1f
            data[0] = qPos; data[7] = qPos; data[14] = qPos
            data[21] = qVel; data[28] = qVel; data[35] = qVel
        }
    }

    fun getState(): FloatArray = x.clone()

    fun predict(u: FloatArray, dt: Float) {
        // F matrix update (x_new = x_old + v*dt)
        F[3] = dt
        F[10] = dt
        F[17] = dt

        // B matrix update (p_new = p_old + v*dt + 0.5*a*dt^2 and v_new = v_old + a*dt)
        val dt2 = 0.5f * dt * dt
        B.fill(0f)
        // Correct indexing for 6x3 matrix: row * num_cols + col
        B[0 * 3 + 0] = dt2 // Px from ax
        B[1 * 3 + 1] = dt2 // Py from ay
        B[2 * 3 + 2] = dt2 // Pz from az

        B[3 * 3 + 0] = dt  // Vx from ax
        B[4 * 3 + 1] = dt  // Vy from ay
        B[5 * 3 + 2] = dt  // Vz from az

        val Fx = Matrix.multiply(F, 6, 6, x, 6, 1)
        val Bu = Matrix.multiply(B, 6, 3, u, 3, 1)
        x = Matrix.add(Fx, Bu)

        val FP = Matrix.multiply(F, 6, 6, P, 6, 6)
        val F_t = Matrix.transpose(F, 6, 6)
        val FPF_t = Matrix.multiply(FP, 6, 6, F_t, 6, 6)
        P = Matrix.add(FPF_t, Q.data)
    }

    fun update(z: FloatArray, R_data: FloatArray) {
        val H = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f, 0f
        )

        val Hx = Matrix.multiply(H, 3, 6, x, 6, 1)
        val y = Matrix.subtract(z, Hx)

        val P_Ht = Matrix.multiply(P, 6, 6, Matrix.transpose(H, 3, 6), 6, 3)
        val HP_Ht = Matrix.multiply(H, 3, 6, P_Ht, 6, 3)
        val S = Matrix.add(HP_Ht, R_data)

        val S_inv = Matrix.invert(S, 3) ?: return
        val K = Matrix.multiply(P_Ht, 6, 3, S_inv, 3, 3)

        val Ky = Matrix.multiply(K, 6, 3, y, 3, 1)
        x = Matrix.add(x, Ky)

        val KH = Matrix.multiply(K, 6, 3, H, 3, 6)
        val I_KH = Matrix.subtract(Matrix.identity(6).data, KH)
        P = Matrix.multiply(I_KH, 6, 6, P, 6, 6)
    }
}

object Matrix {
    data class MatrixData(val rows: Int, val cols: Int, val data: FloatArray = FloatArray(rows * cols))

    fun identity(size: Int): MatrixData {
        val m = MatrixData(size, size)
        for (i in 0 until size) m.data[i * size + i] = 1f
        return m
    }

    fun add(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(a.size)
        for (i in a.indices) result[i] = a[i] + b[i]
        return result
    }

    fun subtract(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(a.size)
        for (i in a.indices) result[i] = a[i] - b[i]
        return result
    }

    fun transpose(a: FloatArray, rows: Int, cols: Int): FloatArray {
        val result = FloatArray(rows * cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j * rows + i] = a[i * cols + j]
            }
        }
        return result
    }

    fun multiply(a: FloatArray, aRows: Int, aCols: Int, b: FloatArray, bRows: Int, bCols: Int): FloatArray {
        if (aCols != bRows) throw IllegalArgumentException("Matrix dimensions do not match for multiplication.")
        val result = FloatArray(aRows * bCols)
        for (i in 0 until aRows) {
            for (j in 0 until bCols) {
                var sum = 0f
                for (k in 0 until aCols) {
                    sum += a[i * aCols + k] * b[k * bCols + j]
                }
                result[i * bCols + j] = sum
            }
        }
        return result
    }

    fun invert(a: FloatArray, n: Int): FloatArray? {
        if (n != 3) return null
        val det = a[0] * (a[4] * a[8] - a[5] * a[7]) -
                  a[1] * (a[3] * a[8] - a[5] * a[6]) +
                  a[2] * (a[3] * a[7] - a[4] * a[6])
        if (det == 0f) return null
        val invDet = 1f / det
        return floatArrayOf(
            (a[4] * a[8] - a[5] * a[7]) * invDet,
            (a[2] * a[7] - a[1] * a[8]) * invDet,
            (a[1] * a[5] - a[2] * a[4]) * invDet,
            (a[5] * a[6] - a[3] * a[8]) * invDet,
            (a[0] * a[8] - a[2] * a[6]) * invDet,
            (a[2] * a[3] - a[0] * a[5]) * invDet,
            (a[3] * a[7] - a[4] * a[6]) * invDet,
            (a[1] * a[6] - a[0] * a[7]) * invDet,
            (a[0] * a[4] - a[1] * a[3]) * invDet
        )
    }
}
