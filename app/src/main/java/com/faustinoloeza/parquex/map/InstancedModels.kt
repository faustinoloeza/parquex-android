package com.faustinoloeza.parquex.map

import android.util.Log
import com.faustinoloeza.parquex.map.PlainMeshes.Primitive
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.android.filament.gltfio.FilamentAsset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modelos repetidos (árboles, palmeras, autobuses) a partir de **un prototipo por tipo** y una
 * lista de copias, en lugar de geometría duplicada en el GLB (gltfio no dibuja
 * `EXT_mesh_gpu_instancing` y el `InstanceBuffer` de Filament admite solo 64 copias).
 *
 * - Prototipos: nodos `proto:<tipo>` del GLB, en el origen y sin transformación; sus primitivas se
 *   leen del binario del GLB ([PlainMeshes]) y se reutilizan los MaterialInstance que crea gltfio.
 * - Copias: `instances.bin` ("PQI1"): por tipo, registros de 28 bytes con x, y, z, rotY, escala XZ,
 *   escala Y y tinte RGB8 (sRGB, multiplica al color de vértice).
 *
 * La geometría se arma en el dispositivo **fusionada por (material, zona de [ZONE] m, canal)**:
 * mismos draw calls y culling que la versión fusionada por el exportador, pero el archivo pesa
 * ~0.3 MB en lugar de ~8–10 MB.
 */
internal class InstancedModels(
    private val engine: Engine,
    private val scene: Scene,
    /** Lado (m) de las zonas en que se fusionan las copias: menos zonas = menos draw calls, culling más grueso. */
    private val zone: Float = ZONE,
) {

    companion object {
        private const val TAG = "InstancedModels"
        const val PROTO_PREFIX = "proto:"
        private const val ZONE = 350f
        private const val RECORD = 28
        /** pos (3 × f32) + color (4 × u8) + uv (2 × f32); las tangentes van en otro búfer. */
        private const val STRIDE = 24

        private val SRGB_TO_LINEAR = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    /** Copias de un tipo: 7 floats (x, y, z, rotY, escala, escalaY, -) + tinte lineal por copia. */
    private class Copies(val count: Int, val data: FloatArray, val tint: FloatArray)

    private class Bucket(val mi: MaterialInstance, val channel: Int) {
        var vertices = 0
        var indices = 0
        val parts = ArrayList<Pair<Primitive, Int>>() // (primitiva, copia)
    }

    private val entities = ArrayList<Int>()
    private val vertexBuffers = ArrayList<VertexBuffer>()
    private val indexBuffers = ArrayList<IndexBuffer>()
    private var shown = false

    val renderableCount get() = entities.size

    /**
     * Arma las copias de [instances] con los prototipos [protos] (de [readPrototypes]), **sin**
     * agregarlas a la escena (ver [show]). [channelFor] da el canal de dibujo por tipo; [keep] (tipo,
     * índice del registro dentro del tipo) permite omitir copias, p. ej. árboles de contexto en
     * calidad media o baja.
     */
    fun build(
        protos: Map<String, List<Primitive>>,
        instances: ByteBuffer,
        channelFor: (String) -> Int,
        keep: ((String, Int) -> Boolean)? = null,
    ) {
        clearMeshes()
        val t0 = System.nanoTime()
        val copiesByType = readInstances(instances, keep)

        // Cubetas por (material, zona, canal).
        val buckets = LinkedHashMap<String, Bucket>()
        var copies = 0
        for ((type, set) in copiesByType) {
            val prims = protos[type]
            if (prims == null) { Log.w(TAG, "Sin prototipo para «$type»"); continue }
            val channel = channelFor(type)
            copies += set.count
            for (c in 0 until set.count) {
                val zx = floor(set.data[c * 7] / zone).toInt()
                val zz = floor(set.data[c * 7 + 2] / zone).toInt()
                for (p in prims) {
                    val bucket = buckets.getOrPut("${p.material}|$zx,$zz|$channel") { Bucket(p.materialInstance, channel) }
                    bucket.parts += p to c
                    bucket.vertices += p.positions.size / 3
                    bucket.indices += p.indices.size
                }
            }
        }

        for (bucket in buckets.values) {
            entities += buildBucket(bucket, copiesByType) ?: continue
        }
        if (shown) entities.forEach(scene::addEntity)
        Log.i(TAG, "Instancias: $copies copias de ${copiesByType.size} tipos → ${entities.size} renderables " +
            "en ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    private fun buildBucket(bucket: Bucket, copiesByType: Map<String, Copies>): Int? {
        val n = bucket.vertices
        if (n == 0 || bucket.indices == 0) return null
        val interleaved = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder())
        val normals = ByteBuffer.allocateDirect(n * 12).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val wide = n > 65_535
        val idx = ByteBuffer.allocateDirect(bucket.indices * if (wide) 4 else 2).order(ByteOrder.nativeOrder())
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE

        var base = 0
        for ((p, c) in bucket.parts) {
            val set = copiesByType.getValue(p.type)
            val d = set.data; val o = c * 7
            val tx = d[o]; val ty = d[o + 1]; val tz = d[o + 2]
            val cs = cos(d[o + 3]); val sn = sin(d[o + 3])
            val s = d[o + 4]; val sy = d[o + 5]
            val tr = set.tint[c * 3]; val tg = set.tint[c * 3 + 1]; val tb = set.tint[c * 3 + 2]
            val vc = p.positions.size / 3
            for (v in 0 until vc) {
                // T · R_y · S (three.js: position, rotation.y, scale).
                val lx = p.positions[v * 3] * s; val ly = p.positions[v * 3 + 1] * sy; val lz = p.positions[v * 3 + 2] * s
                val x = tx + cs * lx + sn * lz
                val y = ty + ly
                val z = tz - sn * lx + cs * lz
                interleaved.putFloat(x).putFloat(y).putFloat(z)
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                interleaved.put(unorm(p.colors[v * 4] * tr)).put(unorm(p.colors[v * 4 + 1] * tg))
                    .put(unorm(p.colors[v * 4 + 2] * tb)).put(unorm(p.colors[v * 4 + 3]))
                interleaved.putFloat(p.uvs?.get(v * 2) ?: 0f).putFloat(p.uvs?.get(v * 2 + 1) ?: 0f)
                // Normal: R_y · S⁻¹ · n, normalizada.
                val nx0 = p.normals[v * 3] / s; val ny0 = p.normals[v * 3 + 1] / sy; val nz0 = p.normals[v * 3 + 2] / s
                val nx = cs * nx0 + sn * nz0; val nz = -sn * nx0 + cs * nz0
                val len = sqrt(nx * nx + ny0 * ny0 + nz * nz).takeIf { it > 0f } ?: 1f
                normals.put(nx / len).put(ny0 / len).put(nz / len)
            }
            for (i in p.indices) if (wide) idx.putInt(base + i) else idx.putShort((base + i).toShort())
            base += vc
        }
        interleaved.flip(); normals.flip(); idx.flip()

        val quats = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
        val orientation = SurfaceOrientation.Builder().vertexCount(n).normals(normals).build()
        orientation.getQuatsAsShort(quats.asShortBuffer())
        orientation.destroy()

        val vb = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(n)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3, 0, STRIDE)
            .attribute(VertexAttribute.COLOR, 0, AttributeType.UBYTE4, 12, STRIDE)
            .normalized(VertexAttribute.COLOR)
            .attribute(VertexAttribute.UV0, 0, AttributeType.FLOAT2, 16, STRIDE)
            .attribute(VertexAttribute.UV1, 0, AttributeType.FLOAT2, 16, STRIDE)
            .attribute(VertexAttribute.TANGENTS, 1, AttributeType.SHORT4, 0, 8)
            .normalized(VertexAttribute.TANGENTS)
            .build(engine)
        vb.setBufferAt(engine, 0, interleaved)
        vb.setBufferAt(engine, 1, quats)
        val ib = IndexBuffer.Builder().indexCount(bucket.indices)
            .bufferType(if (wide) IndexBuffer.Builder.IndexType.UINT else IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        ib.setBuffer(engine, idx)
        vertexBuffers += vb; indexBuffers += ib

        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(
                (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
                (maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2,
            ))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, bucket.indices)
            .material(0, bucket.mi)
            .channel(bucket.channel)
            .castShadows(true)
            .receiveShadows(true)
            .build(engine, entity)
        return entity
    }

    private fun unorm(v: Float): Byte = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()

    /** Agrega las mallas a la escena (y las que se armen después mientras siga visible). */
    fun show() {
        if (shown) return
        shown = true
        entities.forEach(scene::addEntity)
    }

    // ---------------------------------------------------------------------------- lectura

    /** Prototipos `proto:<tipo>` del GLB (con el GLB aún mapeado): sirven para varias listas de copias (parque y contexto). */
    fun readPrototypes(glbBuffer: ByteBuffer, asset: FilamentAsset): Map<String, List<Primitive>> =
        PlainMeshes.read(engine, glbBuffer, asset, PROTO_PREFIX)

    /** `instances.bin` ("PQI1") → copias por tipo; [keep] filtra por (tipo, índice en el tipo). */
    private fun readInstances(data: ByteBuffer, keep: ((String, Int) -> Boolean)?): Map<String, Copies> {
        val b = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "PQI1") { "instances.bin: formato desconocido" }
        val types = b.getInt()
        val out = LinkedHashMap<String, Copies>()
        repeat(types) {
            val name = ByteArray(b.getShort().toInt() and 0xFFFF).also { b.get(it) }.toString(Charsets.UTF_8)
            val total = b.getInt()
            val d = FloatArray(total * 7)
            val tint = FloatArray(total * 3)
            var c = 0
            for (j in 0 until total) {
                if (keep != null && !keep(name, j)) { b.position(b.position() + RECORD); continue }
                for (k in 0 until 6) d[c * 7 + k] = b.getFloat()
                tint[c * 3] = SRGB_TO_LINEAR[b.get().toInt() and 0xFF]
                tint[c * 3 + 1] = SRGB_TO_LINEAR[b.get().toInt() and 0xFF]
                tint[c * 3 + 2] = SRGB_TO_LINEAR[b.get().toInt() and 0xFF]
                b.get() // reservado
                c++
            }
            out[name] = Copies(c, d, tint)
        }
        return out
    }

    // ---------------------------------------------------------------------------- limpieza

    /** Destruye las mallas y deja de mostrarlas (antes de destruir el asset: sus MaterialInstance son de gltfio). */
    fun clear() {
        clearMeshes()
        shown = false
    }

    /** Destruye las mallas pero conserva si se muestran (para reconstruir, p. ej. al cambiar la calidad). */
    private fun clearMeshes() {
        for (e in entities) {
            scene.remove(e)
            engine.destroyEntity(e)
            EntityManager.get().destroy(e)
        }
        entities.clear()
        vertexBuffers.forEach { engine.destroyVertexBuffer(it) }
        indexBuffers.forEach { engine.destroyIndexBuffer(it) }
        vertexBuffers.clear(); indexBuffers.clear()
    }
}
