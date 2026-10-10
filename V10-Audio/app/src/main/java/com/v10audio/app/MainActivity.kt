package com.v10audio.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF0B0B0D)
private val Panel = Color(0xFF17171B)
private val PanelRaised = Color(0xFF202025)
private val Paper = Color(0xFFF5F4F2)
private val Subtle = Color(0xFF9C9CA5)
private val V10Red = Color(0xFFE74B59)

private data class LocalTrack(
    val title: String,
    val artist: String,
    val album: String,
    val uri: String,
    val duration: Long = 0L
)

class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                runCatching { mediaController = future.get() }
            }, ContextCompat.getMainExecutor(this))
        }
        setContent { V10Root(controllerProvider = { mediaController }) }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V10Root(controllerProvider: () -> MediaController?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("v10_audio_preferences", 0) }
    val scope = rememberCoroutineScope()
    val tracks = remember { mutableStateListOf<LocalTrack>() }
    val favorites = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet())
        }
    }

    var player by remember { mutableStateOf<MediaController?>(null) }
    var screen by remember { mutableStateOf("Home") }
    var search by remember { mutableStateOf("") }
    var libraryFilter by remember { mutableStateOf("All songs") }
    var activeUri by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var scanMessage by remember { mutableStateOf("Your music stays on this device.") }
    var isScanning by remember { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var settingsDynamic by remember { mutableStateOf(prefs.getBoolean("dynamic_colours", true)) }
    var playerStyle by remember { mutableStateOf(prefs.getString("player_style", "Modern") ?: "Modern") }
    var backgroundStyle by remember { mutableStateOf(prefs.getString("player_background", "Blurred") ?: "Blurred") }
    var accentName by remember { mutableStateOf(prefs.getString("accent", "Rose") ?: "Rose") }
    var sortOrder by remember { mutableStateOf(prefs.getString("sort_order", "Title") ?: "Title") }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showScanInfo by remember { mutableStateOf(false) }

    val accent = when (accentName) {
        "Violet" -> Color(0xFFAA91FF)
        "Blue" -> Color(0xFF70B8FF)
        "Mint" -> Color(0xFF72D6B2)
        "Amber" -> Color(0xFFFFC16C)
        else -> V10Red
    }
    val currentIndex = tracks.indexOfFirst { it.uri == activeUri }
    val currentTrack = tracks.getOrNull(currentIndex)
    val displayedTracks = remember(tracks.size, search, libraryFilter, favorites.size, sortOrder, screen) {
        val base = when {
            libraryFilter == "Favorites" -> tracks.filter { favorites.contains(it.uri) }
            libraryFilter == "Albums" -> tracks
            libraryFilter == "Artists" -> tracks
            else -> tracks.toList()
        }.filter {
            search.isBlank() || it.title.contains(search, true) ||
                it.artist.contains(search, true) || it.album.contains(search, true)
        }
        when (sortOrder) {
            "Artist" -> base.sortedBy { it.artist.lowercase() }
            "Album" -> base.sortedBy { it.album.lowercase() }
            else -> base.sortedBy { it.title.lowercase() }
        }
    }

    fun persistSettings() {
        prefs.edit()
            .putBoolean("dynamic_colours", settingsDynamic)
            .putString("player_style", playerStyle)
            .putString("player_background", backgroundStyle)
            .putString("accent", accentName)
            .putString("sort_order", sortOrder)
            .putStringSet("favorites", favorites.toSet())
            .apply()
    }

    suspend fun scanDevice() {
        isScanning = true
        scanMessage = "Scanning audio stored on your phone…"
        val found = withContext(Dispatchers.IO) {
            val result = mutableListOf<LocalTrack>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.IS_MUSIC
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            context.contentResolver.query(
                collection, projection, selection, null,
                if (sortOrder == "Artist") "artist COLLATE NOCASE ASC, title COLLATE NOCASE ASC"
                else if (sortOrder == "Album") "album COLLATE NOCASE ASC, title COLLATE NOCASE ASC"
                else "title COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result.add(
                        LocalTrack(
                            title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title",
                            artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
                            album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown album",
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            duration = cursor.getLong(durationCol)
                        )
                    )
                }
            }
            result
        }
        tracks.clear()
        tracks.addAll(found)
        isScanning = false
        scanMessage = if (found.isEmpty()) {
            "No music found yet. Download audio to your phone, then scan again."
        } else {
            "${found.size} local songs found on this device"
        }
    }

    fun playAt(track: LocalTrack) {
        val controller = player ?: run {
            scanMessage = "Player is starting. Try again in a moment."
            return
        }
        val items = tracks.map { trackItem ->
            MediaItem.Builder()
                .setUri(trackItem.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(trackItem.title)
                        .setArtist(trackItem.artist)
                        .setAlbumTitle(trackItem.album)
                        .build()
                )
                .build()
        }
        val index = tracks.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
        controller.setMediaItems(items, index, 0L)
        controller.prepare()
        controller.play()
        activeUri = track.uri
        playerOpen = true
    }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val requestAudio = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { scanDevice() }
        else scanMessage = "Audio permission is needed to show music stored on your phone."
    }

    LaunchedEffect(Unit) {
        for (attempt in 0..80) {
            controllerProvider()?.let { player = it; return@let }
            delay(150)
        }
        if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) {
            scanDevice()
        }
    }
    LaunchedEffect(player) {
        val controller = player ?: return@LaunchedEffect
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                activeUri = mediaItem?.localConfiguration?.uri?.toString().orEmpty()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                totalDuration = controller.duration.takeIf { it > 0 } ?: 0L
            }
        }
        controller.addListener(listener)
        try {
            while (true) {
                currentPosition = controller.currentPosition.coerceAtLeast(0L)
                totalDuration = controller.duration.takeIf { it > 0L } ?: currentTrack?.duration ?: 0L
                isPlaying = controller.isPlaying
                delay(350)
            }
        } finally {
            controller.removeListener(listener)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent,
            onPrimary = Ink,
            background = Ink,
            surface = Panel,
            onSurface = Paper,
            secondary = accent
        )
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            Column(Modifier.fillMaxSize()) {
                when (screen) {
                    "Home" -> HomePage(
                        tracks = tracks,
                        favorites = favorites,
                        currentTrack = currentTrack,
                        playing = isPlaying,
                        accent = accent,
                        scanMessage = scanMessage,
                        onOpenSearch = { screen = "Search" },
                        onOpenLibrary = { screen = "Library"; libraryFilter = "All songs" },
                        onOpenSettings = { screen = "Settings" },
                        onPlay = { playAt(it) },
                        onTogglePlay = {
                            if (isPlaying) player?.pause() else if (player?.mediaItemCount ?: 0 > 0) player?.play()
                        }
                    )
                    "Library" -> LibraryPage(
                        tracks = displayedTracks,
                        allTracks = tracks,
                        favorites = favorites,
                        filter = libraryFilter,
                        search = search,
                        sortOrder = sortOrder,
                        accent = accent,
                        onFilter = { libraryFilter = it },
                        onSearch = { search = it },
                        onSort = { sortOrder = it; persistSettings() },
                        onPlay = { playAt(it) },
                        onFavorite = {
                            if (favorites.contains(it.uri)) favorites.remove(it.uri) else favorites.add(it.uri)
                            persistSettings()
                        },
                        onScan = {
                            if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
                                scope.launch { scanDevice() }
                            else requestAudio.launch(audioPermission)
                        }
                    )
                    "Search" -> SearchPage(
                        query = search,
                        tracks = displayedTracks,
                        favorites = favorites,
                        accent = accent,
                        onQuery = { search = it },
                        onPlay = { playAt(it) },
                        onFavorite = {
                            if (favorites.contains(it.uri)) favorites.remove(it.uri) else favorites.add(it.uri)
                            persistSettings()
                        }
                    )
                    else -> SettingsPage(
                        dynamic = settingsDynamic,
                        playerStyle = playerStyle,
                        backgroundStyle = backgroundStyle,
                        accentName = accentName,
                        sortOrder = sortOrder,
                        accent = accent,
                        scanMessage = scanMessage,
                        onDynamic = { settingsDynamic = it; persistSettings() },
                        onPlayerStyle = { playerStyle = it; persistSettings() },
                        onBackground = { backgroundStyle = it; persistSettings() },
                        onAccent = { accentName = it; settingsDynamic = false; persistSettings() },
                        onSort = { sortOrder = it; persistSettings() },
                        onScan = {
                            if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
                                scope.launch { scanDevice() }
                            else requestAudio.launch(audioPermission)
                        }
                    )
                }
                Spacer(Modifier.weight(1f))
                if (currentTrack != null) {
                    MiniPlayer(
                        track = currentTrack,
                        playing = isPlaying,
                        accent = accent,
                        onClick = { playerOpen = true },
                        onPlayPause = { if (isPlaying) player?.pause() else player?.play() },
                        onNext = { player?.seekToNextMediaItem() }
                    )
                }
                BottomNavigation(screen = screen, accent = accent, onNavigate = { screen = it })
            }
        }
    }

    if (playerOpen && currentTrack != null) {
        Dialog(
            onDismissRequest = { playerOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            FullPlayer(
                track = currentTrack,
                playing = isPlaying,
                position = currentPosition,
                duration = totalDuration.takeIf { it > 0 } ?: currentTrack.duration,
                accent = accent,
                style = playerStyle,
                background = backgroundStyle,
                favorite = favorites.contains(currentTrack.uri),
                onFavorite = {
                    if (favorites.contains(currentTrack.uri)) favorites.remove(currentTrack.uri) else favorites.add(currentTrack.uri)
                    persistSettings()
                },
                onBack = { playerOpen = false },
                onPlayPause = { if (isPlaying) player?.pause() else player?.play() },
                onPrevious = { player?.seekToPreviousMediaItem() },
                onNext = { player?.seekToNextMediaItem() },
                onSeek = { player?.seekTo(it.toLong()) },
                onQueue = { queueOpen = true }
            )
        }
    }
    if (queueOpen) {
        AlertDialog(
            onDismissRequest = { queueOpen = false },
            title = { Text("Up next") },
            text = {
                Column(Modifier.heightIn(max = 360.dp)) {
                    LazyColumn {
                        items(tracks) { track ->
                            Row(
                                Modifier.fillMaxWidth().clickable { playAt(track); queueOpen = false }.padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.MusicNote, null, tint = accent)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = Subtle, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { queueOpen = false }) { Text("Close") } },
            containerColor = Panel,
            titleContentColor = Paper,
            textContentColor = Paper
        )
    }
}

@Composable
private fun PageHeader(
    eyebrow: String,
    title: String,
    accent: Color,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text(title, color = Paper, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
        }
        action?.invoke()
    }
}

@Composable
private fun HomePage(
    tracks: List<LocalTrack>,
    favorites: List<String>,
    currentTrack: LocalTrack?,
    playing: Boolean,
    accent: Color,
    scanMessage: String,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlay: (LocalTrack) -> Unit,
    onTogglePlay: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "Your offline music",
            "V10 Audio",
            accent,
            action = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Tune, "Settings", tint = Paper)
                }
            }
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF302026), Color(0xFF17171B), Color(0xFF25233A))))
                        .clickable(onClick = onOpenSearch).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Search, null, tint = Subtle)
                    Spacer(Modifier.width(10.dp))
                    Text("Search songs, artists, albums", color = Subtle, fontSize = 14.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickStat("SONGS", tracks.size.toString(), Icons.Filled.MusicNote, accent, Modifier.weight(1f), onOpenLibrary)
                    QuickStat("FAVOURITES", favorites.size.toString(), Icons.Filled.Favorite, accent, Modifier.weight(1f), onOpenLibrary)
                    QuickStat("ARTISTS", tracks.map { it.artist }.distinct().size.toString(), Icons.Filled.Person, accent, Modifier.weight(1f), onOpenLibrary)
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.9f), Color(0xFF34212A), Panel)))
                        .padding(20.dp)
                ) {
                    Text("YOUR LIBRARY", color = Paper.copy(alpha = 0.78f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Music that moves\\nwith you.", color = Paper, fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
                    Spacer(Modifier.height(7.dp))
                    Text("Everything here plays from files on your phone.", color = Paper.copy(alpha = 0.78f), fontSize = 12.sp)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenLibrary, colors = ButtonDefaults.buttonColors(containerColor = Paper, contentColor = Ink)) {
                        Text("Explore library")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.ArrowForward, null, Modifier.size(16.dp))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recently added", color = Paper, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenLibrary) { Text("See all", color = accent) }
                }
            }
            if (tracks.isEmpty()) {
                item {
                    EmptyLibrary(accent, scanMessage, onOpenLibrary)
                }
            } else {
                items(tracks.take(8), key = { it.uri }) { track ->
                    TrackRow(
                        track = track,
                        favorite = favorites.contains(track.uri),
                        active = currentTrack?.uri == track.uri,
                        accent = accent,
                        onClick = { onPlay(track) },
                        onFavorite = {}
                    )
                }
            }
            if (currentTrack != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).clickable(onClick = onTogglePlay).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverTile(accent, Modifier.size(52.dp), Icons.Filled.GraphicEq)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("CONTINUE LISTENING", color = accent, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                            Text(currentTrack.title, color = Paper, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(currentTrack.artist, color = Subtle, fontSize = 12.sp)
                        }
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Paper)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Panel).clickable(onClick = onClick).padding(12.dp)) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
        Spacer(Modifier.height(10.dp))
        Text(value, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Subtle, fontSize = 9.sp, letterSpacing = 0.6.sp, maxLines = 1)
    }
}

@Composable
private fun LibraryPage(
    tracks: List<LocalTrack>,
    allTracks: List<LocalTrack>,
    favorites: List<String>,
    filter: String,
    search: String,
    sortOrder: String,
    accent: Color,
    onFilter: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSort: (String) -> Unit,
    onPlay: (LocalTrack) -> Unit,
    onFavorite: (LocalTrack) -> Unit,
    onScan: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("Browse offline", "Library", accent, action = {
            IconButton(onClick = onScan) { Icon(Icons.Filled.Refresh, "Scan device", tint = Paper) }
        })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All songs", "Artists", "Albums", "Favorites").forEach { item ->
                FilterChip(selected = filter == item, onClick = { onFilter(item) }, label = { Text(item) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Ink))
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (filter) {
                        "Artists" -> "${tracks.map { it.artist }.distinct().size} artists"
                        "Albums" -> "${tracks.map { it.album }.distinct().size} albums"
                        "Favorites" -> "${tracks.size} favourites"
                        else -> "${allTracks.size} tracks"
                    },
                    color = Paper, fontWeight = FontWeight.SemiBold
                )
                Text("Stored on this device", color = Subtle, fontSize = 11.sp)
            }
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Filled.Sort, null, tint = accent, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(sortOrder, color = accent)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    listOf("Title", "Artist", "Album").forEach { choice ->
                        DropdownMenuItem(text = { Text(choice) }, onClick = { onSort(choice); sortMenu = false })
                    }
                }
            }
        }
        when (filter) {
            "Artists" -> {
                val artists = tracks.map { it.artist }.distinct().sorted()
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                    items(artists) { artist ->
                        Row(Modifier.fillMaxWidth().clickable { onSearch(artist); onFilter("All songs") }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoverTile(accent, Modifier.size(48.dp), Icons.Filled.Person)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(artist, color = Paper, fontWeight = FontWeight.SemiBold)
                                Text("${tracks.count { it.artist == artist }} songs", color = Subtle, fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = Subtle)
                        }
                    }
                }
            }
            "Albums" -> {
                val albums = tracks.groupBy { it.album to it.artist }.toList().sortedBy { it.first.first.lowercase() }
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                    items(albums, key = { it.first.first + it.first.second }) { pair ->
                        Row(Modifier.fillMaxWidth().clickable { onSearch(pair.first.first); onFilter("All songs") }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CoverTile(accent, Modifier.size(48.dp), Icons.Filled.Album)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(pair.first.first, color = Paper, fontWeight = FontWeight.SemiBold)
                                Text(pair.first.second + " · " + pair.second.size + " songs", color = Subtle, fontSize = 12.sp)
                            }
                            Icon(Icons.Filled.ChevronRight, null, tint = Subtle)
                        }
                    }
                }
            }
            else -> {
                if (tracks.isEmpty()) {
                    EmptyLibrary(accent, "Nothing in this section yet.", onScan)
                } else {
                    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 18.dp)) {
                        items(tracks, key = { it.uri }) { track ->
                            TrackRow(track, favorites.contains(track.uri), false, accent, { onPlay(track) }, { onFavorite(track) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPage(
    query: String,
    tracks: List<LocalTrack>,
    favorites: List<String>,
    accent: Color,
    onQuery: (String) -> Unit,
    onPlay: (LocalTrack) -> Unit,
    onFavorite: (LocalTrack) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("Find it on your phone", "Search", accent)
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            placeholder = { Text("Songs, artists, albums…", color = Subtle) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = accent) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "Clear") } },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = PanelRaised,
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                cursorColor = accent
            )
        )
        Spacer(Modifier.height(14.dp))
        Text("${tracks.size} results", Modifier.padding(horizontal = 22.dp), color = Subtle, fontSize = 12.sp)
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, null, tint = Subtle, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(if (query.isBlank()) "Your local library will appear here" else "No matching tracks", color = Subtle)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                items(tracks, key = { it.uri }) { track ->
                    TrackRow(track, favorites.contains(track.uri), false, accent, { onPlay(track) }, { onFavorite(track) })
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    dynamic: Boolean,
    playerStyle: String,
    backgroundStyle: String,
    accentName: String,
    sortOrder: String,
    accent: Color,
    scanMessage: String,
    onDynamic: (Boolean) -> Unit,
    onPlayerStyle: (String) -> Unit,
    onBackground: (String) -> Unit,
    onAccent: (String) -> Unit,
    onSort: (String) -> Unit,
    onScan: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader("Make it yours", "Settings", accent)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                SettingsGroup("APPEARANCE", accent) {
                    SettingSwitch("Album colour theme", "Adapt the accent from the playing track's cover when available", Icons.Filled.Palette, dynamic, accent, onDynamic)
                    Divider(color = Color.White.copy(alpha = 0.07f))
                    Text("Accent colour", color = Paper, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Rose" to V10Red, "Violet" to Color(0xFFAA91FF), "Blue" to Color(0xFF70B8FF), "Mint" to Color(0xFF72D6B2), "Amber" to Color(0xFFFFC16C)).forEach { (name, color) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).clickable { onAccent(name) }) {
                                Box(Modifier.size(34.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                    if (accentName == name) Icon(Icons.Filled.Check, null, tint = Ink, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.height(5.dp))
                                Text(name, color = if (accentName == name) Paper else Subtle, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup("PLAYER", accent) {
                    SettingChoice("Player layout", "Modern", "Modern", playerStyle, accent) { onPlayerStyle("Modern") }
                    SettingChoice("Player layout", "Minimal", "Minimal", playerStyle, accent) { onPlayerStyle("Minimal") }
                    Divider(color = Color.White.copy(alpha = 0.07f))
                    Text("Player background", color = Paper, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                    listOf("Blurred", "Normal", "Colour").forEach { option ->
                        Row(Modifier.fillMaxWidth().clickable { onBackground(option) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = backgroundStyle == option, onClick = { onBackground(option) }, colors = RadioButtonDefaults.colors(selectedColor = accent))
                            Text(option, color = Paper)
                        }
                    }
                }
            }
            item {
                SettingsGroup("LIBRARY", accent) {
                    Text("Default sort", color = Paper, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        listOf("Title", "Artist", "Album").forEach { option ->
                            FilterChip(selected = sortOrder == option, onClick = { onSort(option) }, label = { Text(option) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = Ink))
                        }
                    }
                    Divider(color = Color.White.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Scan device music", color = Paper, fontWeight = FontWeight.SemiBold)
                            Text("Import indexed audio files stored locally", color = Subtle, fontSize = 12.sp)
                        }
                        FilledTonalButton(onClick = onScan, colors = ButtonDefaults.filledTonalButtonColors(containerColor = accent.copy(alpha = 0.18f), contentColor = accent)) {
                            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scan")
                        }
                    }
                    Text(scanMessage, color = Subtle, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                SettingsGroup("ABOUT", accent) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoverTile(accent, Modifier.size(48.dp), Icons.Filled.MusicNote)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("V10 Audio", color = Paper, fontWeight = FontWeight.Bold)
                            Text("Offline music player · Version 1.0.0", color = Subtle, fontSize = 12.sp)
                        }
                    }
                    Text("Only local device audio is shown in the library. No online music catalogue is used.", color = Subtle, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp)) {
        Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Paper, fontWeight = FontWeight.SemiBold)
            Text(description, color = Subtle, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = accent))
    }
}

@Composable
private fun SettingChoice(label: String, option: String, unused: String, selected: String, accent: Color, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected == option, onClick = onSelect, colors = RadioButtonDefaults.colors(selectedColor = accent))
        Text(option, color = Paper)
    }
}

@Composable
private fun TrackRow(
    track: LocalTrack,
    favorite: Boolean,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onFavorite: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (active) accent.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverTile(accent, Modifier.size(52.dp), if (active) Icons.Filled.Equalizer else Icons.Filled.MusicNote)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = if (active) accent else Paper, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist + " · " + track.album, color = Subtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (track.duration > 0) {
            Text(formatTime(track.duration), color = Subtle, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 5.dp))
        }
        IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (favorite) accent else Subtle, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun CoverTile(accent: Color, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.MusicNote) {
    Box(
        modifier.clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.8f), Color(0xFF29252F)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Paper.copy(alpha = 0.92f), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyLibrary(accent: Color, message: String, onScan: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverTile(accent, Modifier.size(68.dp), Icons.Filled.FolderOpen)
        Spacer(Modifier.height(14.dp))
        Text("Your music starts here", color = Paper, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(message, color = Subtle, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink)) {
            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("Scan device")
        }
    }
}

@Composable
private fun MiniPlayer(
    track: LocalTrack,
    playing: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(PanelRaised).clickable(onClick = onClick).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverTile(accent, Modifier.size(43.dp), Icons.Filled.GraphicEq)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Subtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onPlayPause) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Paper)
            }
            IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next", tint = Paper) }
        }
    }
}

@Composable
private fun BottomNavigation(screen: String, accent: Color, onNavigate: (String) -> Unit) {
    NavigationBar(containerColor = Color(0xFF101013), contentColor = Paper, tonalElevation = 0.dp) {
        val items = listOf(
            Triple("Home", Icons.Filled.Home, "Home"),
            Triple("Library", Icons.Filled.LibraryMusic, "Library"),
            Triple("Search", Icons.Filled.Search, "Search"),
            Triple("Settings", Icons.Filled.Settings, "Settings")
        )
        items.forEach { (label, icon, route) ->
            NavigationBarItem(
                selected = screen == route,
                onClick = { onNavigate(route) },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = accent, selectedTextColor = accent, indicatorColor = accent.copy(alpha = 0.13f), unselectedIconColor = Subtle, unselectedTextColor = Subtle)
            )
        }
    }
}

@Composable
private fun FullPlayer(
    track: LocalTrack,
    playing: Boolean,
    position: Long,
    duration: Long,
    accent: Color,
    style: String,
    background: String,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onQueue: () -> Unit
) {
    val playerBrush = when (background) {
        "Colour" -> Brush.verticalGradient(listOf(accent.copy(alpha = 0.65f), Ink, Ink))
        "Normal" -> Brush.verticalGradient(listOf(PanelRaised, Ink, Ink))
        else -> Brush.verticalGradient(listOf(accent.copy(alpha = 0.32f), Color(0xFF19171D), Ink, Ink))
    }
    Column(
        Modifier.fillMaxSize().background(playerBrush).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Close player", tint = Paper) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("From your library", color = Subtle, fontSize = 11.sp)
            }
            IconButton(onClick = onQueue) { Icon(Icons.Filled.QueueMusic, "Queue", tint = Paper) }
        }
        Spacer(Modifier.weight(0.55f))
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (style == "Minimal") 1.08f else 0.94f)
                .clip(RoundedCornerShape(if (style == "Minimal") 8.dp else 28.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), Color(0xFF25212D), Color(0xFF141418)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.MusicNote, null, tint = Paper.copy(alpha = 0.92f), modifier = Modifier.size(104.dp))
        }
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Paper, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(track.artist + " · " + track.album, color = Subtle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (favorite) accent else Paper, modifier = Modifier.size(25.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Slider(
            value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f,
            onValueChange = onSeek,
            valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()),
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Paper.copy(alpha = 0.18f))
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(position), color = Subtle, fontSize = 11.sp)
            Text(formatTime(duration), color = Subtle, fontSize = 11.sp)
        }
        Spacer(Modifier.height(15.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = Paper, modifier = Modifier.size(31.dp)) }
            FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(70.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent, contentColor = Ink)) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.SkipNext, "Next", tint = Paper, modifier = Modifier.size(31.dp)) }
        }
        Spacer(Modifier.weight(0.7f))
        Text("LOCAL AUDIO  •  V10 AUDIO", color = Subtle.copy(alpha = 0.7f), fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 16.dp))
    }
}

private fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds.coerceAtLeast(0L) / 1000L)
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
