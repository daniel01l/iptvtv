package pl.iptv.tv.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Przetworzone EPG w formacie binarnym – wczytywanie przy starcie wielokrotnie szybsze niż XML. */
object EpgBinary {
    private const val MAGIC = 0x45504732   // "EPG2"

    /** Podpis zestawu kanałów – zmiana listy/mapowania wymaga ponownego przetworzenia XML. */
    fun signature(chs: List<Channel>): String = chs.joinToString("\n") { "${it.tvgId}|${it.name}|${it.tvgName}|${it.origName}|${it.epgOverride}" }
        .hashCode().toString() + ":" + chs.size

    private fun DataOutputStream.str(s: String) = writeUTF(if (s.length > 15_000) s.take(15_000) else s)
    private fun DataOutputStream.map(m: Map<String, String>) { writeInt(m.size); m.forEach { (k, v) -> str(k); str(v) } }
    private fun DataInputStream.map(): Map<String, String> { val n = readInt(); return HashMap<String, String>(n * 2).apply { repeat(n) { put(readUTF(), readUTF()) } } }

    fun write(f: File, d: EpgData, sig: String) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { o ->
            o.writeInt(MAGIC); o.str(sig)
            o.map(d.idByLower); o.map(d.idByName); o.map(d.icons); o.map(d.channelNames)
            o.writeInt(d.programmes.size)
            d.programmes.forEach { (id, l) ->
                o.str(id); o.writeInt(l.size)
                l.forEach { p -> o.writeLong(p.start); o.writeLong(p.end); o.str(p.title); o.str(p.desc); o.str(p.category); o.str(p.subTitle); o.str(p.episode) }
            }
        }
        tmp.renameTo(f)
    }

    /** null, gdy pliku brak lub dotyczy innego zestawu kanałów. */
    fun read(f: File, sig: String, from: Long, to: Long): EpgData? {
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(f.inputStream(), 256 * 1024)).use { i ->
                if (i.readInt() != MAGIC || i.readUTF() != sig) return null
                val a = i.map(); val b = i.map(); val c = i.map(); val n = i.map()
                val cnt = i.readInt()
                val progs = HashMap<String, List<Programme>>(cnt * 2)
                repeat(cnt) {
                    val id = i.readUTF(); val k = i.readInt(); val l = ArrayList<Programme>(k)
                    repeat(k) {
                        val p = Programme(i.readLong(), i.readLong(), i.readUTF(), i.readUTF(), i.readUTF().intern(), i.readUTF(), i.readUTF())
                        if (p.end >= from && p.start <= to) l += p
                    }
                    progs[id] = l
                }
                EpgData(progs, a, b, c, n)
            }
        }.getOrNull()
    }
}
