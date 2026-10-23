package com.faustinoloeza.parquex.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Descarga y decodifica teselas raster en hilos de trabajo; el resultado (píxeles RGBA en un
 * búfer directo) se entrega en el hilo principal, donde [GroundTiles] crea la textura de Filament.
 *
 * Caché en disco con [HttpResponseCache] (respeta las cabeceras del servidor). Sin conexión se
 * reintenta con lo que haya en caché aunque esté vencido.
 */
internal class TileLoader(context: Context, threads: Int) {

    class Pixels(val width: Int, val height: Int, val rgba: ByteBuffer)

    private val cacheDir = File(context.cacheDir, "map-tiles")
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newFixedThreadPool(threads) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "map-tiles-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    /** Descarga [url]; [done] recibe los píxeles o null si falló (siempre en el hilo principal). */
    fun load(url: String, done: (Pixels?) -> Unit) {
        executor.execute {
            val pixels = if (closed) null else runCatching { fetch(url) }
                .onFailure { Log.w(TAG, "Tesela no disponible: $url (${it.message})") }
                .getOrNull()
            main.post { if (!closed) done(pixels) }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }

    private fun fetch(url: String): Pixels {
        ensureCache(cacheDir)
        val bytes = try {
            download(url, offline = false)
        } catch (e: IOException) {
            download(url, offline = true) ?: throw e
        } ?: throw IOException("respuesta vacía")
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("imagen inválida")
        try {
            // ARGB_8888 se guarda en memoria como R, G, B, A: el orden que espera Filament (RGBA).
            val out = ByteBuffer.allocateDirect(bmp.byteCount)
            bmp.copyPixelsToBuffer(out)
            out.flip()
            return Pixels(bmp.width, bmp.height, out)
        } finally {
            bmp.recycle()
        }
    }

    /** [offline] = solo caché, aunque esté vencida; null si no hay copia. */
    private fun download(url: String, offline: Boolean): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            // La política de uso de tile.openstreetmap.org exige identificar la app.
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.useCaches = true
            if (offline) conn.addRequestProperty("Cache-Control", "only-if-cached, max-stale=${60 * 60 * 24 * 60}")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                if (offline) return null
                throw IOException("HTTP $code")
            }
            return conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            if (offline) return null
            throw e
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "TileLoader"
        private const val USER_AGENT = "Parquex/1.0 (Android; com.faustinoloeza.parquex)"
        private const val CACHE_BYTES = 96L * 1024 * 1024
        private val counter = AtomicInteger()

        @Synchronized
        private fun ensureCache(dir: File) {
            if (HttpResponseCache.getInstalled() == null) {
                runCatching { HttpResponseCache.install(dir, CACHE_BYTES) }
                    .onFailure { Log.w(TAG, "Sin caché de teselas en disco", it) }
            }
        }
    }
}
