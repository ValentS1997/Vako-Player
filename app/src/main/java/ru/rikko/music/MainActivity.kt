package ru.rikko.music

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.app.Activity
import android.Manifest
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.app.RecoverableSecurityException
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.media.audiofx.Equalizer
import android.graphics.BitmapFactory
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt


class MainActivity : ComponentActivity() {
    @Volatile private var closing = false
    private val backgroundExecutor = ThreadPoolExecutor(
        3, 3, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(24)
    )

    private fun backgroundTask(block: () -> Unit) {
        try {
            backgroundExecutor.execute {
                if (!Thread.currentThread().isInterrupted) block()
            }
        } catch (_: RejectedExecutionException) {
            status = "Слишком много фоновых задач. Подождите завершения текущих."
        }
    }

    private fun postIfActive(action: () -> Unit) {
        if (closing || isDestroyed || isFinishing) return
        runOnUiThread {
            if (!closing && !isDestroyed && !isFinishing) action()
        }
    }

    private sealed interface QueueItem {
        data class Local(val song: LocalSong) : QueueItem
        data class Remote(val track: Track) : QueueItem
    }

    private lateinit var tokenStore: TokenStore
    private lateinit var libraryStore: LibraryStore
    private lateinit var appPreferences: AppPreferences
    private lateinit var savedQueueStore: SavedQueueStore
    private var savedQueueNames by mutableStateOf<List<String>>(emptyList())
    private var showSaveQueue by mutableStateOf(false)
    private var showSavedQueues by mutableStateOf(false)
    private var showClearQueue by mutableStateOf(false)
    private var saveQueueName by mutableStateOf("")
    private var saveQueueError by mutableStateOf("")
    private var pendingDeleteQueueName by mutableStateOf("")
    private var pendingDeleteRemotePlaylist by mutableStateOf<MusicRepository.UserPlaylist?>(null)
    private var queueRestored by mutableStateOf(false)
    private var yandexEnabled by mutableStateOf(true)
    private var videoEnabled by mutableStateOf(true)
    private var playlistsEnabled by mutableStateOf(true)
    private var foldersEnabled by mutableStateOf(true)
    private var recentEnabled by mutableStateOf(true)
    private var albumsEnabled by mutableStateOf(true)
    private var artistsEnabled by mutableStateOf(true)
    private var genresEnabled by mutableStateOf(true)
    private var favoritesEnabled by mutableStateOf(true)
    private var albumGridEnabled by mutableStateOf(false)
    private var audioViewMode by mutableStateOf(0)
    private var favoriteUris by mutableStateOf<Set<String>>(emptySet())
    private var selectedBrowseGroup by mutableStateOf("")
    private var browseSearch by mutableStateOf("")
    private var downloadQuality by mutableStateOf(Quality.AUTO)
    private var settingsPage by mutableStateOf(0)
    private var startTab by mutableStateOf(0)
    private var tapAction by mutableStateOf(0)
    private var folderShowFileName by mutableStateOf(false)
    private var keepScreenOn by mutableStateOf(false)
    private var compactRows by mutableStateOf(true)
    private var coverCrop by mutableStateOf(false)
    private var themeBase by mutableStateOf(2)
    private var themeAccent by mutableStateOf(0)
    private var themeCustom by mutableStateOf(false)
    private var themeBackgroundHex by mutableStateOf("#101314")
    private var themeSurfaceHex by mutableStateOf("#1C2021")
    private var themeTextHex by mutableStateOf("#F0F3F1")
    private var themeAccentHex by mutableStateOf("#8FCF52")
    private var pendingRenameSong by mutableStateOf<LocalSong?>(null)
    private var pendingRenameFolder by mutableStateOf<FolderNode?>(null)
    private var pendingMoveSong by mutableStateOf<LocalSong?>(null)
    private var pendingMoveSongs by mutableStateOf<List<LocalSong>>(emptyList())
    private var pendingMoveFolder by mutableStateOf<FolderNode?>(null)
    private var pendingInfoSong by mutableStateOf<LocalSong?>(null)
    private var moveSearch by mutableStateOf("")
    private var editName by mutableStateOf("")
    private var editError by mutableStateOf("")
    private var pendingMediaEdits = emptyList<MediaEdit>()
    private var failedMediaEdits by mutableStateOf<List<MediaEdit>>(emptyList())
    private var pendingMediaDescription = ""
    private var token by mutableStateOf("")
    private var tokenInput by mutableStateOf("")
    private var query by mutableStateOf("")
    private var linkInput by mutableStateOf("")
    private var activeTab by mutableStateOf(0)
    private var moduleOrder by mutableStateOf(listOf(0, 1, 2, 3))
    private var navigationVisible by mutableStateOf(true)
    private var navigationTick by mutableStateOf(0)
    private var draggedModule by mutableStateOf<Int?>(null)
    private var navigationDragOffset = 0f
    private var results by mutableStateOf<List<Track>>(emptyList())
    private var artists by mutableStateOf<List<MusicRepository.Artist>>(emptyList())
    private var selectedArtist by mutableStateOf("")
    private var searchResultsBeforeArtist = emptyList<Track>()
    private var playlist by mutableStateOf<List<Track>>(emptyList())
    private var localSongs by mutableStateOf<List<LocalSong>>(emptyList())
    private var recentUris by mutableStateOf<List<String>>(emptyList())
    private var playlistNames by mutableStateOf<List<String>>(emptyList())
    private var libraryView by mutableStateOf(0)
    private var remoteView by mutableStateOf(0)
    private lateinit var autoCollectionStore: AutoCollectionStore
    private var autoCollections by mutableStateOf<List<AutoCollection>>(emptyList())
    private var selectedAutoCollection by mutableStateOf<AutoCollection?>(null)
    private var autoCollectionName by mutableStateOf("")
    private var autoRenameInput by mutableStateOf("")
    private var autoCollectionBusy by mutableStateOf(false)
    private var ownPlaylists by mutableStateOf<List<MusicRepository.UserPlaylist>>(emptyList())
    private var selectedRemotePlaylist by mutableStateOf<MusicRepository.UserPlaylist?>(null)
    private var remotePlaylistTracks by mutableStateOf<List<Track>>(emptyList())
    private var showLikedTracks by mutableStateOf(false)
    private var likedTracks by mutableStateOf<List<Track>>(emptyList())
    private var likedLoaded by mutableStateOf(false)
    private var likedLoading by mutableStateOf(false)
    private var likedProgress by mutableStateOf("")
    private var renameInput by mutableStateOf("")
    private var includeSubfolders by mutableStateOf(false)
    private var selectedPlaylist by mutableStateOf("")
    private var selectedFolder by mutableStateOf("")
    private var newPlaylistName by mutableStateOf("")
    private var localRenameInput by mutableStateOf("")
    private var localFilter by mutableStateOf("")
    private var sortMode by mutableStateOf(0)
    private var sortDescending by mutableStateOf(false)
    private var libraryRevision by mutableStateOf(0)
    private var scanning by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var downloading by mutableStateOf(false)
    private var progress by mutableStateOf(0f)
    private var status by mutableStateOf("")
    private var checkingUpdate by mutableStateOf(false)
    private var updateMessage by mutableStateOf("")
    private var updateUrl by mutableStateOf("")
    private var currentTitle by mutableStateOf("")
    private var currentArtist by mutableStateOf("")
    private var playing by mutableStateOf(false)
    private var prepared by mutableStateOf(false)
    private var currentPositionMs by mutableStateOf(0)
    private var durationMs by mutableStateOf(0)
    private var showNowPlaying by mutableStateOf(false)
    private var repeatMode by mutableStateOf(0)
    private var shuffle by mutableStateOf(false)
    private var queue by mutableStateOf<List<QueueItem>>(emptyList())
    private var queueIndex by mutableStateOf(-1)
    private var queueSourcePlaylist by mutableStateOf<MusicRepository.UserPlaylist?>(null)
    private var showQueue by mutableStateOf(false)
    private var showSound by mutableStateOf(false)
    private var selectedLocalUris by mutableStateOf<Set<String>>(emptySet())
    private var selectedRemoteTracks by mutableStateOf<List<Track>>(emptyList())
    private var playlistPickerTracks by mutableStateOf<List<Track>>(emptyList())
    private var localPlaylistPickerSongs by mutableStateOf<List<LocalSong>>(emptyList())
    private var playlistSearch by mutableStateOf("")
    private var localPickerName by mutableStateOf("")
    private var pendingFolderPlaylist by mutableStateOf<FolderNode?>(null)
    private var folderPlaylistName by mutableStateOf("")
    private var folderPlaylistError by mutableStateOf("")
    private var shuffleRemaining = mutableListOf<Int>()
    private var shuffleHistory = mutableListOf<Int>()
    private var playbackGeneration = 0
    private var bulkDownloading by mutableStateOf(false)
    private var bulkDone by mutableStateOf(0)
    private var bulkTotal by mutableStateOf(0)
    @Volatile private var cancelBulk = false
    private var visualizer: Visualizer? = null
    private var visualizerVisible by mutableStateOf(false)
    private var visualizerStyle by mutableStateOf(0)
    private var miniSpectrumEnabled by mutableStateOf(true)
    private var waveform by mutableStateOf(ByteArray(0))
    private var spectrum by mutableStateOf(ByteArray(0))
    private var playbackService: PlaybackService? = null
    private var scanRequested = false
    private var pendingExternalAudio: LocalSong? = null
    private val player: MediaPlayer? get() = playbackService?.player
    private val playbackListener = object : PlaybackService.Listener {
        override fun onPrepared(duration: Int) {
            prepared = true
            durationMs = duration
            playing = playbackService?.player?.isPlaying == true
            if (eqEnabled) startEqualizer()
            status = ""
            if (visualizerVisible || miniSpectrumEnabled) startVisualizer()
        }
        override fun onCompletion() {
            playing = false
            if (repeatMode == 2) {
                playbackService?.seekTo(0)
                playbackService?.toggle()
                playing = true
            } else nextTrack(automatic = true)
        }
        override fun onError() { playing = false; status = "Ошибка воспроизведения" }
        override fun onPlayRequest() {
            if (queue.isNotEmpty()) playQueueItem()
        }
        override fun onPlaybackStateChanged(playing: Boolean) {
            this@MainActivity.playing = playing
            if (playing && (miniSpectrumEnabled || visualizerVisible) && visualizer == null) startVisualizer()
            else if (!playing) stopVisualizer()
            if (sleepDeadline > 0L && playbackService?.sleepDeadline == 0L) {
                sleepDeadline = 0L
                sleepRemainingMs = 0L
            }
        }
        override fun onNext() { nextTrack() }
        override fun onPrevious() { previousTrack() }
    }
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? PlaybackService.LocalBinder)?.service ?: return
            playbackService = service
            service.listener = playbackListener
            sleepDeadline = service.sleepDeadline
            sleepRemainingMs = (sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            pendingExternalAudio?.let { song ->
                pendingExternalAudio = null
                playLocal(song, listOf(song))
            }
            if (service.title.isNotBlank()) {
                currentTitle = service.title
                currentArtist = service.artist
                prepared = service.prepared
                playing = service.player?.isPlaying == true
                durationMs = if (service.prepared) service.player?.duration ?: 0 else 0
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            sleepDeadline = 0L
            sleepRemainingMs = 0L
            playing = false
        }
    }
    private var cover by mutableStateOf<ImageBitmap?>(null)
    private var equalizer: Equalizer? = null
    private var eqEnabled by mutableStateOf(false)
    private var eqLevels by mutableStateOf<List<Short>>(emptyList())
    private var eqFrequencies by mutableStateOf<List<Int>>(emptyList())
    private var eqRange by mutableStateOf(Pair<Short, Short>(-1500, 1500))
    private var sleepDeadline by mutableStateOf(0L)
    private var sleepRemainingMs by mutableStateOf(0L)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            libraryStore.addUri(uri)
            refreshLibrary()
        }
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refreshLibrary() else status = "Доступ к аудио не предоставлен; выбранные файлы всё равно доступны"
    }

    private val visualizerPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVisualizer() else {
            status = "Для визуализации Android требует разрешение на аудио"
        }
    }

    private val mediaWritePermission = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) applyMediaEdits()
        else status = "Изменение файлов отменено"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, PlaybackService::class.java), playbackConnection, Context.BIND_AUTO_CREATE)
        tokenStore = TokenStore(this)
        libraryStore = LibraryStore(this)
        appPreferences = AppPreferences(this)
        autoCollectionStore = AutoCollectionStore(this)
        autoCollections = autoCollectionStore.all()
        autoCollectionName = "Подборка ${autoCollections.size + 1}"
        val visualizerPage = appPreferences.number("visualizer_page", 0).coerceIn(0, 6)
        visualizerVisible = visualizerPage > 0
        visualizerStyle = (visualizerPage - 1).coerceAtLeast(0)
        miniSpectrumEnabled = appPreferences.enabled("mini_spectrum", true)
        selectedFolder = appPreferences.string("last_music_folder", "")
        moduleOrder = (appPreferences.string("module_order", "0,1,2,3").split(',')
            .mapNotNull(String::toIntOrNull).filter { it in 0..3 } + listOf(0, 1, 2, 3)).distinct()
        savedQueueStore = SavedQueueStore(this)
        savedQueueNames = savedQueueStore.names()
        yandexEnabled = appPreferences.enabled("yandex")
        videoEnabled = appPreferences.enabled("video", true)
        playlistsEnabled = appPreferences.enabled("playlists")
        foldersEnabled = appPreferences.enabled("folders")
        if (selectedFolder.isNotEmpty() && foldersEnabled) libraryView = 2
        recentEnabled = appPreferences.enabled("recent")
        albumsEnabled = appPreferences.enabled("albums")
        artistsEnabled = appPreferences.enabled("artists")
        genresEnabled = appPreferences.enabled("genres")
        favoritesEnabled = appPreferences.enabled("favorites")
        albumGridEnabled = appPreferences.enabled("album_grid", false)
        startTab = appPreferences.number("start_tab", 0).coerceIn(0, 1)
        tapAction = appPreferences.number("tap_action", 0).coerceIn(0, 2)
        folderShowFileName = appPreferences.enabled("folder_file_name", false)
        keepScreenOn = appPreferences.enabled("keep_screen_on", false)
        compactRows = appPreferences.enabled("compact_rows", true)
        coverCrop = appPreferences.enabled("cover_crop", false)
        themeBase = appPreferences.number("theme_base", 2).coerceIn(0, 2)
        themeAccent = appPreferences.number("theme_accent", 0).coerceIn(0, ThemePalette.accents.lastIndex)
        themeCustom = appPreferences.enabled("theme_custom", false)
        themeBackgroundHex = appPreferences.string("theme_background", "#101314")
        themeSurfaceHex = appPreferences.string("theme_surface", "#1C2021")
        themeTextHex = appPreferences.string("theme_text", "#F0F3F1")
        themeAccentHex = appPreferences.string("theme_accent_hex", "#8FCF52")
        repeatMode = appPreferences.number("repeat_mode", 0).coerceIn(0, 2)
        shuffle = appPreferences.enabled("shuffle", false)
        activeTab = if (startTab == 1 && yandexEnabled) 1 else 0
        downloadQuality = appPreferences.downloadQuality()
        token = tokenStore.read()
        val audioPrefs = getSharedPreferences("rikko_audio", MODE_PRIVATE)
        eqEnabled = audioPrefs.getBoolean("eq_enabled", false)
        eqLevels = audioPrefs.getString("eq_levels", "")?.split(',')?.mapNotNull(String::toShortOrNull).orEmpty()
        val libraryPrefs = getSharedPreferences("rikko_local", MODE_PRIVATE)
        audioViewMode = libraryPrefs.getInt("audio_view_mode", 0).coerceIn(0, 2)
        sortMode = libraryPrefs.getInt("sort_mode", 0).coerceIn(0, 2)
        sortDescending = libraryPrefs.getBoolean("sort_descending", false)
        recentUris = libraryStore.recentUris()
        favoriteUris = libraryStore.favoriteUris()
        playlistNames = libraryStore.playlistNames()
        refreshLibrary()
        acceptSharedLink(intent)
        setContent {
            val palette = ThemePalette.resolve(themeBase, themeAccent, themeCustom,
                themeBackgroundHex, themeSurfaceHex, themeTextHex, themeAccentHex)
            LaunchedEffect(palette) {
                window.statusBarColor = palette.background.toArgb()
                window.navigationBarColor = (if (palette.light) Color(0xFF26312D) else palette.background).toArgb()
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = palette.light
                    isAppearanceLightNavigationBars = false
                }
            }
            MaterialTheme(colorScheme = palette.scheme, shapes = VakoUi.shapes) {
                App()
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if ((visualizerVisible || miniSpectrumEnabled) && prepared && visualizer == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVisualizer()
        }
        val preferences = getSharedPreferences("rikko_local", MODE_PRIVATE)
        if (!libraryStore.hasAudioPermission() && !preferences.getBoolean("audio_permission_prompt_shown", false)) {
            preferences.edit().putBoolean("audio_permission_prompt_shown", true).apply()
            audioPermission.launch(libraryStore.audioPermission())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptSharedLink(intent)
    }

    private fun acceptSharedLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            val uri = intent.data!!
            val name = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment ?: "Аудиофайл"
            val song = LocalSong(uri, name.substringBeforeLast('.', name), fileName = name)
            activeTab = 0
            if (playbackService == null) pendingExternalAudio = song else playLocal(song, listOf(song))
            return
        }
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (shared.contains("music.yandex.")) {
            linkInput = Regex("https?://\\S+").find(shared)?.value ?: shared
            activeTab = if (yandexEnabled) 1 else 3
            if (!yandexEnabled) status = "Включите модуль Яндекса в настройках"
            remoteView = 2
        }
    }

    private fun checkForUpdates() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateMessage = "Проверяем релизы GitHub…"
        updateUrl = ""
        Thread {
            val result = runCatching { ReleaseChecker.latest() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                checkingUpdate = false
                result.onSuccess { release ->
                    val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
                    updateMessage = when {
                        release == null -> "Опубликованных релизов пока нет. Установлена версия $installed."
                        ReleaseChecker.isNewer(release.version, installed) -> "Доступна версия ${release.version}. Установлена $installed."
                        else -> "Установлена актуальная версия $installed."
                    }
                    if (release != null && ReleaseChecker.isNewer(release.version, installed)) updateUrl = release.pageUrl
                }.onFailure { updateMessage = "Не удалось проверить обновления: ${it.message ?: "ошибка сети"}" }
            }
        }.start()
    }

    private fun refreshLibrary() {
        if (scanning) {
            scanRequested = true
            return
        }
        scanning = true
        backgroundTask {
            val result = runCatching { libraryStore.songs() }
            postIfActive {
                scanning = false
                result.onSuccess { songs ->
                    localSongs = songs
                    if (!queueRestored) {
                        queueRestored = true
                        if (queue.isEmpty()) savedQueueStore.loadCurrent()?.let { restoreQueue(it, songs, keepPlayback = true) }
                    }
                }
                result.onFailure { status = "Не удалось обновить медиатеку: ${it.message ?: "ошибка"}" }
                if (scanRequested) {
                    scanRequested = false
                    refreshLibrary()
                }
            }
        }
    }

    private fun queueSnapshot(): SavedQueueStore.Snapshot = SavedQueueStore.Snapshot(
        queue.map { item -> when (item) {
            is QueueItem.Local -> SavedQueueStore.Entry(localUri = item.song.uri.toString())
            is QueueItem.Remote -> SavedQueueStore.Entry(remote = item.track)
        } }, queueIndex.coerceAtLeast(0)
    )

    private fun restoreQueue(snapshot: SavedQueueStore.Snapshot, songs: List<LocalSong> = localSongs, keepPlayback: Boolean = false) {
        val byUri = songs.associateBy { it.uri.toString() }
        val restored = snapshot.entries.mapNotNull { entry ->
            when {
                entry.localUri != null -> byUri[entry.localUri]?.let(QueueItem::Local)
                entry.remote != null -> QueueItem.Remote(entry.remote)
                else -> null
            }
        }
        if (restored.isEmpty()) return
        stopVisualizer()
        waveform = ByteArray(0)
        spectrum = ByteArray(0)
        stopEqualizer()
        val activePlayback = keepPlayback && playbackService?.title?.isNotBlank() == true
        if (!activePlayback) playbackService?.reset()
        prepared = activePlayback && playbackService?.prepared == true
        playing = activePlayback && player?.isPlaying == true
        queue = restored
        queueIndex = snapshot.index.coerceIn(0, restored.lastIndex)
        val current = restored[queueIndex]
        currentTitle = playbackService?.title?.takeIf { activePlayback } ?: when (current) { is QueueItem.Local -> current.song.title; is QueueItem.Remote -> current.track.title }
        currentArtist = playbackService?.artist?.takeIf { activePlayback } ?: when (current) { is QueueItem.Local -> current.song.artist; is QueueItem.Remote -> current.track.artist }
        cover = null
        currentPositionMs = if (activePlayback) player?.currentPosition ?: 0 else 0
        durationMs = if (prepared) player?.duration ?: 0 else 0
        resetShuffleOrder()
    }

    private fun search() {
        val q = query.trim()
        if (q.isBlank()) return
        selectedRemoteTracks = emptyList()
        if (token.isBlank()) { status = "Сначала подключите аккаунт в настройках"; activeTab = 3; return }
        busy = true
        status = "Ищем…"
        backgroundTask {
            try {
                val found = MusicRepository.searchTracks(q, token)
                val foundArtists = MusicRepository.searchArtists(q, token)
                postIfActive {
                    results = found
                    artists = foundArtists
                    selectedArtist = ""
                    status = if (found.isEmpty() && foundArtists.isEmpty()) "Ничего не найдено" else ""
                    busy = false
                }
            } catch (e: Exception) {
                postIfActive { status = "Ошибка поиска: ${e.message ?: "неизвестная ошибка"}"; busy = false }
            }
        }
    }

    private fun openArtist(artist: MusicRepository.Artist) {
        selectedRemoteTracks = emptyList()
        if (selectedArtist.isBlank()) searchResultsBeforeArtist = results
        busy = true
        status = "Открываем: ${artist.name}"
        backgroundTask {
            try {
                val tracks = MusicRepository.artistTracks(artist.id, token)
                postIfActive {
                    results = tracks
                    selectedArtist = artist.name
                    status = if (tracks.isEmpty()) "У исполнителя пока нет доступных треков" else ""
                    busy = false
                }
            } catch (e: Exception) {
                postIfActive { status = "Не удалось открыть исполнителя: ${e.message ?: "ошибка"}"; busy = false }
            }
        }
    }

    private fun openLink() {
        val link = linkInput.trim()
        if (link.isBlank()) return
        selectedRemoteTracks = emptyList()
        if (token.isBlank()) { status = "Сначала подключите аккаунт в настройках"; activeTab = 3; return }
        busy = true
        status = "Открываем подборку…"
        backgroundTask {
            try {
                val tracks = MusicRepository.playlistFromUrl(link, token)
                postIfActive { playlist = tracks; status = "Открыто треков: ${tracks.size}"; busy = false }
            } catch (e: Exception) {
                postIfActive { status = "Не удалось открыть: ${e.message ?: "неизвестная ошибка"}"; busy = false }
            }
        }
    }

    private fun loadOwnPlaylists() {
        if (token.isBlank()) { status = "Сначала подключите аккаунт в настройках"; return }
        busy = true
        status = "Загружаем плейлисты…"
        backgroundTask {
            try {
                val items = MusicRepository.ownPlaylists(token)
                postIfActive { ownPlaylists = items; busy = false; status = "Плейлистов: ${items.size}" }
            } catch (e: Exception) {
                postIfActive { busy = false; status = "Не удалось получить плейлисты: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun openOwnPlaylist(item: MusicRepository.UserPlaylist) {
        selectedRemoteTracks = emptyList()
        showLikedTracks = false
        selectedRemotePlaylist = item
        renameInput = item.title
        busy = true
        status = "Загружаем ${item.title}…"
        backgroundTask {
            try {
                val tracks = MusicRepository.playlistTracks(item, token)
                postIfActive { remotePlaylistTracks = tracks; busy = false; status = "Треков: ${tracks.size}" }
            } catch (e: Exception) {
                postIfActive { busy = false; status = "Не удалось открыть плейлист: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun generateAutoCollection() {
        if (autoCollectionBusy) return
        if (token.isBlank()) { status = "Подключите аккаунт Яндекс Музыки"; return }
        if (autoCollections.size >= 10) { status = "Лимит: 10 подборок"; return }
        val name = autoCollectionName.trim()
        if (name.isBlank()) { status = "Введите название подборки"; return }
        if (autoCollections.any { it.name.equals(name, true) }) { status = "Такое название уже есть"; return }
        autoCollectionBusy = true
        status = "Подбираем музыку…"
        backgroundTask {
            try {
                val tracks = recommendedTracks()
                autoCollectionStore.save(name, tracks)
                postIfActive {
                    autoCollections = autoCollectionStore.all()
                    autoCollectionName = "Подборка ${autoCollections.size + 1}"
                    autoCollectionBusy = false
                    status = "Подборка сохранена: ${tracks.size} треков"
                }
            } catch (e: Exception) {
                postIfActive { autoCollectionBusy = false; status = "Не удалось создать подборку: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun recommendedTracks(): List<Track> {
        val seeds = MusicRepository.recommendationSeeds(token).shuffled().take(5)
        return (seeds + seeds.flatMap { MusicRepository.similarTracks(it, token) })
            .filter { it.albumId.isNotBlank() }.distinctBy(::trackKey).take(100)
    }

    private fun refreshAutoCollection(collection: AutoCollection) {
        if (autoCollectionBusy) return
        autoCollectionBusy = true
        status = "Обновляем подборку…"
        backgroundTask {
            try {
                val tracks = recommendedTracks()
                autoCollectionStore.replace(collection.name, tracks)
                postIfActive {
                    autoCollections = autoCollectionStore.all()
                    selectedAutoCollection = autoCollections.firstOrNull { it.name == collection.name }
                    autoCollectionBusy = false
                    status = "Подборка обновлена: ${tracks.size} треков"
                }
            } catch (e: Exception) {
                postIfActive { autoCollectionBusy = false; status = "Не удалось обновить подборку: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun exportAutoCollection(collection: AutoCollection) {
        if (autoCollectionBusy || token.isBlank()) return
        autoCollectionBusy = true
        status = "Создаём копию в Яндекс Музыке…"
        backgroundTask {
            try {
                val playlist = MusicRepository.createPlaylist(collection.name, collection.tracks, token)
                postIfActive {
                    ownPlaylists = ownPlaylists + playlist
                    autoCollectionBusy = false
                    status = "Копия «${collection.name}» создана в Яндекс Музыке"
                }
            } catch (e: Exception) {
                postIfActive { autoCollectionBusy = false; status = "Не удалось сохранить в Яндекс Музыке: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun openLikedTracks() {
        selectedRemoteTracks = emptyList()
        selectedRemotePlaylist = null
        showLikedTracks = true
        if (likedLoaded) return
        refreshLikedTracks()
    }

    private fun refreshLikedTracks() {
        if (token.isBlank()) { status = "Подключите аккаунт Яндекса"; return }
        if (likedLoading) return
        likedLoading = true
        likedProgress = "Загружаем «Мне нравится»…"
        backgroundTask {
            try {
                val tracks = MusicRepository.likedTracks(token) { done, total ->
                    postIfActive { likedProgress = "Загружаем «Мне нравится»: $done/$total" }
                }
                postIfActive {
                    likedTracks = tracks
                    likedLoaded = true
                    likedLoading = false
                    likedProgress = "${trackCountLabel(tracks.size)}"
                }
            } catch (e: Exception) {
                postIfActive {
                    likedLoading = false
                    likedProgress = "Не удалось загрузить «Мне нравится»: ${e.message ?: "ошибка"}"
                }
            }
        }
    }

    private fun renameOwnPlaylist() {
        val item = selectedRemotePlaylist ?: return
        val name = renameInput.trim()
        if (name.isBlank() || name == item.title) return
        busy = true
        backgroundTask {
            try {
                MusicRepository.renamePlaylist(item, name, token)
                postIfActive {
                    val changed = item.copy(title = name)
                    selectedRemotePlaylist = changed
                    ownPlaylists = ownPlaylists.map { if (it.kind == item.kind) changed else it }
                    busy = false
                    status = "Название сохранено в Яндекс Музыке"
                }
            } catch (e: Exception) {
                postIfActive { busy = false; status = "Не удалось переименовать: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun deleteOwnPlaylist(item: MusicRepository.UserPlaylist) {
        if (busy || token.isBlank()) return
        busy = true
        status = "Удаляем «${item.title}»…"
        backgroundTask {
            try {
                MusicRepository.deletePlaylist(item, token)
                postIfActive {
                    ownPlaylists = ownPlaylists.filterNot { it.kind == item.kind && it.owner == item.owner }
                    if (selectedRemotePlaylist?.kind == item.kind) {
                        selectedRemotePlaylist = null
                        remotePlaylistTracks = emptyList()
                    }
                    busy = false
                    status = "Плейлист «${item.title}» удалён из Яндекс Музыки"
                }
            } catch (e: Exception) {
                postIfActive { busy = false; status = "Не удалось удалить плейлист: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun removeOwnTrack(position: Int) {
        val item = selectedRemotePlaylist ?: return
        busy = true
        backgroundTask {
            try {
                MusicRepository.removePlaylistTrack(item, position, token)
                val tracks = MusicRepository.playlistTracks(item, token)
                postIfActive { remotePlaylistTracks = tracks; busy = false; status = "Трек удалён из Яндекс Музыки" }
            } catch (e: Exception) {
                postIfActive { busy = false; status = "Не удалось удалить трек: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun addOwnTrack(item: MusicRepository.UserPlaylist, track: Track) {
        addOwnTracks(item, listOf(track))
    }

    private fun addOwnTracks(item: MusicRepository.UserPlaylist, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        busy = true
        backgroundTask {
            try {
                MusicRepository.addPlaylistTracks(item, tracks, token)
                val updated = if (selectedRemotePlaylist?.kind == item.kind) MusicRepository.playlistTracks(item, token) else null
                postIfActive {
                    if (updated != null) remotePlaylistTracks = updated
                    busy = false
                    status = "Добавлено в «${item.title}»: ${tracks.size}"
                }
            } catch (e: Exception) {
                postIfActive { busy = false; status = "Не удалось добавить трек: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun trackKey(track: Track): String = "${track.trackId}:${track.albumId}"

    private fun toggleRemoteSelection(track: Track) {
        val key = trackKey(track)
        selectedRemoteTracks = if (selectedRemoteTracks.any { trackKey(it) == key })
            selectedRemoteTracks.filterNot { trackKey(it) == key }
        else selectedRemoteTracks + track
    }

    private fun toggleLocalSelection(song: LocalSong) {
        val uri = song.uri.toString()
        selectedLocalUris = if (uri in selectedLocalUris) selectedLocalUris - uri else selectedLocalUris + uri
    }

    private fun toggleFavorite(song: LocalSong) {
        val added = libraryStore.toggleFavorite(song.uri)
        favoriteUris = libraryStore.favoriteUris()
        status = if (added) "Добавлено в избранное" else "Удалено из избранного"
    }

    private fun openRemotePlaylistPicker(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        playlistPickerTracks = tracks
        playlistSearch = ""
        if (ownPlaylists.isEmpty() && !busy) loadOwnPlaylists()
    }

    private fun openFolderPlaylistPicker(folder: FolderNode) {
        pendingFolderPlaylist = folder
        folderPlaylistName = folder.label
        folderPlaylistError = ""
    }

    private fun startMediaEdits(edits: List<MediaEdit>, description: String) {
        if (edits.isEmpty()) { status = "В папке нет аудиофайлов"; return }
        if (edits.any { it.uri.authority != MediaStore.AUTHORITY }) {
            status = "Этот файл открыт через системный выбор; изменение пути недоступно"
            return
        }
        if (edits.map { it.uri }.distinct().size != edits.size) {
            status = "Один файл попал в операцию несколько раз"
            return
        }
        failedMediaEdits = emptyList()
        pendingMediaEdits = edits
        pendingMediaDescription = description
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val request = MediaStore.createWriteRequest(contentResolver, edits.map(MediaEdit::uri).distinct())
                mediaWritePermission.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else applyMediaEdits()
        } catch (e: Exception) { status = "Не удалось запросить доступ: ${e.message ?: "ошибка"}" }
    }

    private fun applyMediaEdits() {
        val edits = pendingMediaEdits
        val description = pendingMediaDescription
        backgroundTask {
            val planError = MediaEdits.validatePlan(contentResolver, edits)
            if (planError != null) {
                postIfActive {
                    pendingMediaEdits = emptyList()
                    status = planError
                }
                return@backgroundTask
            }
            val report = BatchOperations.run(edits) { edit ->
                contentResolver.update(edit.uri, edit.values(), null, null) > 0
            }
            postIfActive {
                pendingMediaEdits = emptyList()
                failedMediaEdits = report.failed.map { it.item }
                refreshLibrary()
                status = if (report.failed.isEmpty()) "$description: ${report.succeeded.size} файлов"
                else "$description: ${report.succeeded.size} из ${edits.size}. Не изменены: " +
                    report.failed.take(3).joinToString { it.item.name ?: it.item.uri.lastPathSegment ?: "файл" } +
                    if (report.failed.size > 3) " и ещё ${report.failed.size - 3}" else ""
            }
        }
    }

    private fun shareSong(song: LocalSong) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, song.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Отправить аудиофайл"))
    }

    private fun chooseDestination(): List<FolderNode> = buildFolderTree(localSongs).let { root ->
        fun collect(node: FolderNode): List<FolderNode> = listOf(node) + node.children.values.flatMap(::collect)
        collect(root).filter { it.key.isNotEmpty() }
    }

    private fun downloadOwnPlaylist() {
        val item = selectedRemotePlaylist ?: return
        downloadTracks(remotePlaylistTracks, item.title, item.title)
    }

    private fun downloadOwnPlaylist(item: MusicRepository.UserPlaylist) {
        if (busy || bulkDownloading) return
        busy = true
        status = "Загружаем треки «${item.title}»…"
        backgroundTask {
            try {
                val tracks = MusicRepository.playlistTracks(item, token)
                postIfActive {
                    busy = false
                    if (tracks.isEmpty()) status = "Плейлист «${item.title}» пуст"
                    else downloadTracks(tracks, item.title, item.title)
                }
            } catch (e: Exception) {
                postIfActive { busy = false; status = "Не удалось загрузить плейлист: ${e.message ?: "ошибка"}" }
            }
        }
    }

    private fun downloadTracks(tracks: List<Track>, title: String, playlistFolder: String? = null) {
        if (tracks.isEmpty() || bulkDownloading) return
        bulkDownloading = true
        cancelBulk = false
        bulkDone = 0
        bulkTotal = tracks.size
        status = "Скачиваем «$title»: 0/${tracks.size}"
        backgroundTask {
            var failed = 0
            val downloader = Downloader(this)
            for ((index, track) in tracks.withIndex()) {
                if (cancelBulk) break
                try {
                    val result = downloader.downloadTrack(track, token, downloadQuality, wantNativeFlac = true,
                        wantLyrics = false, playlistFolder = playlistFolder)
                    result.uri?.let(libraryStore::addUri)
                } catch (_: Exception) { failed++ }
                postIfActive { bulkDone = index + 1; status = "Скачиваем «$title»: $bulkDone/${tracks.size}" }
            }
            postIfActive {
                bulkDownloading = false
                status = if (cancelBulk) "Загрузка остановлена: $bulkDone/${tracks.size}, ошибок: $failed"
                else "Загрузка завершена: ${tracks.size - failed}/${tracks.size}, ошибок: $failed"
                refreshLibrary()
            }
        }
    }

    private fun playRemote(track: Track, source: List<Track> = results) {
        val items = source.ifEmpty { listOf(track) }.map(QueueItem::Remote)
        queueSourcePlaylist = selectedRemotePlaylist?.takeIf { source === remotePlaylistTracks }
        queue = items
        queueIndex = items.indexOfFirst { it.track.trackId == track.trackId }.coerceAtLeast(0)
        resetShuffleOrder()
        playQueueItem()
    }

    private fun playLocal(song: LocalSong, source: List<LocalSong> = localSongs) {
        queueSourcePlaylist = null
        val items = source.ifEmpty { listOf(song) }.map(QueueItem::Local)
        queue = items
        queueIndex = items.indexOfFirst { it.song.uri == song.uri }.coerceAtLeast(0)
        resetShuffleOrder()
        playQueueItem()
    }

    private fun tapLocal(song: LocalSong, source: List<LocalSong>) {
        when (tapAction) {
            1 -> addToQueue(QueueItem.Local(song), false)
            2 -> addToQueue(QueueItem.Local(song), true)
            else -> playLocal(song, source)
        }
    }

    private fun tapRemote(track: Track, source: List<Track>) {
        when (tapAction) {
            1 -> addToQueue(QueueItem.Remote(track), false)
            2 -> addToQueue(QueueItem.Remote(track), true)
            else -> playRemote(track, source)
        }
    }

    private fun playQueueItem() {
        val item = queue.getOrNull(queueIndex) ?: return
        val generation = ++playbackGeneration
        stopVisualizer()
        waveform = ByteArray(0)
        spectrum = ByteArray(0)
        stopEqualizer()
        playbackService?.reset()
        playing = false
        prepared = false
        currentPositionMs = 0
        durationMs = 0
        cover = null
        loadCover(item, generation)
        when (item) {
            is QueueItem.Local -> {
                currentTitle = item.song.title
                currentArtist = item.song.artist.ifBlank { "На устройстве" }
                libraryStore.recordPlay(item.song.uri)
                recentUris = libraryStore.recentUris()
                startPlayer(null, item.song.uri)
            }
            is QueueItem.Remote -> {
                if (token.isBlank()) { status = "Подключите аккаунт"; activeTab = 3; return }
                currentTitle = item.track.title
                currentArtist = item.track.artist
                status = "Загружаем аудио…"
                backgroundTask {
                    try {
                        val source = YandexMusic.getAudioSource(item.track, Quality.MP3_320, token)
                        postIfActive { if (generation == playbackGeneration) startPlayer(source.url, null) }
                    } catch (e: Exception) {
                        postIfActive { if (generation == playbackGeneration) status = "Не удалось воспроизвести: ${e.message ?: "неизвестная ошибка"}" }
                    }
                }
            }
        }
    }

    private fun startPlayer(url: String?, uri: Uri?) {
        val service = playbackService ?: run { status = "Сервис воспроизведения ещё запускается"; return }
        startService(Intent(this, PlaybackService::class.java))
        service.play(url, uri, currentTitle, currentArtist)
    }

    private fun togglePlayback() {
        if (!prepared && queue.isNotEmpty()) {
            playQueueItem()
            return
        }
        if (prepared) {
            playbackService?.toggle()
            playing = player?.isPlaying == true
        }
    }

    private fun nextTrack(automatic: Boolean = false) {
        if (queue.isEmpty()) return
        if (shuffle && shuffleRemaining.isEmpty() && repeatMode == 1) resetShuffleOrder()
        val next = if (shuffle) {
            if (shuffleRemaining.isEmpty()) queue.size else shuffleRemaining.removeAt(shuffleRemaining.indices.random())
        } else queueIndex + 1
        if (next >= queue.size) {
            if (repeatMode == 1) queueIndex = 0
            else { if (!automatic) status = "Конец очереди"; return }
        } else {
            if (shuffle) shuffleHistory.add(queueIndex)
            queueIndex = next
        }
        playQueueItem()
    }

    private fun resetShuffleOrder() {
        shuffleRemaining = queue.indices.filter { it != queueIndex }.toMutableList()
        shuffleHistory.clear()
    }

    private fun toggleShuffle() {
        shuffle = !shuffle
        appPreferences.setEnabled("shuffle", shuffle)
        resetShuffleOrder()
    }

    private fun cycleRepeatMode() {
        repeatMode = (repeatMode + 1) % 3
        appPreferences.setNumber("repeat_mode", repeatMode)
    }

    private fun previousTrack() {
        if (prepared && (player?.currentPosition ?: 0) > 3000) {
            playbackService?.seekTo(0)
            currentPositionMs = 0
        } else if (shuffle && shuffleHistory.isNotEmpty()) {
            shuffleRemaining.add(queueIndex)
            queueIndex = shuffleHistory.removeAt(shuffleHistory.lastIndex)
            playQueueItem()
        } else if (queueIndex > 0 && !shuffle) {
            queueIndex--
            playQueueItem()
        } else if (repeatMode == 1 && queue.isNotEmpty()) {
            queueIndex = queue.lastIndex
            playQueueItem()
        } else if (prepared) {
            playbackService?.seekTo(0)
        }
    }

    private fun addToQueue(item: QueueItem, playNext: Boolean) {
        appendToQueue(listOf(item), playNext)
    }

    private fun appendToQueue(items: List<QueueItem>, playNext: Boolean) {
        if (items.isEmpty()) return
        if (queue.isEmpty()) {
            queue = items
            queueIndex = 0
            resetShuffleOrder()
            playQueueItem()
            return
        }
        val updated = queue.toMutableList()
        if (playNext) updated.addAll((queueIndex + 1).coerceIn(0, updated.size), items) else updated.addAll(items)
        queue = updated
        resetShuffleOrder()
        status = if (playNext) "Добавлено следующим" else "Добавлено в очередь"
    }

    private fun moveQueueItem(from: Int, direction: Int) {
        val to = from + direction
        reorderQueue(from, to)
    }

    private fun reorderQueue(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices) return
        val updated = queue.toMutableList()
        val moved = updated.removeAt(from)
        updated.add(to, moved)
        queueIndex = when {
            queueIndex == from -> to
            from < queueIndex && queueIndex <= to -> queueIndex - 1
            to <= queueIndex && queueIndex < from -> queueIndex + 1
            else -> queueIndex
        }
        queue = updated
        resetShuffleOrder()
    }

    private fun removeQueueItem(index: Int) {
        if (index !in queue.indices || index == queueIndex) return
        val updated = queue.toMutableList()
        updated.removeAt(index)
        if (index < queueIndex) queueIndex--
        queue = updated
        resetShuffleOrder()
    }

    private fun sortedSongs(songs: List<LocalSong>): List<LocalSong> {
        val comparator = when (sortMode) {
            1 -> compareBy<LocalSong> { it.artist.lowercase() }.thenBy { it.title.lowercase() }
            2 -> compareBy<LocalSong> { it.dateAdded }.thenBy { it.title.lowercase() }
            else -> compareBy<LocalSong> { it.title.lowercase() }
        }
        return songs.sortedWith(if (sortDescending) comparator.reversed() else comparator)
    }

    private fun loadCover(item: QueueItem, generation: Int) {
        backgroundTask {
            val image = try {
                val bytes = when (item) {
                    is QueueItem.Local -> {
                        val retriever = MediaMetadataRetriever()
                        try { retriever.setDataSource(this, item.song.uri); retriever.embeddedPicture }
                        finally { retriever.release() }
                    }
                    is QueueItem.Remote -> YandexMusic.coverUrl(item.track.coverUri, "300x300")?.let {
                        Http.getBytes(it, maxBytes = 4 * 1024 * 1024)
                    }
                }
                bytes?.let {
                    BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 2 })
                        ?.asImageBitmap()
                }
            } catch (_: Exception) { null }
            postIfActive { if (generation == playbackGeneration) cover = image }
        }
    }

    private fun startEqualizer() {
        stopEqualizer()
        if (!eqEnabled || !prepared) return
        try {
            val effect = Equalizer(0, player?.audioSessionId ?: return)
            equalizer = effect
            val count = effect.numberOfBands.toInt()
            val range = effect.bandLevelRange
            eqRange = range[0] to range[1]
            eqFrequencies = (0 until count).map { effect.getCenterFreq(it.toShort()) / 1000 }
            if (eqLevels.size != count) eqLevels = List(count) { 0.toShort() }
            eqLevels.forEachIndexed { index, level ->
                effect.setBandLevel(index.toShort(), level.coerceIn(range[0], range[1]))
            }
            effect.enabled = true
        } catch (e: Exception) {
            stopEqualizer()
            eqEnabled = false
            getSharedPreferences("rikko_audio", MODE_PRIVATE).edit().putBoolean("eq_enabled", false).apply()
            status = "Эквалайзер недоступен: ${e.message ?: "ошибка"}"
        }
    }

    private fun stopEqualizer() {
        equalizer?.let { effect ->
            try { effect.enabled = false } catch (_: Exception) { }
            try { effect.release() } catch (_: Exception) { }
        }
        equalizer = null
    }

    private fun setEqLevel(index: Int, level: Short) {
        if (index !in eqLevels.indices) return
        val updated = eqLevels.toMutableList()
        updated[index] = level
        eqLevels = updated
        getSharedPreferences("rikko_audio", MODE_PRIVATE).edit()
            .putString("eq_levels", updated.joinToString(",")).apply()
        try { equalizer?.setBandLevel(index.toShort(), level) } catch (_: Exception) { }
    }

    private fun startSleepTimer(minutes: Int) {
        val service = playbackService ?: run {
            status = "Сервис воспроизведения ещё запускается"
            return
        }
        service.setSleepTimer(minutes)
        sleepDeadline = service.sleepDeadline
        sleepRemainingMs = (sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    private fun cancelSleepTimer() {
        playbackService?.cancelSleepTimer()
        sleepDeadline = 0L
        sleepRemainingMs = 0L
    }

    private fun startVisualizer() {
        if (!prepared) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (visualizerVisible && showNowPlaying) visualizerPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        stopVisualizer()
        try {
            val session = player?.audioSessionId ?: return
            val effect = Visualizer(session)
            visualizer = effect
            effect.captureSize = 256.coerceIn(Visualizer.getCaptureSizeRange()[0], Visualizer.getCaptureSizeRange()[1])
            effect.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer?, data: ByteArray?, samplingRate: Int) {
                    if (data != null) postIfActive { waveform = data.copyOf() }
                }
                override fun onFftDataCapture(visualizer: Visualizer?, data: ByteArray?, samplingRate: Int) {
                    if (data != null) postIfActive { spectrum = data.copyOf() }
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true)
            effect.enabled = true
        } catch (e: Exception) {
            stopVisualizer()
            status = "Визуализация недоступна на устройстве: ${e.message ?: "ошибка"}"
        }
    }

    private fun stopVisualizer() {
        visualizer?.let { effect ->
            try { effect.enabled = false; effect.release() } catch (_: Exception) { }
        }
        visualizer = null
    }

    private fun download(track: Track) {
        if (downloading || bulkDownloading) return
        if (token.isBlank()) { status = "Подключите аккаунт"; activeTab = 3; return }
        downloading = true
        progress = 0f
        status = "Скачиваем: ${track.title}"
        backgroundTask {
            try {
                val result = Downloader(this).downloadTrack(
                    track, token, downloadQuality, wantNativeFlac = true, wantLyrics = false
                ) { bytes, total ->
                    if (total > 0) postIfActive { progress = (bytes.toFloat() / total).coerceIn(0f, 1f) }
                }
                postIfActive {
                    result.uri?.let { uri ->
                        libraryStore.addUri(uri)
                        refreshLibrary()
                    }
                    status = "Сохранено в Музыка/MusicDownloader: ${result.fileName}"
                    downloading = false
                    progress = 0f
                }
            } catch (e: Exception) {
                postIfActive { status = "Ошибка загрузки: ${e.message ?: "неизвестная ошибка"}"; downloading = false; progress = 0f }
            }
        }
    }

    override fun onDestroy() {
        closing = true
        backgroundExecutor.shutdownNow()
        stopVisualizer()
        stopEqualizer()
        playbackService?.listener = null
        unbindService(playbackConnection)
        super.onDestroy()
    }

    override fun onStop() {
        if (::savedQueueStore.isInitialized && queueRestored) savedQueueStore.saveCurrent(queueSnapshot())
        stopVisualizer()
        super.onStop()
    }

    @Composable
    private fun App() {
        BackHandler { navigateBack() }
        LaunchedEffect(showNowPlaying, visualizerVisible) {
            if (showNowPlaying && visualizerVisible && prepared && visualizer == null) startVisualizer()
        }
        LaunchedEffect(selectedFolder) { appPreferences.setString("last_music_folder", selectedFolder) }
        LaunchedEffect(navigationVisible, navigationTick, showNowPlaying) {
            if (navigationVisible && !showNowPlaying) {
                delay(15_000)
                navigationVisible = false
            }
        }
        LaunchedEffect(localSongs) {
            if (localSongs.isNotEmpty() && selectedFolder.isNotEmpty()) {
                val root = buildFolderTree(localSongs)
                var path = selectedFolder
                while (path.isNotEmpty() && root.find(path) == null) path = path.substringBeforeLast('/', "")
                if (path != selectedFolder) selectedFolder = path
            }
        }
        LaunchedEffect(keepScreenOn, playing) {
            if (keepScreenOn && playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        LaunchedEffect(queue, queueIndex, queueRestored) {
            if (queueRestored) {
                delay(350)
                val snapshot = queueSnapshot()
                withContext(Dispatchers.IO) { savedQueueStore.saveCurrent(snapshot) }
            }
        }
        LaunchedEffect(currentTitle) {
            while (true) {
                if (prepared) currentPositionMs = try { player?.currentPosition ?: 0 } catch (_: IllegalStateException) { 0 }
                if (sleepDeadline > 0L) sleepRemainingMs = (sleepDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                delay(500)
            }
        }
        Scaffold(
            modifier = Modifier.vakoBackdrop(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                if (!showNowPlaying) Column {
                    if (currentTitle.isNotBlank()) PlayerBar()
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))
                    Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, shape = VakoUi.panel,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp).vakoPanel()) {
                        if (navigationVisible) {
                            val labels = mapOf(0 to "Медиатека", 1 to "Яндекс", 2 to "Видео", 3 to "Настройки")
                            val shortLabels = mapOf(0 to "Медиа", 1 to "Яндекс", 2 to "Видео", 3 to "Настр.")
                            val useShortLabels = LocalDensity.current.fontScale >= 1.25f ||
                                LocalConfiguration.current.screenWidthDp < 390
                            val visible = moduleOrder.filter { it == 0 || it == 3 ||
                                (it == 1 && yandexEnabled) || (it == 2 && videoEnabled) }
                            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                visible.forEach { index ->
                                    val openModule = {
                                        activeTab = index
                                        if (index == 3) settingsPage = 0
                                        showQueue = false; showSound = false
                                        selectedLocalUris = emptySet(); selectedRemoteTracks = emptyList()
                                        navigationTick++
                                        Unit
                                    }
                                    Box(modifier = Modifier.weight(1f).fillMaxSize()
                                        .vakoPanel(active = activeTab == index, shape = VakoUi.row)
                                        .semantics {
                                            contentDescription = labels[index].orEmpty()
                                            selected = activeTab == index
                                            onClick { openModule(); true }
                                        }
                                        .pointerInput(index, moduleOrder, visible) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                var dragging = false
                                                while (true) {
                                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                    val dx = change.position.x - down.position.x
                                                    val dy = change.position.y - down.position.y
                                                    val held = change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                                                    if (!held && (kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                                                kotlin.math.abs(dy) > viewConfiguration.touchSlop)) {
                                                        break
                                                    }
                                                    if (held && kotlin.math.abs(dx) > viewConfiguration.touchSlop) {
                                                        dragging = true
                                                        draggedModule = index
                                                        navigationDragOffset = dx
                                                        change.consume()
                                                    }
                                                    if (!change.pressed) {
                                                        if (dragging) {
                                                            val from = visible.indexOf(index)
                                                            val slot = (window.decorView.width.toFloat() / visible.size).coerceAtLeast(1f)
                                                            val to = (from + (navigationDragOffset / slot).roundToInt()).coerceIn(0, visible.lastIndex)
                                                            if (from >= 0 && to != from) {
                                                                val reordered = visible.toMutableList().apply { add(to, removeAt(from)) }
                                                                moduleOrder = (reordered + moduleOrder.filter { it !in visible }).distinct()
                                                                appPreferences.setString("module_order", moduleOrder.joinToString(","))
                                                            }
                                                            draggedModule = null
                                                            navigationTick++
                                                        }
                                                        break
                                                    }
                                                }
                                            }
                                        }.clickable(onClick = openModule), contentAlignment = Alignment.Center) {
                                        Text((if (useShortLabels) shortLabels else labels)[index].orEmpty(),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (activeTab == index || draggedModule == index) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        } else {
                            TextButton(onClick = { navigationVisible = true; navigationTick++ },
                                modifier = Modifier.fillMaxWidth().height(34.dp)) { Text("▴  Разделы") }
                        }
                    }
                }
            }
        ) { padding ->
            if (showNowPlaying) NowPlayingScreen(Modifier.fillMaxSize().padding(padding))
            else Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("VAKO PLAYER", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        when { showQueue -> "ОЧЕРЕДЬ"; showSound -> "ЗВУК"; else -> when (activeTab) {
                            0 -> "МЕДИАТЕКА"; 1 -> "ЯНДЕКС"; 2 -> "ВИДЕО"; else -> "НАСТРОЙКИ"
                        } },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(Modifier.height(10.dp))
                when {
                    showQueue -> QueueScreen()
                    showSound -> SoundScreen()
                    activeTab == 0 -> LibraryScreen()
                    activeTab == 1 && yandexEnabled -> YandexScreen()
                    activeTab == 2 && videoEnabled -> VideoModule.Screen(
                        openSettings = { activeTab = 3 },
                        onOpenVideo = { playbackService?.pause(); playing = false }
                    )
                    else -> SettingsScreen()
                }
            }
        }
        if (playlistPickerTracks.isNotEmpty()) RemotePlaylistPicker()
        if (pendingFolderPlaylist != null) FolderPlaylistDialog()
        if (localPlaylistPickerSongs.isNotEmpty()) LocalPlaylistPicker()
        if (pendingRenameSong != null || pendingRenameFolder != null) RenameMediaDialog()
        if (pendingMoveSong != null || pendingMoveSongs.isNotEmpty() || pendingMoveFolder != null) MoveMediaDialog()
        if (pendingInfoSong != null) SongInfoDialog()
        if (showSaveQueue) SaveQueueDialog()
        if (showSavedQueues) SavedQueuesDialog()
        if (showClearQueue) ClearQueueDialog()
        if (pendingDeleteQueueName.isNotBlank()) DeleteSavedQueueDialog()
        pendingDeleteRemotePlaylist?.let { playlist ->
            AlertDialog(
                onDismissRequest = { pendingDeleteRemotePlaylist = null },
                title = { Text("Удалить плейлист?") },
                text = { Text("«${playlist.title}» будет удалён из вашего аккаунта Яндекс Музыки. Уже скачанные файлы останутся на телефоне.") },
                confirmButton = {
                    TextButton(onClick = { pendingDeleteRemotePlaylist = null; deleteOwnPlaylist(playlist) }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteRemotePlaylist = null }) { Text("Отмена") } }
            )
        }
    }

    private fun closeNowPlaying() {
        showNowPlaying = false
    }

    private fun changeVisualizerPage(direction: Int) {
        val current = if (visualizerVisible) visualizerStyle + 1 else 0
        val next = (current + direction + 7) % 7
        if (next == 0) {
            visualizerVisible = false
            if (!miniSpectrumEnabled) stopVisualizer()
        } else {
            visualizerStyle = next - 1
            if (!visualizerVisible) {
                visualizerVisible = true
                startVisualizer()
            }
        }
        appPreferences.setNumber("visualizer_page", next)
    }

    private fun navigateBack() {
        when {
            showNowPlaying -> closeNowPlaying()
            showQueue || showSound -> { showQueue = false; showSound = false }
            activeTab == 3 && settingsPage != 0 -> settingsPage = if (settingsPage == 7) 4 else 0
            selectedLocalUris.isNotEmpty() -> selectedLocalUris = emptySet()
            selectedRemoteTracks.isNotEmpty() -> selectedRemoteTracks = emptyList()
            activeTab == 0 && libraryView == 2 && selectedFolder.isNotEmpty() -> {
                selectedFolder = selectedFolder.substringBeforeLast('/', "")
            }
            activeTab == 0 && libraryView == 1 && selectedPlaylist.isNotEmpty() -> selectedPlaylist = ""
            activeTab == 0 && libraryView in 4..6 && selectedBrowseGroup.isNotEmpty() -> selectedBrowseGroup = ""
            activeTab == 0 && libraryView != 0 -> {
                libraryView = 0
                selectedFolder = ""
                selectedPlaylist = ""
                selectedBrowseGroup = ""
            }
            activeTab == 0 && localFilter.isNotEmpty() -> localFilter = ""
            activeTab == 1 && showLikedTracks -> showLikedTracks = false
            activeTab == 1 && selectedAutoCollection != null -> selectedAutoCollection = null
            activeTab == 1 && selectedRemotePlaylist != null -> {
                selectedRemotePlaylist = null
                remotePlaylistTracks = emptyList()
            }
            activeTab == 1 && selectedArtist.isNotEmpty() -> {
                selectedArtist = ""
                results = searchResultsBeforeArtist
                searchResultsBeforeArtist = emptyList()
            }
            activeTab == 1 && remoteView == 2 && playlist.isNotEmpty() -> playlist = emptyList()
            activeTab == 1 && remoteView == 0 && (results.isNotEmpty() || artists.isNotEmpty()) -> {
                results = emptyList()
                artists = emptyList()
            }
            activeTab != 0 -> {
                activeTab = 0
                libraryView = 0
                selectedFolder = ""
                selectedPlaylist = ""
                selectedBrowseGroup = ""
            }
            else -> moveTaskToBack(true)
        }
    }

    @Composable
    private fun RemotePlaylistPicker() {
        AlertDialog(
            onDismissRequest = { playlistPickerTracks = emptyList() },
            title = { Text("Добавить в плейлист") },
            text = {
                Column {
                    OutlinedTextField(
                        value = playlistSearch, onValueChange = { playlistSearch = it },
                        label = { Text("Найти плейлист") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    val matches = ownPlaylists.filter { it.title.contains(playlistSearch, ignoreCase = true) }
                    if (ownPlaylists.isEmpty()) Text(if (busy) "Загружаем плейлисты…" else "Плейлисты не найдены")
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(matches, key = { it.kind }) { item ->
                            TextButton(onClick = {
                                val tracks = playlistPickerTracks
                                playlistPickerTracks = emptyList()
                                selectedRemoteTracks = emptyList()
                                addOwnTracks(item, tracks)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(item.title, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { playlistPickerTracks = emptyList() }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun LocalPlaylistPicker() {
        AlertDialog(
            onDismissRequest = { localPlaylistPickerSongs = emptyList() },
            title = { Text("Добавить в плейлист") },
            text = {
                Column {
                    OutlinedTextField(
                        value = localPickerName, onValueChange = { localPickerName = it },
                        label = { Text("Новый плейлист") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        val name = localPickerName.trim()
                        val songs = localPlaylistPickerSongs
                        if (libraryStore.createPlaylistFromSongs(name, songs)) {
                            playlistNames = libraryStore.playlistNames()
                            localPlaylistPickerSongs = emptyList()
                            selectedLocalUris = emptySet()
                            localPickerName = ""
                            libraryRevision++
                        } else status = "Плейлист с таким именем уже есть"
                    }, enabled = localPickerName.isNotBlank()) { Text("Создать с выбранными треками") }
                    if (status == "Плейлист с таким именем уже есть") Text(status)
                    LazyColumn(Modifier.heightIn(max = 330.dp)) {
                        items(playlistNames) { name ->
                            TextButton(onClick = {
                                libraryStore.addManyToPlaylist(name, localPlaylistPickerSongs)
                                localPlaylistPickerSongs = emptyList()
                                selectedLocalUris = emptySet()
                                libraryRevision++
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(name, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { localPlaylistPickerSongs = emptyList() }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun FolderPlaylistDialog() {
        val folder = pendingFolderPlaylist ?: return
        val songs = remember(folder) { folder.allSongs() }
        AlertDialog(
            onDismissRequest = { pendingFolderPlaylist = null },
            title = { Text("Плейлист из папки") },
            text = {
                Column {
                    Text("Включая подпапки: ${trackCountLabel(songs.size)}")
                    OutlinedTextField(
                        value = folderPlaylistName, onValueChange = { folderPlaylistName = it; folderPlaylistError = "" },
                        label = { Text("Название плейлиста") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    if (folderPlaylistError.isNotBlank()) Text(folderPlaylistError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (libraryStore.createPlaylistFromSongs(folderPlaylistName, songs)) {
                        playlistNames = libraryStore.playlistNames()
                        selectedPlaylist = folderPlaylistName.trim()
                        localRenameInput = selectedPlaylist
                        libraryRevision++
                        pendingFolderPlaylist = null
                        libraryView = 1
                    } else folderPlaylistError = "Введите другое название плейлиста"
                }, enabled = folderPlaylistName.isNotBlank() && songs.isNotEmpty()) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { pendingFolderPlaylist = null }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun RenameMediaDialog() {
        val song = pendingRenameSong
        val folder = pendingRenameFolder
        AlertDialog(
            onDismissRequest = { pendingRenameSong = null; pendingRenameFolder = null },
            title = { Text(if (song != null) "Переименовать файл" else "Переименовать папку") },
            text = {
                Column {
                    OutlinedTextField(editName, onValueChange = { editName = it; editError = "" },
                        label = { Text("Новое название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (folder != null) Text("Будут изменены пути ${folder.totalTracks} аудиофайлов, включая подпапки. Другие файлы останутся на месте.",
                        style = MaterialTheme.typography.bodySmall)
                    if (editError.isNotBlank()) Text(editError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = {
                try {
                    val edits = if (song != null) listOf(MediaEdits.renameFile(contentResolver, song, editName))
                        else {
                            val current = folder ?: return@TextButton
                            val sibling = buildFolderTree(localSongs).find(current.key.substringBeforeLast('/'))
                            require(sibling?.children?.containsKey(editName.trim()) != true) { "Папка с таким названием уже существует" }
                            MediaEdits.renameFolder(current, editName)
                        }
                    pendingRenameSong = null; pendingRenameFolder = null
                    startMediaEdits(edits, "Переименовано")
                } catch (e: Exception) { editError = e.message ?: "Не удалось подготовить изменение" }
            }, enabled = editName.isNotBlank()) { Text("Продолжить") } },
            dismissButton = { TextButton(onClick = { pendingRenameSong = null; pendingRenameFolder = null }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun MoveMediaDialog() {
        val song = pendingMoveSong
        val songs = pendingMoveSongs
        val folder = pendingMoveFolder
        val volume = song?.volume ?: songs.firstOrNull()?.volume ?: folder?.key?.substringBefore('/') ?: ""
        val candidates = remember(localSongs, volume, song, songs, folder) {
            chooseDestination().filter { it.key.substringBefore('/') == volume &&
                '/' in it.key &&
                (song == null || MediaEdits.relativePath(it.key).trimEnd('/') != song.folder.trimEnd('/')) &&
                (folder == null || (it.key != folder.key && !it.key.startsWith(folder.key + "/"))) }
        }
        AlertDialog(
            onDismissRequest = { pendingMoveSong = null; pendingMoveSongs = emptyList(); pendingMoveFolder = null },
            title = { Text("Переместить в папку") },
            text = {
                Column {
                    Text("На том же накопителе${if (folder != null) "; ${folder.totalTracks} аудиофайлов с подпапками, другие файлы останутся" else if (songs.isNotEmpty()) "; ${songs.size} файлов" else ""}",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(moveSearch, onValueChange = { moveSearch = it },
                        label = { Text("Найти папку") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(candidates.filter { it.key.contains(moveSearch, true) }, key = { it.key }) { target ->
                            TextButton(onClick = {
                                try {
                                    val edits = when {
                                        song != null -> listOf(MediaEdits.moveFile(song, target))
                                        songs.isNotEmpty() -> songs.map { MediaEdits.moveFile(it, target) }
                                        else -> MediaEdits.moveFolder(folder ?: return@TextButton, target)
                                    }
                                    pendingMoveSong = null; pendingMoveSongs = emptyList(); pendingMoveFolder = null
                                    selectedLocalUris = emptySet()
                                    startMediaEdits(edits, "Перемещено")
                                } catch (e: Exception) { editError = e.message ?: "Не удалось подготовить перенос" }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if ('/' in target.key) target.key.substringAfter('/') else volumeLabel(target.key),
                                    modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (editError.isNotBlank()) Text(editError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { pendingMoveSong = null; pendingMoveSongs = emptyList(); pendingMoveFolder = null }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun SongInfoDialog() {
        val song = pendingInfoSong ?: return
        AlertDialog(
            onDismissRequest = { pendingInfoSong = null }, title = { Text("Сведения о файле") },
            text = { Column {
                Text(song.title, fontWeight = FontWeight.SemiBold)
                Text(song.artist.ifBlank { "Исполнитель не указан" })
                Text("Папка: ${volumeLabel(song.volume)}/${song.folder}")
                Text("URI: ${song.uri}", style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { pendingInfoSong = null }) { Text("Закрыть") } }
        )
    }

    @Composable
    private fun YandexScreen() {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Поиск", "Мои плейлисты", "По ссылке", "Автоподборки").forEachIndexed { index, label ->
                VakoChip(label, remoteView == index, onClick = {
                    remoteView = index
                    selectedRemoteTracks = emptyList()
                    if (index == 1 && ownPlaylists.isEmpty() && !busy) loadOwnPlaylists()
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (selectedRemoteTracks.isNotEmpty()) RemoteSelectionBar()
        when (remoteView) {
            0 -> SearchScreen()
            1 -> OwnPlaylistsScreen()
            2 -> YandexLinkScreen()
            else -> AutoCollectionsScreen()
        }
    }

    @Composable
    private fun AutoCollectionsScreen() {
        val selected = selectedAutoCollection
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedAutoCollection = null }) { Text("←") }
                Text(selected.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            }
            Surface(shape = VakoUi.panel, color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(selected.name, fontWeight = FontWeight.SemiBold)
                    Text("По любимым трекам · ${selected.tracks.size} треков · обновлено ${
                        if (selected.updatedAt > 0) java.text.DateFormat.getDateInstance().format(java.util.Date(selected.updatedAt)) else "ранее"
                    }", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(value = autoRenameInput, onValueChange = { autoRenameInput = it },
                label = { Text("Название подборки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                try {
                    autoCollectionStore.rename(selected.name, autoRenameInput)
                    autoCollections = autoCollectionStore.all()
                    selectedAutoCollection = autoCollections.firstOrNull { it.name == autoRenameInput.trim() }
                    status = "Название сохранено на устройстве"
                } catch (e: Exception) { status = e.message ?: "Не удалось переименовать" }
            }, enabled = autoRenameInput.trim().isNotBlank() && autoRenameInput.trim() != selected.name) {
                Text("Переименовать")
            }
            Button(onClick = { refreshAutoCollection(selected) }, enabled = !autoCollectionBusy) { Text("Обновить подборку") }
            FilledTonalButton(onClick = { exportAutoCollection(selected) }, enabled = !autoCollectionBusy) {
                Text("Сохранить как плейлист Яндекс Музыки")
            }
            TextButton(onClick = {
                autoCollectionStore.delete(selected.name)
                autoCollections = autoCollectionStore.all()
                selectedAutoCollection = null
                status = "Подборка удалена с устройства"
            }) { Text("Удалить локальную подборку", color = MaterialTheme.colorScheme.error) }
            StatusLine()
            LazyColumn { items(selected.tracks, key = ::trackKey) { track -> TrackRow(track, selected.tracks) } }
        } else {
            Text("Автоподборки", fontWeight = FontWeight.SemiBold)
            Text("На основе любимых треков. До 10 подборок по 100 треков; хранятся только в Vako Player.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = autoCollectionName, onValueChange = { autoCollectionName = it },
                label = { Text("Название новой подборки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = ::generateAutoCollection,
                enabled = !autoCollectionBusy && autoCollections.size < 10 && autoCollectionName.isNotBlank()) {
                Text(if (autoCollectionBusy) "Подбираем…" else "Создать подборку")
            }
            StatusLine()
            LazyColumn { items(autoCollections, key = { it.name }) { collection ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).heightIn(min = 60.dp)
                    .vakoPanel(shape = VakoUi.row)
                    .clickable {
                    selectedAutoCollection = collection
                    autoRenameInput = collection.name
                }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(collection.name, fontWeight = FontWeight.SemiBold)
                        Text(trackCountLabel(collection.tracks.size), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", color = MaterialTheme.colorScheme.primary)
                }
            } }
        }
    }

    @Composable
    private fun RemoteSelectionBar() {
        val available = when (remoteView) {
            0 -> results
            1 -> if (showLikedTracks) likedTracks else remotePlaylistTracks
            2 -> playlist
            else -> selectedAutoCollection?.tracks.orEmpty()
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Выбрано: ${selectedRemoteTracks.size}", modifier = Modifier.weight(1f))
            TextButton(onClick = { selectedRemoteTracks = available.distinctBy(::trackKey) }) { Text("Все") }
            TextButton(onClick = { selectedRemoteTracks = emptyList() }) { Text("Снять") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                appendToQueue(selectedRemoteTracks.map(QueueItem::Remote), false)
                selectedRemoteTracks = emptyList()
            }) { Text("В очередь") }
            TextButton(onClick = {
                downloadTracks(selectedRemoteTracks, "Выбранные треки")
                selectedRemoteTracks = emptyList()
            }, enabled = !bulkDownloading && !downloading) { Text("Скачать") }
            TextButton(onClick = { openRemotePlaylistPicker(selectedRemoteTracks) }) { Text("В плейлист…") }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun OwnPlaylistsScreen() {
        val item = selectedRemotePlaylist
        if (showLikedTracks) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showLikedTracks = false }) { Text("←") }
                Text("Мне нравится", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                ActionIcon("⟳", "Обновить любимые треки", ::refreshLikedTracks, enabled = !likedLoading)
            }
            if (likedProgress.isNotBlank()) Text(likedProgress, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActionIcon("↓", "Скачать все любимые треки: ${likedTracks.size}",
                    { downloadTracks(likedTracks, "Мне нравится") },
                    enabled = likedTracks.isNotEmpty() && !bulkDownloading && !likedLoading)
                Text("Все ${likedTracks.size}", style = MaterialTheme.typography.bodySmall)
                if (bulkDownloading) TextButton(onClick = { cancelBulk = true }) { Text("Остановить") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(likedTracks, key = { "like:${trackKey(it)}" }) { track -> TrackRow(track, likedTracks) }
            }
        } else if (item == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Ваши плейлисты", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                ActionIcon("⟳", "Обновить плейлисты Яндекс Музыки", ::loadOwnPlaylists, enabled = !busy)
            }
            StatusLine()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "liked") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).vakoPanel(shape = VakoUi.row)
                        .clickable { openLikedTracks() }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("♥", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Мне нравится", fontWeight = FontWeight.SemiBold)
                            Text("Любимые треки Яндекс Музыки", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(ownPlaylists, key = { it.kind }) { listItem ->
                    var menuOpen by remember(listItem.owner, listItem.kind) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).heightIn(min = 56.dp)
                        .vakoPanel(shape = VakoUi.row)
                        .combinedClickable(onClick = { openOwnPlaylist(listItem) }, onLongClick = { menuOpen = true })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(listItem.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(trackCountLabel(listItem.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            ActionIcon("⋮", "Действия с плейлистом ${listItem.title}", { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Скачать плейлист") }, enabled = !busy && !bulkDownloading,
                                    onClick = { menuOpen = false; downloadOwnPlaylist(listItem) })
                                DropdownMenuItem(text = { Text("Переименовать") }, enabled = !busy,
                                    onClick = { menuOpen = false; openOwnPlaylist(listItem) })
                                DropdownMenuItem(text = { Text("Удалить плейлист") }, enabled = !busy,
                                    onClick = { menuOpen = false; pendingDeleteRemotePlaylist = listItem })
                            }
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedRemotePlaylist = null; remotePlaylistTracks = emptyList() }) { Text("←") }
                Text(item.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionIcon("↓", "Скачать весь плейлист: ${remotePlaylistTracks.size} треков", ::downloadOwnPlaylist,
                    enabled = !bulkDownloading && !busy && remotePlaylistTracks.isNotEmpty())
                Text("Все ${remotePlaylistTracks.size}", style = MaterialTheme.typography.bodySmall)
                if (bulkDownloading) TextButton(onClick = { cancelBulk = true }) { Text("Остановить") }
            }
            OutlinedTextField(
                value = renameInput, onValueChange = { renameInput = it },
                label = { Text("Название плейлиста") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = ::renameOwnPlaylist, enabled = !busy && renameInput.isNotBlank() && renameInput != item.title) {
                Text("Сохранить название в Яндекс Музыке")
            }
            StatusLine()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(remotePlaylistTracks, key = { index, track -> "$index:${track.trackId}" }) { index, track ->
                    TrackRow(track, remotePlaylistTracks, onRemove = { removeOwnTrack(index) })
                }
            }
        }
    }

    @Composable
    private fun SearchScreen() {
        if (selectedArtist.isNotBlank()) {
            TextButton(onClick = {
                selectedArtist = ""
                results = searchResultsBeforeArtist
                searchResultsBeforeArtist = emptyList()
            }) { Text("← К результатам поиска") }
        }
        VakoSearchField(query, onValueChange = { query = it }, label = "Исполнитель или трек",
            modifier = Modifier.fillMaxWidth(), onSearch = { if (!busy && query.isNotBlank()) search() })
        Spacer(Modifier.height(8.dp))
        Button(onClick = ::search, enabled = !busy && query.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Найти в Яндекс Музыке")
        }
        StatusLine()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (artists.isNotEmpty()) {
                item { Text("Исполнители", style = MaterialTheme.typography.titleMedium) }
                items(artists) { artist ->
                    Card(onClick = { openArtist(artist) }, modifier = Modifier.fillMaxWidth()) {
                        Text(artist.name, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (results.isNotEmpty()) {
                item {
                    Text(
                        if (selectedArtist.isBlank()) "Треки" else "Треки: $selectedArtist",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            items(results) { track -> TrackRow(track, results) }
        }
    }

    @Composable
    private fun YandexLinkScreen() {
        Text("Плейлист, альбом или трек", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = linkInput, onValueChange = { linkInput = it },
            label = { Text("Ссылка из Яндекс Музыки") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = ::openLink, enabled = !busy && linkInput.isNotBlank()) { Text("Открыть") }
        StatusLine()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (playlist.isNotEmpty()) {
                item { Text("Треки", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                items(playlist) { track -> TrackRow(track, playlist) }
            }
        }
    }

    @Composable
    private fun LibraryScreen() {
        var sortMenuOpen by remember { mutableStateOf(false) }
        var viewMenuOpen by remember { mutableStateOf(false) }
        VakoSectionHeader("Медиатека", if (scanning) "Сканирование…" else "${localSongs.size} файлов") {
            ActionIcon("☷", "Очередь воспроизведения", { showQueue = true; showSound = false })
            if (libraryView == 0) {
                ActionIcon("▦", "Вид локальной музыки", { viewMenuOpen = true })
                DropdownMenu(expanded = viewMenuOpen, onDismissRequest = { viewMenuOpen = false }) {
                    listOf("Список", "Крупная сетка", "Компактная сетка").forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            audioViewMode = index
                            getSharedPreferences("rikko_local", MODE_PRIVATE).edit().putInt("audio_view_mode", index).apply()
                            viewMenuOpen = false
                        })
                    }
                }
            }
            if (libraryView == 0 || libraryView == 2) {
                ActionIcon("⇅", "Сортировка медиатеки", { sortMenuOpen = true })
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    listOf("Название", "Исполнитель", "Дата добавления").forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(if (sortMode == index) "✓ $label" else label) }, onClick = {
                            sortMode = index
                            if (index == 2) {
                                sortDescending = true
                            }
                            getSharedPreferences("rikko_local", MODE_PRIVATE).edit()
                                .putInt("sort_mode", sortMode).putBoolean("sort_descending", sortDescending).apply()
                            sortMenuOpen = false
                        })
                    }
                    DropdownMenuItem(text = { Text(if (sortDescending) "По возрастанию" else "По убыванию") }, onClick = {
                        sortDescending = !sortDescending
                        getSharedPreferences("rikko_local", MODE_PRIVATE).edit()
                            .putBoolean("sort_descending", sortDescending).apply()
                        sortMenuOpen = false
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (libraryStore.hasAudioPermission()) {
                ActionIcon(if (scanning) "…" else "⟳", "Обновить медиатеку", ::refreshLibrary, enabled = !scanning)
            } else FilledTonalButton(onClick = { audioPermission.launch(libraryStore.audioPermission()) }) { Text("Разрешить доступ") }
            ActionIcon("＋", "Добавить аудиофайл", { picker.launch(arrayOf("audio/*")) })
        }
        if (!libraryStore.hasAudioPermission()) {
            Text(
                "Разрешите доступ к аудио, чтобы показать музыку с телефона и карты памяти.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf(0 to "Все") +
                (if (playlistsEnabled) listOf(1 to "Плейлисты") else emptyList()) +
                (if (foldersEnabled) listOf(2 to "Папки") else emptyList()) +
                (if (recentEnabled) listOf(3 to "Недавние") else emptyList()) +
                (if (albumsEnabled) listOf(4 to "Альбомы") else emptyList()) +
                (if (artistsEnabled) listOf(5 to "Исполнители") else emptyList()) +
                (if (genresEnabled) listOf(6 to "Жанры") else emptyList()) +
                (if (favoritesEnabled) listOf(7 to "Избранное") else emptyList())).forEach { (index, label) ->
                VakoChip(label, libraryView == index, onClick = {
                    libraryView = index; selectedPlaylist = ""; selectedBrowseGroup = ""
                    selectedLocalUris = emptySet(); browseSearch = ""
                })
            }
        }
        Spacer(Modifier.height(4.dp))
        if (selectedLocalUris.isNotEmpty()) {
            LocalSelectionBar()
        }
        when (libraryView) {
            0 -> AllSongsView()
            1 -> PlaylistsView()
            2 -> FoldersView()
            3 -> RecentView()
            4, 5, 6 -> BrowseGroupsView()
            else -> FavoritesView()
        }
    }

    @Composable
    private fun LocalSelectionBar() {
        val byUri = remember(localSongs) { localSongs.associateBy { it.uri.toString() } }
        val selected = remember(selectedLocalUris, byUri) { selectedLocalUris.mapNotNull(byUri::get) }
        val available = remember(libraryView, localSongs, localFilter, selectedPlaylist, selectedFolder, selectedBrowseGroup,
            includeSubfolders, recentUris, favoriteUris, libraryRevision) {
            when (libraryView) {
                0 -> localSongs.filter { localFilter.isBlank() || it.title.contains(localFilter, true) || it.artist.contains(localFilter, true) }
                1 -> libraryStore.playlistUris(selectedPlaylist).mapNotNull(byUri::get)
                2 -> {
                    val node = buildFolderTree(localSongs).find(selectedFolder)
                    if (node == null) emptyList() else if (includeSubfolders) node.allSongs() else node.songs.toList()
                }
                3 -> recentUris.mapNotNull(byUri::get)
                4, 5, 6 -> LibraryBrowse.groups(localSongs, libraryView).firstOrNull { it.key == selectedBrowseGroup }?.songs.orEmpty()
                else -> favoriteUris.mapNotNull(byUri::get)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Выбрано: ${selected.size}", modifier = Modifier.weight(1f))
            TextButton(onClick = { selectedLocalUris = available.map { it.uri.toString() }.toSet() }) { Text("Все") }
            TextButton(onClick = { selectedLocalUris = emptySet() }) { Text("Снять") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = {
                appendToQueue(selected.map(QueueItem::Local), false)
                selectedLocalUris = emptySet()
            }) { Text("В очередь") }
            TextButton(onClick = {
                appendToQueue(selected.map(QueueItem::Local), true)
                selectedLocalUris = emptySet()
            }) { Text("Следующими") }
            TextButton(onClick = { localPlaylistPickerSongs = selected; localPickerName = "" }) { Text("В плейлист…") }
            TextButton(onClick = {
                libraryStore.addFavorites(selected)
                favoriteUris = libraryStore.favoriteUris()
                selectedLocalUris = emptySet()
            }) { Text("В избранное") }
            TextButton(onClick = { pendingMoveSongs = selected; moveSearch = ""; editError = "" },
                enabled = selected.map(LocalSong::volume).distinct().size == 1) { Text("Переместить…") }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AllSongsView() {
        VakoSearchField(localFilter, onValueChange = { localFilter = it; selectedLocalUris = emptySet() },
            label = "Поиск в медиатеке", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        val filtered = remember(localSongs, localFilter, sortMode, sortDescending) {
            sortedSongs(localSongs.filter {
                localFilter.isBlank() || it.title.contains(localFilter, ignoreCase = true) ||
                    it.artist.contains(localFilter, ignoreCase = true)
            })
        }
        if (filtered.isEmpty()) {
            Text(if (scanning) "Сканирование медиатеки…" else
                "Аудиофайлов пока нет. Нажмите «Разрешить доступ» или «Добавить файл».",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (audioViewMode == 0) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(VakoUi.rowGap)) {
                    items(filtered, key = { it.uri.toString() }) { song -> LocalSongRow(song, source = filtered) }
                }
            } else {
                val columns = if (audioViewMode == 2 && LocalConfiguration.current.screenWidthDp >= 350) 3 else 2
                LazyVerticalGrid(columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    gridItems(filtered, key = { it.uri.toString() }) { song ->
                        val tileMenu = remember(song.uri.toString()) { mutableStateOf(false) }
                        Column(Modifier.vakoPanel(active = song.uri.toString() in selectedLocalUris,
                            shape = VakoUi.row).clip(VakoUi.row)) {
                            Box {
                                AudioThumbnails.Artwork(song, Modifier.fillMaxWidth().aspectRatio(1f)
                                    .combinedClickable(onClick = {
                                        if (selectedLocalUris.isNotEmpty()) toggleLocalSelection(song) else tapLocal(song, filtered)
                                    }, onLongClick = { toggleLocalSelection(song) }))
                                Box(Modifier.align(Alignment.TopEnd)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .78f))) {
                                    ActionIcon("⋮", "Действия с ${song.title}", { tileMenu.value = true })
                                }
                            }
                            LocalSongRow(song, source = filtered, tile = true, menuState = tileMenu)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaylistsView() {
        val revision = libraryRevision
        if (selectedPlaylist.isBlank()) {
            OutlinedTextField(
                value = newPlaylistName, onValueChange = { newPlaylistName = it },
                label = { Text("Новый плейлист") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val name = newPlaylistName.trim()
                if (libraryStore.createPlaylist(name)) {
                    playlistNames = libraryStore.playlistNames()
                    selectedPlaylist = name
                    localRenameInput = name
                    newPlaylistName = ""
                    libraryRevision++
                } else status = "Введите новое название плейлиста"
            }, enabled = newPlaylistName.isNotBlank()) { Text("Создать") }
            Spacer(Modifier.height(12.dp))
            if (playlistNames.isEmpty()) Text("Плейлистов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(playlistNames) { name ->
                    Surface(onClick = { selectedPlaylist = name; localRenameInput = name; selectedLocalUris = emptySet() },
                        modifier = Modifier.fillMaxWidth().vakoPanel(shape = VakoUi.row), shape = VakoUi.row,
                        color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("›", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedPlaylist = "" }) { Text("←") }
                Text(selectedPlaylist, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    libraryStore.deletePlaylist(selectedPlaylist)
                    selectedPlaylist = ""
                    playlistNames = libraryStore.playlistNames()
                    libraryRevision++
                }) { Text("Удалить") }
            }
            OutlinedTextField(
                value = localRenameInput, onValueChange = { localRenameInput = it },
                label = { Text("Название плейлиста") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = {
                if (libraryStore.renamePlaylist(selectedPlaylist, localRenameInput)) {
                    selectedPlaylist = localRenameInput.trim()
                    playlistNames = libraryStore.playlistNames()
                    libraryRevision++
                } else status = "Не удалось переименовать: такое имя уже есть"
            }, enabled = localRenameInput.isNotBlank() && localRenameInput != selectedPlaylist) { Text("Переименовать") }
            if (status.startsWith("Не удалось переименовать")) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val byUri = localSongs.associateBy { it.uri.toString() }
            val songs = remember(revision, selectedPlaylist, localSongs) {
                libraryStore.playlistUris(selectedPlaylist).mapNotNull(byUri::get)
            }
            if (songs.isEmpty()) Text("Добавьте треки из раздела «Все»", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(songs, key = { it.uri.toString() }) { song -> LocalSongRow(song, selectedPlaylist, songs) }
            }
        }
    }

    @Composable
    private fun FoldersView() {
        val root = remember(localSongs) { buildFolderTree(localSongs) }
        val node = root.find(selectedFolder) ?: root
        val atRoot = node.key.isEmpty()
        var currentFolderMenu by remember(node.key) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!atRoot) TextButton(onClick = {
                selectedFolder = node.key.substringBeforeLast('/', "")
                selectedLocalUris = emptySet()
            }) { Text("←") }
            Text(
                if (atRoot) "Накопители" else if ('/' !in node.key) volumeLabel(node.label) else node.label,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
            if (!atRoot) TextButton(onClick = { selectedFolder = ""; selectedLocalUris = emptySet() }) { Text("К началу") }
            if (!atRoot) {
                ActionIcon("⋮", "Действия с текущей папкой", { currentFolderMenu = true })
                DropdownMenu(expanded = currentFolderMenu, onDismissRequest = { currentFolderMenu = false }) {
                    DropdownMenuItem(text = { Text("Создать плейлист из папки") }, onClick = {
                        currentFolderMenu = false
                        openFolderPlaylistPicker(node)
                    })
                    if ('/' in node.key) {
                        DropdownMenuItem(text = { Text("Переименовать папку") }, onClick = {
                            currentFolderMenu = false; pendingRenameFolder = node; editName = node.label; editError = ""
                        })
                        DropdownMenuItem(text = { Text("Переместить папку…") }, onClick = {
                            currentFolderMenu = false; pendingMoveFolder = node; moveSearch = ""; editError = ""
                        })
                    }
                }
            }
        }
        if (!atRoot) {
            val volume = node.key.substringBefore('/')
            val subpath = node.key.substringAfter('/', "")
            Text(if (subpath.isBlank()) volumeLabel(volume) else "${volumeLabel(volume)}/$subpath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { includeSubfolders = !includeSubfolders; selectedLocalUris = emptySet() }) {
                Text(if (includeSubfolders) "Только эта папка" else "Показать треки из подпапок")
            }
            val branchSongs = remember(node, sortMode, sortDescending) { sortedSongs(node.allSongs()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionIcon("▶", "Играть папку", { branchSongs.firstOrNull()?.let { playLocal(it, branchSongs) } }, enabled = branchSongs.isNotEmpty())
                ActionIcon("☷", "Добавить папку в очередь", { appendToQueue(branchSongs.map(QueueItem::Local), false) }, enabled = branchSongs.isNotEmpty())
                ActionIcon("⤨", "Перемешать папку", {
                    val shuffled = branchSongs.shuffled()
                    shuffled.firstOrNull()?.let { playLocal(it, shuffled) }
                }, enabled = branchSongs.isNotEmpty())
            }
        }

        val childFolders = node.children.values.sortedWith(
            if (sortDescending) compareByDescending<FolderNode, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
            else compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
        val visibleSongs = remember(node, includeSubfolders, sortMode, sortDescending) {
            sortedSongs(if (atRoot) emptyList() else if (includeSubfolders) node.allSongs() else node.songs.toList())
        }
        if (childFolders.isEmpty() && visibleSongs.isEmpty()) {
            Text("Здесь пока нет музыки", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(childFolders, key = { "folder:${it.key}" }) { child ->
                    var folderMenuOpen by remember(child.key) { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).vakoPanel(shape = VakoUi.row)
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("▣", color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).clickable { selectedFolder = child.key; selectedLocalUris = emptySet() }) {
                            Text(if (atRoot) volumeLabel(child.label) else child.label,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${trackCountLabel(child.totalTracks)} · ${LibraryBrowse.duration(child.totalDurationMs)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ActionIcon("⋮", "Действия с папкой ${child.label}", { folderMenuOpen = true })
                        DropdownMenu(expanded = folderMenuOpen, onDismissRequest = { folderMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Создать плейлист из папки") }, onClick = {
                                folderMenuOpen = false
                                openFolderPlaylistPicker(child)
                            })
                            DropdownMenuItem(text = { Text("Играть папку") }, onClick = {
                                folderMenuOpen = false
                                val songs = sortedSongs(child.allSongs())
                                songs.firstOrNull()?.let { playLocal(it, songs) }
                            })
                            DropdownMenuItem(text = { Text("Добавить в очередь") }, onClick = {
                                folderMenuOpen = false
                                appendToQueue(child.allSongs().map(QueueItem::Local), false)
                            })
                            DropdownMenuItem(text = { Text("Перемешать папку") }, onClick = {
                                folderMenuOpen = false
                                val shuffled = child.allSongs().shuffled()
                                shuffled.firstOrNull()?.let { playLocal(it, shuffled) }
                            })
                            if ('/' in child.key) {
                                DropdownMenuItem(text = { Text("Переименовать папку") }, onClick = {
                                    folderMenuOpen = false; pendingRenameFolder = child; editName = child.label; editError = ""
                                })
                                DropdownMenuItem(text = { Text("Переместить папку…") }, onClick = {
                                    folderMenuOpen = false; pendingMoveFolder = child; moveSearch = ""; editError = ""
                                })
                            }
                        }
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { selectedFolder = child.key; selectedLocalUris = emptySet() })
                    }
                }
                items(visibleSongs, key = { "song:${it.uri}" }) { song ->
                    LocalSongRow(song, source = visibleSongs,
                        displayTitle = if (folderShowFileName) song.fileName.ifBlank { song.title } else song.title)
                }
            }
        }
    }

    private fun trackCountLabel(count: Int): String {
        val word = when {
            count % 100 in 11..14 -> "треков"
            count % 10 == 1 -> "трек"
            count % 10 in 2..4 -> "трека"
            else -> "треков"
        }
        return "$count $word"
    }

    @Composable
    private fun RecentView() {
        val byUri = localSongs.associateBy { it.uri.toString() }
        val recent = recentUris.mapNotNull(byUri::get)
        if (recent.isEmpty()) Text("Слушайте музыку — последние треки появятся здесь", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent, key = { it.uri.toString() }) { song -> LocalSongRow(song, source = recent) }
        }
    }

    @Composable
    private fun FavoritesView() {
        val favorites = remember(localSongs, favoriteUris) {
            localSongs.filter { it.uri.toString() in favoriteUris }
        }
        Text("Избранное · ${favorites.size}", fontWeight = FontWeight.SemiBold)
        if (favorites.isEmpty()) Text("Добавьте трек в избранное через его меню", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(favorites, key = { it.uri.toString() }) { song -> LocalSongRow(song, source = favorites) }
        }
    }

    @Composable
    private fun BrowseGroupsView() {
        val mode = libraryView
        val groups = remember(localSongs, mode) { LibraryBrowse.groups(localSongs, mode) }
        val selected = groups.firstOrNull { it.key == selectedBrowseGroup }
        if (selected != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selectedBrowseGroup = ""; selectedLocalUris = emptySet() }) { Text("←") }
                Text(selected.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold)
            }
            Text("${trackCountLabel(selected.songs.size)} · ${LibraryBrowse.duration(selected.durationMs)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ActionIcon("▶", "Играть подборку", { selected.songs.firstOrNull()?.let { playLocal(it, selected.songs) } })
                ActionIcon("⤨", "Перемешать подборку", {
                    val shuffled = selected.songs.shuffled()
                    shuffled.firstOrNull()?.let { playLocal(it, shuffled) }
                })
                ActionIcon("☷", "Добавить подборку в очередь", { appendToQueue(selected.songs.map(QueueItem::Local), false) })
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(selected.songs, key = { it.uri.toString() }) { song -> LocalSongRow(song, source = selected.songs) }
            }
            return
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VakoSearchField(browseSearch, onValueChange = { browseSearch = it },
                label = "Найти в разделе", modifier = Modifier.weight(1f))
            if (mode == LibraryBrowse.ALBUMS) TextButton(onClick = {
                albumGridEnabled = !albumGridEnabled
                appPreferences.setEnabled("album_grid", albumGridEnabled)
            }) { Text(if (albumGridEnabled) "Список" else "Сетка") }
        }
        Spacer(Modifier.height(6.dp))
        Text("${groups.size} групп", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val visible = remember(groups, browseSearch) { groups.filter { it.title.contains(browseSearch, true) || it.subtitle.contains(browseSearch, true) } }
        if (albumGridEnabled && mode == LibraryBrowse.ALBUMS) {
            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                gridItems(visible, key = { it.key }) { group ->
                    AlbumCard(group) { selectedBrowseGroup = group.key }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(visible, key = { it.key }) { group ->
                    BrowseGroupRow(group) { selectedBrowseGroup = group.key }
                }
            }
        }
    }

    @Composable
    private fun BrowseGroupRow(group: BrowseGroup, open: () -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).vakoPanel(shape = VakoUi.row)
            .clickable(onClick = open).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("♫", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(group.subtitle.takeIf(String::isNotBlank), trackCountLabel(group.songs.size),
                    LibraryBrowse.duration(group.durationMs)).joinToString(" · "), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun AlbumCard(group: BrowseGroup, open: () -> Unit) {
        val first = group.songs.firstOrNull()
        val context = LocalContext.current
        var art by remember(first?.uri) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(first?.uri) {
            art = first?.let { LocalArtwork.get(context, it)?.asImageBitmap() }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                if (art != null) Image(art!!, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
                else Text("♫", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(group.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
            Text("${group.songs.size} · ${group.subtitle}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun LocalSongRow(song: LocalSong, playlistName: String = "", source: List<LocalSong> = localSongs,
                             displayTitle: String = song.title, tile: Boolean = false,
                             menuState: MutableState<Boolean>? = null) {
        val menuOpenState: MutableState<Boolean> = menuState ?: remember(song.uri.toString()) { mutableStateOf(false) }
        var menuOpen by menuOpenState
        val selected = song.uri.toString() in selectedLocalUris
        Column(Modifier.padding(vertical = if (tile) 0.dp else 2.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (tile) 48.dp else if (compactRows) 56.dp else 72.dp)
                .then(if (tile) Modifier else Modifier.vakoPanel(active = selected, shape = VakoUi.row))
                .combinedClickable(
                onClick = { if (selectedLocalUris.isNotEmpty()) toggleLocalSelection(song) else tapLocal(song, source) },
                onLongClick = { toggleLocalSelection(song) }
            ).padding(start = if (tile) 6.dp else 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedLocalUris.isNotEmpty() && !tile) Checkbox(checked = selected, onCheckedChange = { toggleLocalSelection(song) })
                else if (!tile) Text(if (song.uri.toString() in favoriteUris) "♥" else "♫", color = MaterialTheme.colorScheme.primary)
                if (!tile) Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (selected && tile) "✓ $displayTitle" else displayTitle,
                        maxLines = if (tile) 2 else 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (song.artist.isNotBlank()) Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (song.durationMs > 0 && !tile) Text(LibraryBrowse.duration(song.durationMs),
                    modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!tile) ActionIcon("⋮", "Действия с ${song.title}", { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Выбрать") }, onClick = {
                        toggleLocalSelection(song)
                        menuOpen = false
                    })
                    DropdownMenuItem(text = { Text("Играть следующим") }, onClick = {
                        addToQueue(QueueItem.Local(song), true)
                        menuOpen = false
                    })
                    DropdownMenuItem(text = { Text("Добавить в очередь") }, onClick = {
                        addToQueue(QueueItem.Local(song), false)
                        menuOpen = false
                    })
                    DropdownMenuItem(text = { Text(if (song.uri.toString() in favoriteUris) "Убрать из избранного" else "В избранное") }, onClick = {
                        toggleFavorite(song)
                        menuOpen = false
                    })
                    if (playlistName.isNotBlank()) {
                        DropdownMenuItem(text = { Text("Выше") }, onClick = {
                            libraryStore.moveInPlaylist(playlistName, song.uri, -1)
                            libraryRevision++
                            menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Ниже") }, onClick = {
                            libraryStore.moveInPlaylist(playlistName, song.uri, 1)
                            libraryRevision++
                            menuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Убрать из плейлиста") }, onClick = {
                            libraryStore.removeFromPlaylist(playlistName, song.uri)
                            libraryRevision++
                            menuOpen = false
                        })
                    } else {
                        DropdownMenuItem(text = { Text("Добавить в плейлист…") }, onClick = {
                            localPlaylistPickerSongs = listOf(song)
                            localPickerName = ""
                            menuOpen = false
                        })
                    }
                    DropdownMenuItem(text = { Text("Переименовать файл") }, onClick = {
                        menuOpen = false; pendingRenameSong = song; editName = song.title; editError = ""
                    })
                    DropdownMenuItem(text = { Text("Переместить в папку…") }, onClick = {
                        menuOpen = false; pendingMoveSong = song; moveSearch = ""; editError = ""
                    })
                    DropdownMenuItem(text = { Text("Отправить файл…") }, onClick = {
                        menuOpen = false; shareSong(song)
                    })
                    DropdownMenuItem(text = { Text("Сведения о файле") }, onClick = {
                        menuOpen = false; pendingInfoSong = song
                    })
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val scroll = rememberScrollState()
        LaunchedEffect(settingsPage) { scroll.scrollTo(0) }
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            if (settingsPage == 0) {
                Text("Настройки Vako Player", style = MaterialTheme.typography.titleLarge)
                SettingLink("Разделы приложения", "Показывать только нужные вкладки", 1)
                SettingLink("Медиатека", "Сортировка, имена файлов и действия с треками", 2)
                SettingLink("Воспроизведение", "Повтор, перемешивание, таймер и эквалайзер", 3)
                SettingLink("Внешний вид", "Начальный экран, компактность и обложки", 4)
                SettingLink("Яндекс Музыка и аккаунт", "Подключение, загрузка и любимые треки", 5)
                if (videoEnabled) SettingLink("Видео", "Жесты и уровни яркости и громкости", 8)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = ::checkForUpdates, enabled = !checkingUpdate) { Text("Проверить обновление") }
                if (updateMessage.isNotBlank()) Text(updateMessage, style = MaterialTheme.typography.bodySmall)
                if (updateUrl.isNotBlank()) TextButton(onClick = {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                }) { Text("Открыть релиз на GitHub") }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { settingsPage = if (settingsPage == 7) 4 else 0 }) { Text("←") }
                    Text(listOf("", "Разделы приложения", "Медиатека", "Воспроизведение", "Внешний вид", "Яндекс Музыка и аккаунт", "Аккаунт", "Цвета темы", "Видео")[settingsPage],
                        style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(8.dp))
                when (settingsPage) {
                    8 -> {
                        var levels by remember { mutableStateOf(getSharedPreferences("vako_video", MODE_PRIVATE).getBoolean("show_gesture_levels", true)) }
                        SettingSwitch("Показывать уровни при свайпах", "Шкалы яркости окна, громкости видео и перемотки", levels) { enabled ->
                            levels = enabled
                            getSharedPreferences("vako_video", MODE_PRIVATE).edit().putBoolean("show_gesture_levels", enabled).apply()
                        }
                        Text("Свайп слева меняет яркость только окна видео, справа — громкость самого видео. Щипок меняет масштаб, двумя пальцами можно перемещать увеличенное изображение.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    1 -> {
                        SettingSwitch("Яндекс Музыка", "Поиск, плейлисты и загрузка", yandexEnabled) { enabled ->
                            yandexEnabled = enabled; appPreferences.setEnabled("yandex", enabled)
                            if (!enabled && activeTab == 1) activeTab = 3
                        }
                        SettingSwitch("Видео", "Локальные файлы, папки и полноэкранный плеер", videoEnabled) { enabled ->
                            videoEnabled = enabled; appPreferences.setEnabled("video", enabled)
                            if (!enabled && activeTab == 2) activeTab = 3
                        }
                        listOf(
                            Triple("Плейлисты", playlistsEnabled, 1), Triple("Папки", foldersEnabled, 2),
                            Triple("Недавние", recentEnabled, 3), Triple("Альбомы", albumsEnabled, 4),
                            Triple("Исполнители", artistsEnabled, 5), Triple("Жанры", genresEnabled, 6),
                            Triple("Избранное", favoritesEnabled, 7)
                        ).forEach { (label, checked, view) ->
                            SettingSwitch(label, if (view == 7) "Локальные отмеченные треки" else "Раздел медиатеки", checked) { enabled ->
                                val key = listOf("", "playlists", "folders", "recent", "albums", "artists", "genres", "favorites")[view]
                                appPreferences.setEnabled(key, enabled)
                                when (view) {
                                    1 -> playlistsEnabled = enabled; 2 -> foldersEnabled = enabled
                                    3 -> recentEnabled = enabled; 4 -> albumsEnabled = enabled
                                    5 -> artistsEnabled = enabled; 6 -> genresEnabled = enabled
                                    7 -> favoritesEnabled = enabled
                                }
                                if (!enabled && libraryView == view) libraryView = 0
                            }
                        }
                    }
                    2 -> {
                        Text("Сортировка треков", style = MaterialTheme.typography.titleMedium)
                        SettingChoice(listOf("Название", "Исполнитель", "Дата"), sortMode) {
                            sortMode = it
                            getSharedPreferences("rikko_local", MODE_PRIVATE).edit().putInt("sort_mode", it).apply()
                        }
                        SettingSwitch("По убыванию", "Обратный порядок списка", sortDescending) { enabled ->
                            sortDescending = enabled
                            getSharedPreferences("rikko_local", MODE_PRIVATE).edit().putBoolean("sort_descending", enabled).apply()
                        }
                        SettingSwitch("Имя файла в папках", "Вместо названия из тегов", folderShowFileName) { enabled ->
                            folderShowFileName = enabled; appPreferences.setEnabled("folder_file_name", enabled)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Нажатие на трек", style = MaterialTheme.typography.titleMedium)
                        SettingChoice(listOf("Играть", "В очередь", "Следующим"), tapAction) {
                            tapAction = it; appPreferences.setNumber("tap_action", it)
                        }
                        Text("Долгое нажатие выделяет трек для массовых действий.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = ::refreshLibrary) { Text("Обновить медиатеку") }
                    }
                    3 -> {
                        Text("Режим повтора", style = MaterialTheme.typography.titleMedium)
                        SettingChoice(listOf("Выкл.", "Все", "Один"), repeatMode) {
                            repeatMode = it; appPreferences.setNumber("repeat_mode", it)
                        }
                        SettingSwitch("Перемешивание", "Порядок треков в очереди", shuffle) { enabled ->
                            shuffle = enabled; appPreferences.setEnabled("shuffle", enabled); resetShuffleOrder()
                        }
                        TextButton(onClick = { showSound = true; showQueue = false }) { Text("Эквалайзер") }
                        TextButton(onClick = { showQueue = true; showSound = false }) { Text("Очередь") }
                        Text("Таймер сна", style = MaterialTheme.typography.titleMedium)
                        SettingChoice(listOf("Выкл.", "15 мин", "30 мин", "60 мин"),
                            when { sleepDeadline == 0L -> 0; sleepRemainingMs <= 15 * 60_000L -> 1;
                                sleepRemainingMs <= 30 * 60_000L -> 2; else -> 3 }) {
                            if (it == 0) cancelSleepTimer() else startSleepTimer(listOf(0, 15, 30, 60)[it])
                        }
                    }
                    4 -> {
                        SettingLink("Цвета темы", "Белая, серая, чёрная и свои цвета", 7)
                        Spacer(Modifier.height(12.dp))
                        Text("Начальный экран", style = MaterialTheme.typography.titleMedium)
                        SettingChoice(listOf("Медиатека", "Яндекс"), startTab) {
                            startTab = it; appPreferences.setNumber("start_tab", it)
                        }
                        if (albumsEnabled) SettingSwitch("Сетка обложек", "Альбомы в виде плиток", albumGridEnabled) { enabled ->
                            albumGridEnabled = enabled; appPreferences.setEnabled("album_grid", enabled)
                        }
                        SettingSwitch("Компактные строки", "Больше треков на экране", compactRows) { enabled ->
                            compactRows = enabled; appPreferences.setEnabled("compact_rows", enabled)
                        }
                        SettingSwitch("Обложка на весь квадрат", "Обрезать края вместо вписывания", coverCrop) { enabled ->
                            coverCrop = enabled; appPreferences.setEnabled("cover_crop", enabled)
                        }
                        SettingSwitch("Мини-спектр", "Показывать спектр в компактном плеере", miniSpectrumEnabled) { enabled ->
                            miniSpectrumEnabled = enabled
                            appPreferences.setEnabled("mini_spectrum", enabled)
                            if (enabled && prepared) startVisualizer()
                            else if (!enabled && !visualizerVisible) stopVisualizer()
                        }
                        SettingSwitch("Не гасить экран", "Пока Vako Player воспроизводит трек на экране", keepScreenOn) { enabled ->
                            keepScreenOn = enabled; appPreferences.setEnabled("keep_screen_on", enabled)
                        }
                    }
                    5, 6 -> {
                        Text("Качество загружаемых файлов", style = MaterialTheme.typography.titleMedium)
                        SettingChoice(listOf("Авто", "FLAC", "MP3"),
                            listOf(Quality.AUTO, Quality.FLAC, Quality.MP3_320).indexOf(downloadQuality)) {
                            downloadQuality = listOf(Quality.AUTO, Quality.FLAC, Quality.MP3_320)[it]
                            appPreferences.setDownloadQuality(downloadQuality)
                        }
                        Text("FLAC доступен, когда сервис предоставляет его для трека. Файлы сохраняются в общую папку Музыка/MusicDownloader.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            activeTab = 1; remoteView = 1; openLikedTracks()
                        }) { Text("Открыть «Мне нравится»") }
                        Spacer(Modifier.height(16.dp))
                        Text("Аккаунт", style = MaterialTheme.typography.titleMedium)
                        Text(if (token.isBlank()) "Для каталога Яндекса нужен токен вашего аккаунта."
                            else "Аккаунт подключён. Токен зашифрован на этом устройстве.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(value = tokenInput, onValueChange = { tokenInput = it },
                            label = { Text("Токен или адрес с access_token") },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = {
                                tokenStore.write(tokenInput)
                                token = tokenStore.read()
                                tokenInput = ""
                                ownPlaylists = emptyList(); selectedRemotePlaylist = null; remotePlaylistTracks = emptyList()
                                likedTracks = emptyList(); likedLoaded = false; showLikedTracks = false
                                status = if (token.isNotBlank()) "Аккаунт подключён" else "Введите токен"
                            }, enabled = tokenInput.isNotBlank()) { Text("Подключить") }
                            if (token.isNotBlank()) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    tokenStore.write("")
                                    token = ""
                                    ownPlaylists = emptyList(); selectedRemotePlaylist = null; remotePlaylistTracks = emptyList()
                                    likedTracks = emptyList(); likedLoaded = false; showLikedTracks = false
                                    status = "Аккаунт отключён"
                                }) { Text("Отключить") }
                            }
                        }
                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(YandexMusic.OAUTH_URL))) }) {
                            Text("Открыть вход Яндекса")
                        }
                        TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ym.marshal.dev/token/"))) }) {
                            Text("Как получить токен")
                        }
                        StatusLine()
                    }
                    7 -> ThemeSettings()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun SettingLink(title: String, description: String, page: Int) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).vakoPanel(shape = VakoUi.row)
            .clickable { settingsPage = page }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", color = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable
    private fun SettingChoice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                VakoChip(label, selected == index) { onSelect(index) }
            }
        }
    }

    @Composable
    private fun ThemeSettings() {
        Text("Основа", style = MaterialTheme.typography.titleMedium)
        SettingChoice(ThemePalette.bases, if (themeCustom) -1 else themeBase) { index ->
            themeBase = index
            themeCustom = false
            appPreferences.setNumber("theme_base", index)
            appPreferences.setEnabled("theme_custom", false)
        }
        Spacer(Modifier.height(12.dp))
        Text("Цвет элементов", style = MaterialTheme.typography.titleMedium)
        val light = themeBase == 0
        ThemePalette.accents.chunked(4).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { columnIndex, choice ->
                    val index = rowIndex * 4 + columnIndex
                    val swatch = if (light) choice.light else choice.dark
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(onClick = {
                            themeAccent = index
                            themeCustom = false
                            appPreferences.setNumber("theme_accent", index)
                            appPreferences.setEnabled("theme_custom", false)
                        }, color = swatch, shape = CircleShape,
                            border = if (!themeCustom && themeAccent == index)
                                BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface) else null,
                            modifier = Modifier.width(40.dp).height(40.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                if (!themeCustom && themeAccent == index) Text("✓",
                                    color = if (swatch.luminance() > .18f) Color.Black else Color.White)
                            }
                        }
                        Text(choice.name, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        SettingSwitch("Своя палитра", "Фон, панели, текст и элементы по кодам HEX", themeCustom) { enabled ->
            if (enabled && !themeCustom) {
                val preset = ThemePalette.resolve(themeBase, themeAccent, false, "", "", "", "")
                themeBackgroundHex = colorHex(preset.background)
                themeSurfaceHex = colorHex(preset.surface)
                themeTextHex = colorHex(preset.text)
                themeAccentHex = colorHex(preset.accent)
                appPreferences.setString("theme_background", themeBackgroundHex)
                appPreferences.setString("theme_surface", themeSurfaceHex)
                appPreferences.setString("theme_text", themeTextHex)
                appPreferences.setString("theme_accent_hex", themeAccentHex)
            }
            themeCustom = enabled
            appPreferences.setEnabled("theme_custom", enabled)
        }
        if (themeCustom) {
            HexColorField("Фон", themeBackgroundHex) {
                themeBackgroundHex = it; appPreferences.setString("theme_background", it)
            }
            HexColorField("Панели", themeSurfaceHex) {
                themeSurfaceHex = it; appPreferences.setString("theme_surface", it)
            }
            HexColorField("Текст", themeTextHex) {
                themeTextHex = it; appPreferences.setString("theme_text", it)
            }
            HexColorField("Акцент", themeAccentHex) {
                themeAccentHex = it; appPreferences.setString("theme_accent_hex", it)
            }
            Text("Формат: #RRGGBB. Неверный код временно заменяется цветом выбранной основы.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                themeCustom = false
                appPreferences.setEnabled("theme_custom", false)
            }) { Text("Вернуться к готовой теме") }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Предпросмотр", fontWeight = FontWeight.SemiBold)
                Text("Так будут выглядеть панели, текст и кнопки.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {}) { Text("Играть") }
            }
        }
    }

    @Composable
    private fun HexColorField(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(value = value, onValueChange = { onChange(it.take(7)) },
            label = { Text(label) }, singleLine = true,
            isError = ThemePalette.parseHex(value) == null, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
    }

    private fun colorHex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

    @Composable
    private fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).vakoPanel(shape = VakoUi.row)
            .clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun SaveQueueDialog() {
        AlertDialog(
            onDismissRequest = { showSaveQueue = false }, title = { Text("Сохранить очередь") },
            text = { Column {
                OutlinedTextField(saveQueueName, onValueChange = { saveQueueName = it; saveQueueError = "" },
                    label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${queue.size} треков${if (queue.any { it is QueueItem.Remote }) ", включая Яндекс Музыку" else ""}",
                    style = MaterialTheme.typography.bodySmall)
                if (saveQueueError.isNotBlank()) Text(saveQueueError, color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(onClick = {
                if (savedQueueStore.save(saveQueueName, queueSnapshot())) {
                    savedQueueNames = savedQueueStore.names()
                    status = "Очередь сохранена: ${saveQueueName.trim()}"
                    showSaveQueue = false
                } else saveQueueError = "Введите другое название"
            }, enabled = saveQueueName.isNotBlank() && queue.isNotEmpty()) { Text("Сохранить") } },
            dismissButton = { TextButton(onClick = { showSaveQueue = false }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun SavedQueuesDialog() {
        AlertDialog(
            onDismissRequest = { showSavedQueues = false }, title = { Text("Сохранённые очереди") },
            text = { Column {
                if (savedQueueNames.isEmpty()) Text("Пока нет сохранённых очередей")
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(savedQueueNames, key = { it }) { name ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                savedQueueStore.load(name)?.let { restoreQueue(it) }
                                showSavedQueues = false
                                status = "Очередь загружена: $name"
                            }, modifier = Modifier.weight(1f)) {
                                Text(name, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { pendingDeleteQueueName = name; showSavedQueues = false }) { Text("×") }
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { showSavedQueues = false }) { Text("Закрыть") } }
        )
    }

    @Composable
    private fun DeleteSavedQueueDialog() {
        val name = pendingDeleteQueueName
        AlertDialog(
            onDismissRequest = { pendingDeleteQueueName = "" },
            title = { Text("Удалить сохранённую очередь?") }, text = { Text(name) },
            confirmButton = { TextButton(onClick = {
                savedQueueStore.delete(name)
                savedQueueNames = savedQueueStore.names()
                pendingDeleteQueueName = ""
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { pendingDeleteQueueName = ""; showSavedQueues = true }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun ClearQueueDialog() {
        AlertDialog(
            onDismissRequest = { showClearQueue = false },
            title = { Text("Очистить очередь?") },
            text = { Text("Текущие ${queue.size} треков будут убраны. Сохранённые очереди останутся.") },
            confirmButton = { TextButton(onClick = {
                showClearQueue = false
                stopVisualizer()
                stopEqualizer()
                playbackService?.stop()
                queue = emptyList()
                queueIndex = -1
                currentTitle = ""
                currentArtist = ""
                cover = null
                prepared = false
                playing = false
                currentPositionMs = 0
                durationMs = 0
            }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { showClearQueue = false }) { Text("Отмена") } }
        )
    }

    @Composable
    private fun QueueScreen() {
        val listState = rememberLazyListState()
        var draggingIndex by remember { mutableStateOf(-1) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showQueue = false },
                modifier = Modifier.semantics { contentDescription = "Вернуться из очереди" }) { Text("←") }
            Text("Очередь · ${queue.size}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { showClearQueue = true }, enabled = queue.isNotEmpty()) {
                Text("Очистить", color = MaterialTheme.colorScheme.error)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { saveQueueName = ""; saveQueueError = ""; showSaveQueue = true },
                enabled = queue.isNotEmpty()) { Text("Сохранить очередь") }
            TextButton(onClick = { savedQueueNames = savedQueueStore.names(); showSavedQueues = true }) { Text("Загрузить") }
        }
        Text("Удерживайте трек и перетащите для смены порядка", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (queue.isEmpty()) Text("Очередь пуста", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(queue, key = { index, _ -> index }) { index, item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .vakoPanel(active = index == queueIndex || index == draggingIndex, shape = VakoUi.row)
                    .padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(30.dp).height(56.dp).pointerInput(index, queue.size) {
                        var draggedItem: QueueItem? = null
                        var originalOffset = 0
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedItem = queue.getOrNull(index)
                                originalOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset ?: 0
                                draggingIndex = index
                            },
                            onDragEnd = { draggingIndex = -1 },
                            onDragCancel = { draggingIndex = -1 },
                            onDrag = { change, _ ->
                                val current = queue.indexOfFirst { it === draggedItem }
                                val y = originalOffset + change.position.y.toInt()
                                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    y in it.offset until (it.offset + it.size)
                                }?.index ?: -1
                                if (current >= 0 && target >= 0 && current != target) {
                                    reorderQueue(current, target)
                                    draggingIndex = target
                                }
                                change.consume()
                            }
                        )
                    }, contentAlignment = Alignment.Center) {
                        Text("≡", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { contentDescription = "Перетащить трек ${index + 1}" })
                    }
                    Text("${index + 1}",
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f).clickable {
                        queueIndex = index
                        resetShuffleOrder()
                        playQueueItem()
                    }) {
                        Text((if (index == queueIndex) "▶ " else "") + when (item) { is QueueItem.Local -> item.song.title; is QueueItem.Remote -> item.track.title },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                            color = if (index == queueIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(when (item) { is QueueItem.Local -> item.song.artist; is QueueItem.Remote -> item.track.artist },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item is QueueItem.Local && item.song.durationMs > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(LibraryBrowse.duration(item.song.durationMs),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { moveQueueItem(index, -1) }, enabled = index > 0,
                        modifier = Modifier.semantics { contentDescription = "Поднять трек ${index + 1} в очереди" }) {
                        Text("↑")
                    }
                    TextButton(onClick = { moveQueueItem(index, 1) }, enabled = index < queue.lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Опустить трек ${index + 1} в очереди" }) {
                        Text("↓")
                    }
                    TextButton(onClick = { removeQueueItem(index) }, enabled = index != queueIndex,
                        modifier = Modifier.semantics { contentDescription = "Удалить трек ${index + 1} из очереди" }) {
                        Text("×", color = if (index != queueIndex) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    private fun SoundScreen() {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showSound = false }) { Text("←") }
            Text("Настройка звука", fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = {
                eqEnabled = !eqEnabled
                getSharedPreferences("rikko_audio", MODE_PRIVATE).edit().putBoolean("eq_enabled", eqEnabled).apply()
                if (eqEnabled) startEqualizer() else stopEqualizer()
            }, enabled = prepared) { Text(if (eqEnabled) "Эквалайзер: включён" else "Включить эквалайзер") }
            if (eqEnabled) TextButton(onClick = { eqLevels.indices.forEach { setEqLevel(it, 0) } }) { Text("Сбросить") }
        }
        if (!prepared) Text("Включите трек, чтобы настроить звук", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (eqEnabled && eqLevels.isNotEmpty()) {
            Text("Полосы зависят от аудиодвижка телефона", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(eqLevels.indices.toList()) { index ->
                    val frequency = eqFrequencies.getOrNull(index) ?: 0
                    val label = if (frequency >= 1000) "${frequency / 1000} кГц" else "$frequency Гц"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = eqLevels[index].toFloat(),
                            onValueChange = { setEqLevel(index, it.toInt().toShort()) },
                            valueRange = eqRange.first.toFloat()..eqRange.second.toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                        Text("${eqLevels[index] / 100} дБ", modifier = Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (status.startsWith("Эквалайзер недоступен")) Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun StatusLine() {
        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (failedMediaEdits.isNotEmpty()) {
            TextButton(onClick = { startMediaEdits(failedMediaEdits, pendingMediaDescription) }) {
                Text("Повторить только неуспешные (${failedMediaEdits.size})")
            }
        }
        if (downloading) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        if (bulkDownloading && bulkTotal > 0) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { bulkDone.toFloat() / bulkTotal },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ActionIcon(symbol: String, label: String, onClick: () -> Unit,
                           enabled: Boolean = true, selected: Boolean = false, large: Boolean = false) {
        IconButton(onClick = onClick, enabled = enabled,
            modifier = Modifier.width(if (large) 50.dp else 48.dp).height(48.dp)
                .then(if (selected) Modifier.vakoPanel(active = true, shape = VakoUi.row) else Modifier)
                .semantics { contentDescription = label }) {
            Text(symbol, fontSize = if (large) 34.sp else 27.sp, lineHeight = if (large) 35.sp else 28.sp,
                color = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .35f)
                    else if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TrackRow(track: Track, source: List<Track>, onRemove: (() -> Unit)? = null) {
        var menuOpen by remember(track.trackId) { mutableStateOf(false) }
        val selected = selectedRemoteTracks.any { trackKey(it) == trackKey(track) }
        Column(Modifier.padding(vertical = 2.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = if (compactRows) 58.dp else 74.dp)
                .vakoPanel(active = selected, shape = VakoUi.row)
                .combinedClickable(
                onClick = { if (selectedRemoteTracks.isNotEmpty()) toggleRemoteSelection(track) else tapRemote(track, source) },
                onLongClick = { toggleRemoteSelection(track) }
            ).padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedRemoteTracks.isNotEmpty()) Checkbox(checked = selected, onCheckedChange = { toggleRemoteSelection(track) })
                Column(Modifier.weight(1f)) {
                    Text(track.title.ifBlank { "Без названия" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selectedRemoteTracks.isEmpty()) ActionIcon("▶", "Играть ${track.title}", { playRemote(track, source) }, large = true)
                ActionIcon("↓", "Скачать ${track.title}", { download(track) }, enabled = !downloading && !bulkDownloading, large = true)
                ActionIcon("⋮", "Действия с ${track.title}", { menuOpen = true }, large = true)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Выбрать") }, onClick = {
                        toggleRemoteSelection(track)
                        menuOpen = false
                    })
                    DropdownMenuItem(text = { Text("Играть следующим") }, onClick = {
                        addToQueue(QueueItem.Remote(track), true)
                        menuOpen = false
                    })
                    DropdownMenuItem(text = { Text("Добавить в очередь") }, onClick = {
                        addToQueue(QueueItem.Remote(track), false)
                        menuOpen = false
                    })
                    if (onRemove != null) DropdownMenuItem(text = { Text("Убрать из плейлиста") }, onClick = {
                        menuOpen = false
                        onRemove()
                    }, enabled = !busy)
                    DropdownMenuItem(text = { Text("Добавить в плейлист…") }, onClick = {
                        menuOpen = false
                        openRemotePlaylistPicker(listOf(track))
                    })
                }
            }
        }
    }

    @Composable
    private fun CurrentRemoteActions() {
        val track = (queue.getOrNull(queueIndex) as? QueueItem.Remote)?.track ?: return
        var open by remember(trackKey(track)) { mutableStateOf(false) }
        val source = queueSourcePlaylist
        ActionIcon("⋮", "Действия с текущим треком", { open = true }, large = true)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Добавить в плейлист…") }, onClick = {
                open = false
                openRemotePlaylistPicker(listOf(track))
            })
            DropdownMenuItem(text = { Text("Играть следующим") }, onClick = {
                open = false
                addToQueue(QueueItem.Remote(track), true)
            })
            DropdownMenuItem(text = { Text("Добавить в очередь") }, onClick = {
                open = false
                addToQueue(QueueItem.Remote(track), false)
            })
            DropdownMenuItem(text = { Text("Скачать") }, onClick = { open = false; download(track) },
                enabled = !downloading && !bulkDownloading)
            if (source != null) DropdownMenuItem(text = { Text("Убрать из «${source.title}»") }, onClick = {
                open = false
                val index = remotePlaylistTracks.indexOfFirst { trackKey(it) == trackKey(track) }
                if (selectedRemotePlaylist?.kind == source.kind && index >= 0) removeOwnTrack(index)
                else status = "Откройте плейлист для удаления трека"
            }, enabled = !busy)
        }
    }

    @Composable
    private fun PlayerBar() {
        var pendingSeek by remember(queueIndex, currentTitle) { mutableStateOf<Float?>(null) }
        Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface, shape = VakoUi.panel,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).vakoPanel(active = true)) {
            Column {
            Row(Modifier.fillMaxWidth().height(66.dp).clickable { showNowPlaying = true }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                    cover?.let { art ->
                        Image(art, contentDescription = "Обложка трека", modifier = Modifier.width(42.dp).height(42.dp), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(currentArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    if (miniSpectrumEnabled && playing && visualizer != null && spectrum.isNotEmpty())
                        MiniSpectrum(Modifier.width(30.dp).height(22.dp))
                    if (queue.getOrNull(queueIndex) is QueueItem.Remote) CurrentRemoteActions()
                    ActionIcon("⏮", "Предыдущий трек", ::previousTrack)
                    ActionIcon(if (playing) "Ⅱ" else "▶", if (playing) "Пауза" else "Играть", ::togglePlayback,
                        enabled = prepared || queue.isNotEmpty(), selected = true)
                    ActionIcon("⏭", "Следующий трек", { nextTrack() })
            }
            if (durationMs > 0) Slider(
                value = pendingSeek ?: (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                onValueChange = { pendingSeek = it },
                onValueChangeFinished = {
                    pendingSeek?.let { fraction ->
                        val position = (fraction * durationMs).toInt().coerceIn(0, durationMs)
                        currentPositionMs = position
                        playbackService?.seekTo(position)
                    }
                    pendingSeek = null
                },
                enabled = prepared,
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
            )
            }
        }
    }

    @Composable
    private fun MiniSpectrum(modifier: Modifier = Modifier) {
        val data = spectrum
        val accent = MaterialTheme.colorScheme.primary
        Canvas(modifier) {
            val bars = 8
            val width = size.width / bars
            for (i in 0 until bars) {
                val re = data.getOrElse(i * 6) { 0 }.toInt()
                val im = data.getOrElse(i * 6 + 1) { 0 }.toInt()
                val magnitude = (sqrt((re * re + im * im).toFloat()) / 180f).coerceIn(.12f, 1f)
                val height = size.height * magnitude
                drawRect(accent, Offset(i * width, size.height - height), Size(width * .65f, height))
            }
        }
    }

    @Composable
    private fun NowPlayingScreen(modifier: Modifier = Modifier) {
        var timerMenuOpen by remember { mutableStateOf(false) }
        Column(modifier.padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::closeNowPlaying) { Text("←") }
                Text("Сейчас играет", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                CurrentRemoteActions()
                Text("${queueIndex + 1}/${queue.size}", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().weight(1f).vakoPanel(shape = VakoUi.shapes.extraLarge)
                    .pointerInput(visualizerVisible, visualizerStyle) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            if (kotlin.math.abs(dragged) > 48.dp.toPx()) changeVisualizerPage(if (dragged < 0f) 1 else -1)
                        },
                        onHorizontalDrag = { change, amount -> dragged += amount; change.consume() }
                    )
                }, contentAlignment = Alignment.Center
            ) {
                if (visualizerVisible && (visualizer != null || spectrum.isNotEmpty()))
                    VisualizerView(Modifier.fillMaxWidth().height(260.dp))
                else cover?.let { art ->
                    Image(art, contentDescription = "Обложка трека", modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = if (coverCrop) ContentScale.Crop else ContentScale.Fit)
                } ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("♫", fontSize = 82.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = .8f))
                    Text("Нет обложки", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("${if (visualizerVisible && (visualizer != null || spectrum.isNotEmpty())) listOf("Спектр", "Осциллограф", "Круг", "Зеркало", "Искры", "Ленты")[visualizerStyle] else "Обложка"} · смахните изображение влево или вправо",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
            Text(currentTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(currentArtist, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Slider(
                value = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    if (prepared && durationMs > 0) {
                        currentPositionMs = (fraction * durationMs).toInt()
                        playbackService?.seekTo(currentPositionMs)
                    }
                },
                enabled = prepared && durationMs > 0, modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(currentPositionMs), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(if (durationMs > 0) formatTime(durationMs) else "—:—", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                ActionIcon("⏮", "Предыдущий трек", ::previousTrack)
                Surface(shape = VakoUi.row, color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary) {
                    IconButton(onClick = ::togglePlayback, enabled = prepared || queue.isNotEmpty(),
                        modifier = Modifier.width(64.dp).height(56.dp).semantics {
                            contentDescription = if (playing) "Пауза" else "Играть"
                        }) {
                        Text(if (playing) "Ⅱ" else "▶", fontSize = 32.sp,
                            color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                ActionIcon("⏭", "Следующий трек", { nextTrack() })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ActionIcon("⤨", "Перемешать", ::toggleShuffle, selected = shuffle)
                ActionIcon(if (repeatMode == 2) "↻₁" else "↻", listOf("Повтор выключен", "Повтор всех", "Повтор одного")[repeatMode],
                    ::cycleRepeatMode, selected = repeatMode != 0)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ActionIcon("☷", "Очередь", { closeNowPlaying(); showQueue = true; showSound = false })
                ActionIcon("♫", "Настройки звука", { closeNowPlaying(); showSound = true; showQueue = false })
                ActionIcon("◷", if (sleepDeadline > 0L) "Таймер: ${((sleepRemainingMs + 59_999L) / 60_000L)} мин" else "Таймер сна",
                    { timerMenuOpen = true }, selected = sleepDeadline > 0L)
                DropdownMenu(expanded = timerMenuOpen, onDismissRequest = { timerMenuOpen = false }) {
                    listOf(15, 30, 60).forEach { minutes ->
                        DropdownMenuItem(text = { Text("Остановить через $minutes мин") }, onClick = {
                            startSleepTimer(minutes)
                            timerMenuOpen = false
                        })
                    }
                    if (sleepDeadline > 0L) DropdownMenuItem(text = { Text("Отменить таймер") }, onClick = {
                        cancelSleepTimer()
                        timerMenuOpen = false
                    })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    @Composable
    private fun VisualizerView(modifier: Modifier = Modifier) {
        val wave = waveform
        val fft = spectrum
        val style = visualizerStyle
        val themeAccentColor = MaterialTheme.colorScheme.primary
        Canvas(modifier) {
            val green = themeAccentColor
            if ((style == 1 || style == 5) && wave.isNotEmpty()) {
                val step = size.width / (wave.size - 1).coerceAtLeast(1)
                for (i in 1 until wave.size) {
                    val y1 = size.height / 2f + ((wave[i - 1].toInt() and 255) - 128) / 128f * size.height * .42f
                    val y2 = size.height / 2f + ((wave[i].toInt() and 255) - 128) / 128f * size.height * .42f
                    drawLine(green, Offset((i - 1) * step, y1), Offset(i * step, y2), strokeWidth = 2.dp.toPx())
                    if (style == 5) {
                        drawLine(green.copy(alpha = .35f), Offset((i - 1) * step, size.height - y1),
                            Offset(i * step, size.height - y2), strokeWidth = 3.dp.toPx())
                        drawLine(green.copy(alpha = .18f), Offset((i - 1) * step, y1 * .65f + size.height * .175f),
                            Offset(i * step, y2 * .65f + size.height * .175f), strokeWidth = 5.dp.toPx())
                    }
                }
            } else if (fft.isNotEmpty()) {
                val bars = 48
                val width = size.width / bars
                val energies = FloatArray(bars) { i ->
                    val re = fft.getOrElse(i * 2) { 0 }.toInt()
                    val im = fft.getOrElse(i * 2 + 1) { 0 }.toInt()
                    sqrt((re * re + im * im).toFloat())
                }
                val peak = energies.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                for (i in 0 until bars) {
                    val energy = energies[i] * .65f + energies.getOrElse(i - 1) { 0f } * .175f +
                        energies.getOrElse(i + 1) { 0f } * .175f
                    val magnitude = sqrt((energy / peak).coerceIn(0f, 1f))
                    if (style == 0) {
                        val height = (magnitude * size.height).coerceAtLeast(2.dp.toPx())
                        drawRect(green, Offset(i * width, size.height - height), Size(width * .72f, height))
                    } else if (style == 3) {
                        val height = (magnitude * size.height * .43f).coerceAtLeast(2.dp.toPx())
                        drawRect(green, Offset(i * width, size.height / 2f - height), Size(width * .72f, height))
                        drawRect(green.copy(alpha = .45f), Offset(i * width, size.height / 2f + 2.dp.toPx()),
                            Size(width * .72f, height))
                    } else if (style == 4) {
                        val angle = i * 2.0 * Math.PI / bars
                        val radius = size.height * (.20f + magnitude * .28f)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(green.copy(alpha = .35f + magnitude * .65f),
                            radius = (2.dp.toPx() + magnitude * 8.dp.toPx()),
                            center = Offset(center.x + cos(angle).toFloat() * radius,
                                center.y + sin(angle).toFloat() * radius))
                    } else {
                        val angle = i * 2.0 * Math.PI / bars
                        val radius = size.height * .22f
                        val length = magnitude * size.height * .28f + 2.dp.toPx()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val start = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
                        val end = Offset(center.x + cos(angle).toFloat() * (radius + length), center.y + sin(angle).toFloat() * (radius + length))
                        drawLine(green, start, end, strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
    }
}
