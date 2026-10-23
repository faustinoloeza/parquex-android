package com.faustinoloeza.parquex.map

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.gltfio.FilamentAsset
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mallas que la app lee directo del binario del GLB: los prototipos `proto:*` de [InstancedModels] y las
 * piezas animadas `anim:*` de [AnimatedModels]. El exportador las deja en float32, sin meshopt ni
 * cuantización y sin transformación de nodo; se reutilizan los MaterialInstance que crea gltfio.
 */
internal object PlainMeshes {

    /** Primitiva ya leída del GLB. */
    class Primitive internal constructor(
        /** Nombre del nodo sin el prefijo. */
        val type: String,
        /** Índice del material glTF (agrupa las primitivas que comparten material). */
        val material: Int,
        val positions: FloatArray,
        val normals: FloatArray,
        /** RGBA lineal por vértice. */
        val colors: FloatArray,
        val uvs: FloatArray?,
        val indices: IntArray,
        val materialInstance: MaterialInstance,
    ) {
        val vertexCount get() = positions.size / 3
    }

    /**
     * Nodos `<prefix>…` del GLB → sus primitivas de triángulos, por nombre sin el prefijo. Leer con el GLB
     * aún mapeado (antes de `releaseSourceData`).
     */
    fun read(engine: Engine, glbBuffer: ByteBuffer, asset: FilamentAsset, prefix: String): Map<String, List<Primitive>> {
        val glb = glbBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = glb.getInt(12)
        val jsonBytes = ByteArray(jsonLength)
        glb.position(20); glb.get(jsonBytes)
        val json = JSONObject(String(jsonBytes, Charsets.UTF_8))
        val binStart = 20 + jsonLength + 8

        val rcm = engine.renderableManager
        val entityByName = asset.entities.associateBy { asset.getName(it) }
        val nodes = json.optJSONArray("nodes") ?: return emptyMap()
        val meshes = json.getJSONArray("meshes")
        val out = HashMap<String, List<Primitive>>()
        for (n in 0 until nodes.length()) {
            val node = nodes.getJSONObject(n)
            val name = node.optString("name")
            if (!name.startsWith(prefix) || !node.has("mesh")) continue
            val type = name.removePrefix(prefix)
            val entity = entityByName[name] ?: continue
            val ri = rcm.getInstance(entity)
            if (ri == 0) continue
            val prims = meshes.getJSONObject(node.getInt("mesh")).getJSONArray("primitives")
            val list = ArrayList<Primitive>()
            for (k in 0 until prims.length()) {
                val p = prims.getJSONObject(k)
                if (p.optInt("mode", 4) != 4) continue
                val attrs = p.getJSONObject("attributes")
                val positions = readFloats(json, glb, binStart, attrs.getInt("POSITION"))
                val vertexCount = positions.size / 3
                val normals = if (attrs.has("NORMAL")) readFloats(json, glb, binStart, attrs.getInt("NORMAL"))
                    else FloatArray(vertexCount * 3) { if (it % 3 == 1) 1f else 0f }
                val colors = if (attrs.has("COLOR_0")) toRgba(readFloats(json, glb, binStart, attrs.getInt("COLOR_0")), vertexCount)
                    else FloatArray(vertexCount * 4) { 1f }
                val uvs = if (attrs.has("TEXCOORD_0")) readFloats(json, glb, binStart, attrs.getInt("TEXCOORD_0")) else null
                val indices = if (p.has("indices")) readIndices(json, glb, binStart, p.getInt("indices")) else IntArray(vertexCount) { it }
                list += Primitive(type, p.optInt("material", -1), positions, normals, colors, uvs, indices,
                    rcm.getMaterialInstanceAt(ri, k))
            }
            out[type] = list
        }
        return out
    }

    private fun toRgba(c: FloatArray, vertexCount: Int): FloatArray {
        val comps = c.size / vertexCount
        if (comps == 4) return c
        return FloatArray(vertexCount * 4) { i -> if (i % 4 == 3) 1f else c[i / 4 * comps + i % 4] }
    }

    /** Accessor como floats (FLOAT o enteros normalizados), respetando byteStride. */
    private fun readFloats(json: JSONObject, glb: ByteBuffer, binStart: Int, accessorIndex: Int): FloatArray {
        val a = json.getJSONArray("accessors").getJSONObject(accessorIndex)
        val bv = json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        val comps = when (a.getString("type")) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; else -> error("tipo ${a.getString("type")}") }
        val ct = a.getInt("componentType")
        val size = when (ct) { 5126, 5125 -> 4; 5123, 5122 -> 2; else -> 1 }
        val count = a.getInt("count")
        val stride = bv.optInt("byteStride", comps * size).takeIf { it > 0 } ?: (comps * size)
        val start = binStart + bv.optInt("byteOffset") + a.optInt("byteOffset")
        val normalized = a.optBoolean("normalized")
        return FloatArray(count * comps) { i ->
            val at = start + (i / comps) * stride + (i % comps) * size
            when (ct) {
                5126 -> glb.getFloat(at)
                5123 -> (glb.getShort(at).toInt() and 0xFFFF).let { if (normalized) it / 65535f else it.toFloat() }
                5121 -> (glb.get(at).toInt() and 0xFF).let { if (normalized) it / 255f else it.toFloat() }
                5122 -> glb.getShort(at).toFloat().let { if (normalized) (it / 32767f).coerceAtLeast(-1f) else it }
                5120 -> glb.get(at).toFloat().let { if (normalized) (it / 127f).coerceAtLeast(-1f) else it }
                else -> error("componentType $ct")
            }
        }
    }

    private fun readIndices(json: JSONObject, glb: ByteBuffer, binStart: Int, accessorIndex: Int): IntArray {
        val a = json.getJSONArray("accessors").getJSONObject(accessorIndex)
        val bv = json.getJSONArray("bufferViews").getJSONObject(a.getInt("bufferView"))
        val start = binStart + bv.optInt("byteOffset") + a.optInt("byteOffset")
        val count = a.getInt("count")
        return when (a.getInt("componentType")) {
            5125 -> IntArray(count) { glb.getInt(start + it * 4) }
            5123 -> IntArray(count) { glb.getShort(start + it * 2).toInt() and 0xFFFF }
            else -> IntArray(count) { glb.get(start + it).toInt() and 0xFF }
        }
    }
}
