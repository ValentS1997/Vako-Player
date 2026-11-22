package ru.rikko.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class LocalSong(
    val uri: Uri,
    val title: String,
    val fileName: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val durationMs: Long = 0,
    val folder: String = "",
    val volume: String = "selected",
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
)

/** Local library index and user-defined organization. No audio data is copied. */
class LibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("rikko_local", Context.MODE_PRIVATE)

    fun audioPermission(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, audioPermission()
    ) == PackageManager.PERMISSION_GRANTED

    private fun strings(key: String): List<String> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    } catch (_: Exception) { emptyList() }

    private fun putStrings(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    fun addUri(uri: Uri) {
        val list = strings("uris").toMutableList()
        if (uri.toString() !in list) {
            list += uri.toString()
            putStrings("uris", list)
        }
    }

    fun songs(): List<LocalSong> {
        val found = linkedMapOf<String, LocalSong>()
        if (hasAudioPermission()) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.GENRE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.MediaColumns.VOLUME_NAME,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
            )
            try {
                context.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                    null, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val genre = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
                    val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                    val path = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                    val volume = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                    val dateAdded = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                    val dateModified = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(id)
                        )
                        val name = cursor.getString(title)?.takeUnless { it == "<unknown>" || it.isBlank() }
                            ?: cursor.getString(displayName) ?: "Аудиофайл"
                        val folder = cursor.getString(path)?.trim('/') ?: ""
                        val volumeName = if (volume >= 0) cursor.getString(volume) ?: "external_primary" else "external_primary"
                        found[uri.toString()] = LocalSong(
                            uri = uri,
                            title = name,
                            fileName = cursor.getString(displayName).orEmpty(),
                            artist = cursor.getString(artist)?.takeUnless { it == "<unknown>" } ?: "",
                            album = cursor.getString(album)?.takeUnless { it == "<unknown>" } ?: "",
                            genre = cursor.getString(genre)?.takeUnless { it == "<unknown>" } ?: "",
                            durationMs = cursor.getLong(duration).coerceAtLeast(0L),
                            folder = folder,
                            volume = volumeName,
                            dateAdded = if (dateAdded >= 0) cursor.getLong(dateAdded) else 0L,
                            dateModified = if (dateModified >= 0) cursor.getLong(dateModified) else 0L
                        )
                    }
                }
            } catch (_: SecurityException) { /* The user may revoke access while scanning. */ }
        }
        for (value in strings("uris")) {
            if (value in found) continue
            try {
                val uri = Uri.parse(value)
                val mediaUri = uri.authority == MediaStore.AUTHORITY
                val projection = if (mediaUri) arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.GENRE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.MediaColumns.VOLUME_NAME,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                ) else arrayOf(OpenableColumns.DISPLAY_NAME)
                val song = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    fun value(column: String): String? {
                        val index = cursor.getColumnIndex(column)
                        return if (index >= 0) cursor.getString(index) else null
                    }
                    LocalSong(
                        uri = uri,
                        title = value(MediaStore.Audio.Media.TITLE)?.takeUnless { it.isBlank() || it == "<unknown>" }
                            ?: value(OpenableColumns.DISPLAY_NAME) ?: "Аудиофайл",
                        fileName = value(OpenableColumns.DISPLAY_NAME).orEmpty(),
                        artist = value(MediaStore.Audio.Media.ARTIST)?.takeUnless { it == "<unknown>" } ?: "",
                        album = value(MediaStore.Audio.Media.ALBUM)?.takeUnless { it == "<unknown>" } ?: "",
                        genre = value(MediaStore.Audio.Media.GENRE)?.takeUnless { it == "<unknown>" } ?: "",
                        durationMs = value(MediaStore.Audio.Media.DURATION)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                        folder = value(MediaStore.Audio.Media.RELATIVE_PATH)?.trim('/') ?: "",
                        volume = value(MediaStore.MediaColumns.VOLUME_NAME) ?: "selected",
                        dateAdded = value(MediaStore.Audio.Media.DATE_ADDED)?.toLongOrNull() ?: 0L,
                        dateModified = value(MediaStore.Audio.Media.DATE_MODIFIED)?.toLongOrNull() ?: 0L,
                    )
                }
                if (song != null) found[value] = song
            } catch (_: Exception) { /* A file was removed or its grant expired. */ }
        }
        return found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    private fun playlistsJson(): JSONObject = try {
        JSONObject(prefs.getString("playlists", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    fun playlistNames(): List<String> {
        val json = playlistsJson()
        return json.keys().asSequence().toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun createPlaylist(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        val json = playlistsJson()
        if (json.has(clean)) return false
        json.put(clean, JSONArray())
        prefs.edit().putString("playlists", json.toString()).apply()
        return true
    }

    fun createPlaylistFromSongs(name: String, songs: List<LocalSong>): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || songs.isEmpty()) return false
        val json = playlistsJson()
        if (json.has(clean)) return false
        json.put(clean, JSONArray(songs.map { it.uri.toString() }.distinct()))
        prefs.edit().putString("playlists", json.toString()).apply()
        return true
    }

    fun addManyToPlaylist(name: String, songs: List<LocalSong>) {
        val json = playlistsJson()
        if (!json.has(name)) return
        val values = (playlistUris(name) + songs.map { it.uri.toString() }).distinct()
        json.put(name, JSONArray(values))
        prefs.edit().putString("playlists", json.toString()).apply()
    }

    fun renamePlaylist(oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == oldName) return false
        val json = playlistsJson()
        if (!json.has(oldName) || json.has(clean)) return false
        val tracks = json.optJSONArray(oldName) ?: JSONArray()
        json.remove(oldName)
        json.put(clean, tracks)
        prefs.edit().putString("playlists", json.toString()).apply()
        return true
    }

    fun playlistUris(name: String): List<String> {
        val arr = playlistsJson().optJSONArray(name) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    fun addToPlaylist(name: String, uri: Uri) {
        val json = playlistsJson()
        val tracks = playlistUris(name).toMutableList()
        if (uri.toString() !in tracks) tracks += uri.toString()
        json.put(name, JSONArray(tracks))
        prefs.edit().putString("playlists", json.toString()).apply()
    }

    fun removeFromPlaylist(name: String, uri: Uri) {
        val json = playlistsJson()
        json.put(name, JSONArray(playlistUris(name).filterNot { it == uri.toString() }))
        prefs.edit().putString("playlists", json.toString()).apply()
    }

    fun moveInPlaylist(name: String, uri: Uri, direction: Int) {
        val tracks = playlistUris(name).toMutableList()
        val from = tracks.indexOf(uri.toString())
        val to = from + direction
        if (from < 0 || to !in tracks.indices) return
        java.util.Collections.swap(tracks, from, to)
        val json = playlistsJson()
        json.put(name, JSONArray(tracks))
        prefs.edit().putString("playlists", json.toString()).apply()
    }

    fun deletePlaylist(name: String) {
        val json = playlistsJson()
        json.remove(name)
        prefs.edit().putString("playlists", json.toString()).apply()
    }

    fun recentUris(): List<String> = strings("recent")

    fun favoriteUris(): Set<String> = strings("favorites").toSet()

    fun toggleFavorite(uri: Uri): Boolean {
        val value = uri.toString()
        val updated = strings("favorites").toMutableList()
        val added = value !in updated
        if (added) updated.add(value) else updated.remove(value)
        putStrings("favorites", updated)
        return added
    }

    fun addFavorites(songs: List<LocalSong>) {
        putStrings("favorites", (strings("favorites") + songs.map { it.uri.toString() }).distinct())
    }

    fun recordPlay(uri: Uri) {
        putStrings("recent", (listOf(uri.toString()) + strings("recent").filterNot { it == uri.toString() }).take(100))
    }
}
