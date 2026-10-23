package com.faustinoloeza.parquex.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ubicación real con el [LocationManager] del sistema (GPS + red, sin Google Play Services). Solo
 * en primer plano: [start] / [stop] siguen el ciclo de vida de la pantalla. [fix] es estado de
 * Compose.
 */
class LocationTracker(private val context: Context) {

    data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val time: Long)

    private val manager = context.getSystemService(LocationManager::class.java)

    var fix by mutableStateOf<Fix?>(null)
        private set

    private var running = false

    // Implementa todos los métodos: en API < 30 no tienen implementación por defecto.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** true si el GPS o la ubicación por red están encendidos en el teléfono. */
    fun isEnabled(): Boolean = manager != null && PROVIDERS.any { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || manager == null || !hasPermission()) return
        running = true
        for (p in PROVIDERS) {
            if (!runCatching { manager.isProviderEnabled(p) }.getOrDefault(false)) continue
            runCatching { manager.getLastKnownLocation(p)?.let(::accept) }
            runCatching { manager.requestLocationUpdates(p, 2_000L, 1f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        manager?.removeUpdates(listener)
    }

    private fun accept(location: Location) {
        val current = fix
        // Ignora lecturas viejas o mucho menos precisas que una reciente (p. ej. red después de GPS).
        if (current != null) {
            if (location.time < current.time) return
            if (location.time - current.time < 10_000 && location.accuracy > current.accuracy * 2f) return
        }
        fix = Fix(location.latitude, location.longitude, location.accuracy, location.time)
    }

    companion object {
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** Distancia sobre la esfera (m). */
        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
            return 2 * r * asin(sqrt(a))
        }
    }
}
