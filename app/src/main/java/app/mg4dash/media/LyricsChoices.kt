package app.mg4dash.media

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * What was set for one song (26/11): the shift of its lyrics (the ▲▼ arrows) and the lyrics chosen in the picker,
 * kept by song (main artist and title without their extras) in filesDir/lyrics_choices.json, apart from the cache
 * so that emptying the cache keeps them.
 */
object LyricsChoices {
    data class Choice(val shiftMs: Long = 0, val lyricsId: Long = 0)

    /** One arrow press: half a second. */
    const val STEP_MS = 500L
    const val MAX_MS = 30_000L

    private var file: File? = null
    private val map = LinkedHashMap<String, Choice>()
    private val _version = MutableStateFlow(0)
    /** Changes each time a song's shift or lyrics change (the card, the karaoke and the pill follow it). */
    val version: StateFlow<Int> = _version

    fun start(context: Context) {
        val f = File(context.applicationContext.filesDir, "lyrics_choices.json")
        synchronized(map) {
            if (file?.path == f.path) return
            file = f; map.clear()
            try {
                if (f.isFile) {
                    val o = JSONObject(f.readText())
                    o.keys().forEach { k -> o.optJSONObject(k)?.let { map[k] = Choice(it.optLong("shift", 0), it.optLong("id", 0)) } }
                }
            } catch (_: Exception) { map.clear() }
        }
    }

    /** The song's key: its main artist and its title without "(feat. …)", "- Remastered" and the like. */
    fun key(artist: String, title: String) = LyricsRepo.key(LyricsRepo.mainArtist(artist), LyricsRepo.clean(title))

    fun get(artist: String, title: String): Choice = synchronized(map) { map[key(artist, title)] } ?: Choice()
    fun shiftMs(artist: String, title: String): Long = get(artist, title).shiftMs
    fun lyricsId(artist: String, title: String): Long = get(artist, title).lyricsId

    /** The song's shift (ms, a negative one shows the lines later); 0 forgets it. */
    fun setShift(artist: String, title: String, ms: Long) = edit(artist, title) { it.copy(shiftMs = ms.coerceIn(-MAX_MS, MAX_MS)) }
    fun setLyrics(artist: String, title: String, id: Long) = edit(artist, title) { it.copy(lyricsId = id.coerceAtLeast(0)) }

    /** The songs something is kept for. */
    fun count(): Int = synchronized(map) { map.size }

    fun clear() {
        synchronized(map) { map.clear() }
        save(); _version.value++
    }

    private fun edit(artist: String, title: String, change: (Choice) -> Choice) {
        if (artist.isBlank() && title.isBlank()) return
        val k = key(artist, title)
        synchronized(map) {
            val c = change(map[k] ?: Choice())
            if (c == Choice()) map.remove(k) else map[k] = c
        }
        save(); _version.value++
    }

    private fun save() {
        val f = synchronized(map) { file } ?: return
        val text = synchronized(map) {
            JSONObject().apply { map.forEach { (k, c) -> put(k, JSONObject().put("shift", c.shiftMs).put("id", c.lyricsId)) } }.toString()
        }
        try {
            val tmp = File(f.path + ".tmp"); tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) { }
    }
}
