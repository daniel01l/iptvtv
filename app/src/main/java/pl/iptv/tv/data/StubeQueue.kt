package pl.iptv.tv.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pozycja kolejki odtwarzania „Następne” – film STube ([KIND_YT], [ref] = id filmu) albo kanał telewizji na żywo
 * ([KIND_IPTV], [ref] = klucz kanału). Kolejność – [position] rosnąco.
 */
@Entity(tableName = "play_queue", indices = [Index("position")])
data class QueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val ref: String,
    val title: String = "",
    val subtitle: String = "",
    val thumb: String = "",
    val duration: String = "",
    val position: Int = 0,
    val addedAt: Long = 0,
) {
    val isVideo get() = kind == KIND_YT
    fun toVideo() = YtVideo(ref, title, subtitle, thumb, duration = duration)

    companion object {
        const val KIND_YT = "yt"; const val KIND_IPTV = "iptv"
        fun of(v: YtVideo) = QueueItem(kind = KIND_YT, ref = v.id, title = v.title, subtitle = v.channel, thumb = v.thumb, duration = v.duration)
        fun of(ch: Channel) = QueueItem(kind = KIND_IPTV, ref = ch.key, title = ch.name, subtitle = ch.group, thumb = ch.logo)
    }
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM play_queue ORDER BY position, id")
    suspend fun all(): List<QueueItem>

    @Insert suspend fun insert(q: QueueItem): Long

    @Query("UPDATE play_queue SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("SELECT COALESCE(MIN(position), 0) FROM play_queue") suspend fun minPosition(): Int
    @Query("SELECT COALESCE(MAX(position), 0) FROM play_queue") suspend fun maxPosition(): Int

    @Query("DELETE FROM play_queue WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM play_queue WHERE kind = :kind AND ref = :ref") suspend fun deleteRef(kind: String, ref: String)
    @Query("DELETE FROM play_queue") suspend fun clear()
}

/**
 * Kolejka odtwarzania (Room): wspólna dla filmów STube i kanałów TV na żywo. Bez duplikatów (ponowne dodanie przenosi pozycję).
 * Postęp filmów – z historii oglądania ([StubeHistory]), program kanału – z EPG.
 * Zmiany ([version]) odświeżają otwarte panele.
 */
object StubeQueue {
    const val MAX = 200
    @Volatile var version = 0; private set
    /** Ostatni stan (synchronicznie – liczba na przycisku, status dla telefonu). */
    @Volatile var cached: List<QueueItem> = emptyList(); private set

    private fun dao() = StubeHistory.db()?.queue()

    suspend fun items(): List<QueueItem> = dao()?.let { d -> withContext(Dispatchers.IO) { runCatching { d.all() }.getOrDefault(emptyList()) } }
        ?.also { cached = it } ?: emptyList()

    /** Dodaje na koniec (albo na początek: [next] = „Odtwórz jako następne”). */
    suspend fun add(item: QueueItem, next: Boolean = false, now: Long = System.currentTimeMillis()): Boolean {
        val d = dao() ?: return false
        withContext(Dispatchers.IO) {
            d.deleteRef(item.kind, item.ref)
            val pos = if (next) d.minPosition() - 1 else d.maxPosition() + 1
            d.insert(item.copy(id = 0, position = pos, addedAt = now))
            val all = d.all()
            if (all.size > MAX) all.drop(MAX).forEach { d.delete(it.id) }
        }
        changed(); return true
    }

    /** Podmienia całą kolejkę na [items] (playlista otwarta od razu w odtwarzaczu) – jedna transakcja, bez limitu [MAX] ponad listę. */
    suspend fun replaceAll(items: List<QueueItem>, now: Long = System.currentTimeMillis()) {
        val d = dao() ?: return
        withContext(Dispatchers.IO) {
            d.clear()
            items.take(MAX).forEachIndexed { i, q -> d.insert(q.copy(id = 0, position = i, addedAt = now)) }
        }
        changed()
    }

    /** Losowa kolejność pozostałych pozycji (przycisk „Losowo”). */
    suspend fun shuffle() {
        val d = dao() ?: return
        withContext(Dispatchers.IO) { d.all().shuffled().forEachIndexed { i, q -> d.setPosition(q.id, i) } }
        changed()
    }

    suspend fun remove(id: Long) { dao()?.let { d -> withContext(Dispatchers.IO) { d.delete(id) } }; changed() }
    suspend fun removeRef(kind: String, ref: String) { dao()?.let { d -> withContext(Dispatchers.IO) { d.deleteRef(kind, ref) } }; changed() }
    suspend fun clear() { dao()?.let { d -> withContext(Dispatchers.IO) { d.clear() } }; changed() }

    /** Przesunięcie o [delta] miejsc (▲ / ▼ w menu pozycji). */
    suspend fun move(id: Long, delta: Int) {
        val d = dao() ?: return
        withContext(Dispatchers.IO) {
            val l = d.all().toMutableList()
            val i = l.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@withContext
            val j = (i + delta).coerceIn(0, l.size - 1)
            if (i == j) return@withContext
            l.add(j, l.removeAt(i))
            l.forEachIndexed { p, q -> d.setPosition(q.id, p) }
        }
        changed()
    }

    /** Następna pozycja do odtworzenia (zdjęta z kolejki); [skipRef] – bieżący film nie gra drugi raz. */
    suspend fun pop(skipRef: String? = null, random: Boolean = false): QueueItem? {
        val d = dao() ?: return null
        val next = withContext(Dispatchers.IO) {
            if (skipRef != null) d.deleteRef(QueueItem.KIND_YT, skipRef)
            d.all().let { l -> if (random) l.randomOrNull() else l.firstOrNull() }?.also { d.delete(it.id) }
        }
        changed(); return next
    }

    private suspend fun changed() { version++; items() }
}
