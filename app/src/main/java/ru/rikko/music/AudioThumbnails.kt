package ru.rikko.music

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Local artwork is requested only while a grid tile is mounted. */
internal object AudioThumbnails {
    private val slots = Semaphore(3)
    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Composable
    fun Artwork(song: LocalSong, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val key = "${song.uri}:${song.dateModified}:${song.dateAdded}"
        val artwork by produceState<Bitmap?>(initialValue = memory.get(key), key1 = key) {
            value = withContext(Dispatchers.IO) {
                memory.get(key) ?: slots.withPermit {
                    val cancellation = CancellationSignal()
                    val handle = currentCoroutineContext().job.invokeOnCompletion { cancellation.cancel() }
                    try {
                        context.contentResolver.loadThumbnail(song.uri, Size(256, 256), cancellation)
                            .also { memory.put(key, it) }
                    } catch (_: Exception) {
                        null
                    } finally {
                        handle.dispose()
                    }
                }
            }
        }
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (artwork != null) Image(artwork!!.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("♫", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineLarge)
        }
    }
}
