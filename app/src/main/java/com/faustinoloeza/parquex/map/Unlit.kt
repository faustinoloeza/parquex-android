package com.faustinoloeza.parquex.map

import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import com.google.android.filament.gltfio.MaterialProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Materiales sin iluminación para lo que dibuja la app (mapa base y rutas). Se toman del ubershader
 * de gltfio ([MaterialProvider]), así no hace falta compilar materiales propios con `matc`.
 *
 * Todos se dibujan **sin prueba ni escritura de profundidad**: su orden lo fijan el canal y la
 * prioridad del renderable (ver los canales en [ParkRenderer]).
 *
 * Formato de vértice común ([vertexBuffer]): búfer 0 = posición (float3, se reescribe), búfer 1 =
 * atributos fijos [STATIC_STRIDE] (uv, color blanco y tangentes), porque el ubershader los espera.
 */
internal class UnlitMaterials(private val provider: MaterialProvider) {

    private val uvmap = IntArray(8)

    /** Color liso (sRGB, 0xRRGGBB). */
    fun color(rgb: Int, label: String): MaterialInstance = create(textured = false, label).also { setColor(it, rgb) }

    /** Textura en `baseColorMap` (sRGB), asignada después con [MaterialInstance.setParameter]. */
    fun textured(label: String): MaterialInstance = create(textured = true, label).also { setColor(it, 0xFFFFFF) }

    /** Como [textured] pero translúcido: el alfa sale del color de vértice ([staticAttributes] con alfas). */
    fun texturedFade(label: String): MaterialInstance =
        create(textured = true, label, blend = true, vertexColors = true).also { setColor(it, 0xFFFFFF) }

    /** Opaco con el color de vértice (búfer 1, ver [staticAttributes] con colores) multiplicado por [rgb]. */
    fun vertexColored(rgb: Int, label: String): MaterialInstance =
        create(textured = false, label, vertexColors = true).also { setColor(it, rgb) }

    /** Color liso translúcido con opacidad [alpha] (0…1). */
    fun blendedColor(rgb: Int, alpha: Float, label: String): MaterialInstance =
        create(textured = false, label, blend = true).also { setPremultiplied(it, rgb, alpha) }

    /** Translúcido con textura de alfa **premultiplicado** (rgb ya multiplicado por alfa), teñida de [rgb]. */
    fun texturedBlend(rgb: Int, label: String): MaterialInstance =
        create(textured = true, label, blend = true).also { setColor(it, rgb) }

    /**
     * `baseColorFactor` premultiplicado: el unlit translúcido del ubershader mezcla con ONE /
     * ONE_MINUS_SRC_ALPHA sin multiplicar el color por el alfa (ver [staticAttributes]).
     */
    private fun setPremultiplied(mi: MaterialInstance, rgb: Int, alpha: Float) {
        val c = Colors.toLinear(
            Colors.RgbType.SRGB,
            ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f,
            null,
        )
        mi.setParameter("baseColorFactor", c[0] * alpha, c[1] * alpha, c[2] * alpha, alpha)
    }

    fun setColor(mi: MaterialInstance, rgb: Int) {
        mi.setParameter(
            "baseColorFactor", Colors.RgbaType.SRGB,
            ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, 1f,
        )
    }

    private fun create(textured: Boolean, label: String, blend: Boolean = false, vertexColors: Boolean = false): MaterialInstance {
        val key = MaterialProvider.MaterialKey().apply {
            unlit = true
            alphaMode = if (blend) 2 else 0 // BLEND / OPAQUE
            hasVertexColors = vertexColors
            hasBaseColorTexture = textured
            baseColorUV = 0
        }
        val mi = provider.createMaterialInstance(key, uvmap, label, null)
            ?: error("El ubershader de gltfio no tiene material unlit")
        mi.setDepthCulling(false)
        mi.setDepthWrite(false)
        mi.setCullingMode(Material.CullingMode.NONE)
        return mi
    }

    companion object {
        /** uv (float2) + color (ubyte4) + tangentes (short4). */
        const val STATIC_STRIDE = 20

        fun vertexBuffer(engine: Engine, vertexCount: Int): VertexBuffer = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(vertexCount)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3, 0, 12)
            .attribute(VertexAttribute.UV0, 1, AttributeType.FLOAT2, 0, STATIC_STRIDE)
            .attribute(VertexAttribute.UV1, 1, AttributeType.FLOAT2, 0, STATIC_STRIDE)
            .attribute(VertexAttribute.COLOR, 1, AttributeType.UBYTE4, 8, STATIC_STRIDE)
            .normalized(VertexAttribute.COLOR)
            .attribute(VertexAttribute.TANGENTS, 1, AttributeType.SHORT4, 12, STATIC_STRIDE)
            .normalized(VertexAttribute.TANGENTS)
            .build(engine)

        /**
         * Búfer 1 para [count] vértices: [uvs] (u, v por vértice; null = ceros), color blanco con
         * alfa [alphas] (0…1 por vértice; null = opaco) y normal +y.
         *
         * El color va **premultiplicado** (rgb = alfa): el unlit translúcido del ubershader mezcla con
         * ONE / ONE_MINUS_SRC_ALPHA sin multiplicar el color por el alfa, y sin esto un alfa 0 sumaría
         * la tesela entera encima del parque.
         */
        fun staticAttributes(count: Int, uvs: FloatArray?, alphas: FloatArray? = null): ByteBuffer {
            val b = directBuffer(count * STATIC_STRIDE)
            for (i in 0 until count) {
                b.putFloat(uvs?.get(i * 2) ?: 0f).putFloat(uvs?.get(i * 2 + 1) ?: 0f)
                val a = ((alphas?.get(i) ?: 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
                repeat(4) { b.put(a) }
                // Cuaternión que lleva +z (normal del marco tangente) a +y: −90° alrededor de x.
                b.putShort((-23170).toShort()).putShort(0).putShort(0).putShort(23170)
            }
            b.flip()
            return b
        }

        /**
         * Búfer 1 con un color sRGB (0xRRGGBB, opaco) por vértice; sin UV. El color de vértice es lineal
         * (como COLOR_0 de glTF): se convierte aquí.
         */
        fun colorAttributes(colors: IntArray): ByteBuffer {
            val b = directBuffer(colors.size * STATIC_STRIDE)
            for (c in colors) {
                b.putFloat(0f).putFloat(0f)
                for (shift in intArrayOf(16, 8, 0)) b.put(SRGB_TO_LINEAR_BYTE[(c shr shift) and 0xFF])
                b.put(0xFF.toByte())
                b.putShort((-23170).toShort()).putShort(0).putShort(0).putShort(23170)
            }
            b.flip()
            return b
        }

        private val SRGB_TO_LINEAR_BYTE = ByteArray(256) { i ->
            val c = i / 255.0
            val l = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            (l * 255 + 0.5).toInt().toByte()
        }

        fun directBuffer(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
