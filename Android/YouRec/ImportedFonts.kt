package yourec

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/// A face of a font file imported into a project, after the iPhone's ImportedFontFace.
/// Its id names the content, not the file: `import:<sha256>:<index>:<PostScript name>`.
data class ImportedFontFace(val id: String, val name: String, val weights: List<Int>, val defaultWeight: Int) {
    class Reference private constructor(val digest: String, val index: Int, val postScriptName: String) {
        /// The project keeps one immutable file per font, collections included.
        val filename: String get() = "font-$digest.sfnt"
        companion object {
            fun of(id: String): Reference? {
                if (!id.startsWith("import:")) return null
                val parts = id.split(":", limit = 4)
                if (parts.size != 4 || parts.any { it.isEmpty() }) return null
                val digest = parts[1]
                if (digest.length != 64 || !digest.all { it in '0'..'9' || it in 'a'..'f' }) return null
                val index = parts[2].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                return Reference(digest, index, parts[3])
            }
        }
    }
}

class FontError(message: String) : Exception(message)

/// Imported fonts live in the project folder as `font-<sha256>.sfnt`, the name the
/// iPhone app gives them, so a project moves between the two with its fonts.
object ImportedFonts {
    val extensions = setOf("ttf", "otf", "ttc", "otc")
    const val MAXIMUM_BYTES = 64 * 1024 * 1024
    private const val WEIGHT_AXIS = 0x77676874 // 'wght'
    private const val MAXIMUM_HINT_BYTES = 140
    private val cache = HashMap<String, Pair<Long, List<ImportedFontFace>?>>()

    fun digest(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /// The faces of a font file, named as the iPhone app names them. A file that is not a font throws.
    fun faces(data: ByteArray, digest: String = digest(data)): List<ImportedFontFace> = try {
        val bytes = ByteBuffer.wrap(data)
        val starts = if (bytes.getInt(0) == 0x74746366) (0 until bytes.getInt(8)).map { bytes.getInt(12 + 4 * it) } else listOf(0) // 'ttcf'
        if (starts.isEmpty() || starts.size > 1000) throw FontError("This file could not be read as a font.")
        starts.mapIndexed { index, start -> face(bytes, start, index, digest) }
    } catch (e: FontError) { throw e } catch (e: Exception) { throw FontError("This file could not be read as a font.") }

    private fun face(bytes: ByteBuffer, start: Int, index: Int, digest: String): ImportedFontFace {
        val tables = HashMap<String, Int>()
        val count = bytes.getShort(start + 4).toInt() and 0xFFFF
        for (i in 0 until count) {
            val record = start + 12 + 16 * i
            tables[String(ByteArray(4) { bytes.get(record + it) }, Charsets.ISO_8859_1)] = bytes.getInt(record + 8)
        }
        val names = tables["name"]?.let { names(bytes, it) } ?: emptyMap()
        val postScript = names[6]?.takeIf { it.isNotEmpty() } ?: throw FontError("This file could not be read as a font.")
        var weights = emptyList<Int>(); var weight = 400
        tables["fvar"]?.let { weightAxis(bytes, it) }?.let { (lo, normal, hi) ->
            val low = maxOf(100, lo); val high = minOf(1000, hi)
            if (low <= high) {
                weight = normal.coerceIn(low, high)
                weights = (listOf(low, high, weight) + (100..1000 step 100).filter { it in low..high }).distinct().sorted()
            }
        }
        val hint = postScript.toByteArray().let { it.copyOf(minOf(it.size, MAXIMUM_HINT_BYTES)) }.decodeToString()
        return ImportedFontFace("import:$digest:$index:$hint", names[4]?.takeIf { it.isNotEmpty() } ?: postScript, weights, weight)
    }

    /// Name records by id, Windows Unicode (English first) before Unicode before Mac Roman.
    private fun names(bytes: ByteBuffer, table: Int): Map<Int, String> {
        val count = bytes.getShort(table + 2).toInt() and 0xFFFF
        val strings = table + (bytes.getShort(table + 4).toInt() and 0xFFFF)
        val found = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val record = table + 6 + 12 * i
            fun u16(at: Int) = bytes.getShort(record + at).toInt() and 0xFFFF
            val platform = u16(0); val encoding = u16(2); val language = u16(4); val id = u16(6)
            val rank = when {
                platform == 3 && (encoding == 1 || encoding == 10) -> if (language == 0x409) 0 else 1
                platform == 0 -> 2
                platform == 1 && encoding == 0 -> 3
                else -> continue
            }
            if ((found[id]?.first ?: Int.MAX_VALUE) <= rank) continue
            val raw = ByteArray(u16(8)) { bytes.get(strings + u16(10) + it) }
            found[id] = rank to String(raw, if (rank == 3) Charsets.ISO_8859_1 else Charsets.UTF_16BE)
        }
        return found.mapValues { it.value.second }
    }

    /// The weight axis's minimum, default and maximum, truncated as the iPhone reads them.
    private fun weightAxis(bytes: ByteBuffer, table: Int): Triple<Int, Int, Int>? {
        val axes = table + (bytes.getShort(table + 4).toInt() and 0xFFFF)
        val count = bytes.getShort(table + 8).toInt() and 0xFFFF
        val size = bytes.getShort(table + 10).toInt() and 0xFFFF
        for (i in 0 until count) {
            val axis = axes + size * i
            if (bytes.getInt(axis) != WEIGHT_AXIS) continue
            fun fixed(at: Int) = (bytes.getInt(axis + at) / 65536.0).toInt()
            return Triple(fixed(4), fixed(8), fixed(12))
        }
        return null
    }

    /// Copy a font into the project folder and return its faces. Importing the same file again changes nothing.
    fun ingest(data: ByteArray, filename: String, folder: File): List<ImportedFontFace> {
        if (filename.substringAfterLast('.', "").lowercase() !in extensions) throw FontError("Choose a TTF, OTF, TTC, or OTC font file.")
        val digest = digest(data)
        val faces = faces(data, digest)
        val target = File(folder, "font-$digest.sfnt")
        if (!target.exists()) {
            val partial = File(folder, ".font-$digest.partial")
            partial.writeBytes(data)
            if (!partial.renameTo(target)) { partial.delete(); throw FontError("The font could not be saved.") }
        }
        return faces
    }

    /// The faces of a project's font file, or null when it is missing, damaged or not that content.
    fun installed(file: File): List<ImportedFontFace>? = synchronized(cache) {
        cache[file.path]?.takeIf { it.first == file.length() }?.let { return it.second }
        val digest = file.name.removePrefix("font-").removeSuffix(".sfnt")
        val faces = try {
            if (!file.isFile || file.length() > MAXIMUM_BYTES) null
            else file.readBytes().takeIf { digest(it) == digest }?.let { faces(it, digest) }
        } catch (e: Exception) { null }
        cache[file.path] = file.length() to faces
        faces
    }

    /// Every face imported into the project in `folder`, by name.
    fun faces(folder: File): List<ImportedFontFace> = (folder.listFiles() ?: emptyArray())
        .filter { it.name.startsWith("font-") && it.name.endsWith(".sfnt") }
        .flatMap { installed(it) ?: emptyList() }.sortedWith(compareBy({ it.name }, { it.id }))

    /// The file and face an id names, only when the project holds exactly that face.
    fun resolve(id: String, folder: File?): Pair<File, ImportedFontFace>? {
        val ref = ImportedFontFace.Reference.of(id) ?: return null
        val file = File(folder ?: return null, ref.filename)
        val face = installed(file)?.getOrNull(ref.index)?.takeIf { it.id == id } ?: return null
        return file to face
    }

    /// The name shown for a face; a missing one shows its PostScript name.
    fun label(id: String, folder: File?): String? =
        resolve(id, folder)?.second?.name ?: ImportedFontFace.Reference.of(id)?.postScriptName
}
