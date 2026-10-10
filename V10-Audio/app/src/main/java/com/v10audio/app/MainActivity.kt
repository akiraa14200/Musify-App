package com.v10audio.app

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.os.Build
import android.content.ContentUris
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val bg = Color(0xFF100D0E)
private val red = Color(0xFFE63946)
private val fg = Color(0xFFF8F2F2)
private val muted = Color(0xFFB5A9AC)
private data class Track(val title: String, val artist: String, val uri: String)

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        future = MediaController.Builder(this, token).buildAsync().also { f ->
            f.addListener({ runCatching { controller = f.get() } }, ContextCompat.getMainExecutor(this))
        }
        setContent { V10Screen({ controller }) }
    }
    override fun onDestroy() {
        future?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun V10Screen(controllerProvider: () -> MediaController?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("v10", 0) }
    val tracks = remember {
        val a = runCatching { JSONArray(prefs.getString("tracks", "[]")) }.getOrDefault(JSONArray())
        mutableStateListOf<Track>().apply { for (i in 0 until a.length()) runCatching {
            val o = a.getJSONObject(i); add(Track(o.optString("title"), o.optString("artist"), o.optString("uri")))
        } }
    }
    val favorites = remember { mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) } }
    var player by remember { mutableStateOf<MediaController?>(null) }
    var playing by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(-1) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var query by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Scan your device to import downloaded songs.") }
    val scope = rememberCoroutineScope()

    suspend fun scanDeviceSongs() {
        val imported = withContext(Dispatchers.IO) {
            val found = mutableListOf<Track>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
            context.contentResolver.query(collection, projection, null, null, "title COLLATE NOCASE ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val name = cursor.getString(titleCol)?.takeIf { it.isNotBlank() } ?: "Unknown title"
                    val performer = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist"
                    found.add(Track(name, performer, uri))
                }
            }
            found
        }
        var added = 0
        imported.forEach { t -> if (tracks.none { it.uri == t.uri }) { tracks.add(t); added++ } }
        save()
        message = if (imported.isEmpty()) "No indexed audio found. Make sure downloaded files are stored on this device." else "Scan complete: " + imported.size + " songs found; " + added + " new songs added."
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val scanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { message = "Scanning device music…"; scanDeviceSongs() } else message = "Allow audio access to scan music on this device."
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) scanDeviceSongs()
        for (i in 0..80) { val c = controllerProvider(); if (c != null) { player = c; break }; delay(150) }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { active = player?.currentMediaItemIndex ?: -1 }
            override fun onPlaybackStateChanged(playbackState: Int) { dur = player?.duration?.takeIf { it > 0 } ?: 0L }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { message = "Source could not play. Check URL, network and format." }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) { while (true) { player?.let { pos = it.currentPosition.coerceAtLeast(0); dur = it.duration.takeIf { d -> d > 0 } ?: 0; playing = it.isPlaying }; delay(400) } }

    fun save() {
        val a = JSONArray()
        tracks.forEach { a.put(JSONObject().put("title", it.title).put("artist", it.artist).put("uri", it.uri)) }
        prefs.edit().putString("tracks", a.toString()).putStringSet("favorites", favorites.toSet()).apply()
    }
    fun playIndex(index: Int) {
        val p = player ?: run { message = "Player is starting; try again shortly."; return }
        val items = tracks.map { t -> MediaItem.Builder().setUri(t.uri).setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).build()).build() }
        p.setMediaItems(items, index, 0L); p.prepare(); p.play(); active = index
    }
    fun addTrack(t: Track) {
        if (tracks.none { it.uri == t.uri }) tracks.add(t)
        save(); val i = tracks.indexOfFirst { it.uri == t.uri }
        playIndex(i); message = "Added. Playback starts when the source is reachable."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }; addTrack(Track(uri.lastPathSegment?.substringAfterLast(':') ?: "Local audio", "On this device", uri.toString())) }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = red, background = bg, surface = Color(0xFF211A1C), onBackground = fg, onSurface = fg)) {
        Scaffold(containerColor = bg, bottomBar = {
            Row(Modifier.fillMaxWidth().background(Color(0xEE211A1C)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = fg)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Stream URL") }
                OutlinedButton(onClick = { if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) scope.launch { message = "Scanning device music…"; scanDeviceSongs() } else scanPermission.launch(audioPermission) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = fg)) { Icon(Icons.Default.GraphicEq, null); Spacer(Modifier.width(4.dp)); Text("Scan all songs") }
            }
        }) { pad ->
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF35161C), bg, bg))).padding(pad).padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(red, Color(0xFF6D1825)))), contentAlignment = Alignment.Center) { Text("V10", color = Color.White, fontWeight = FontWeight.Black) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("V10 AUDIO", color = fg, fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 19.sp); Text("YOUR SOUND. YOUR SPACE.", color = muted, fontSize = 9.sp, letterSpacing = 1.4.sp) }
                    Icon(Icons.Default.GraphicEq, null, tint = red)
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search your library", color = muted) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = muted) }, shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = red, unfocusedBorderColor = Color.White.copy(alpha = .12f), focusedTextColor = fg, unfocusedTextColor = fg, cursorColor = red))
                Spacer(Modifier.height(22.dp))
                Text("GOOD SOUND, NO NOISE.", color = red, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 2.sp)
                Text("Made for the\nway you listen.", color = fg, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .06f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Waves, null, tint = red, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tracks.getOrNull(active)?.title ?: "Build your sound", color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tracks.getOrNull(active)?.artist ?: "Add your first stream", color = muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { if (playing) player?.pause() else player?.play() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = fg) }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("YOUR LIBRARY", color = fg, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp); Spacer(Modifier.weight(1f)); Text("${tracks.size} tracks", color = muted, fontSize = 11.sp) }
                Spacer(Modifier.height(6.dp))
                val shown = tracks.withIndex().filter { it.value.title.contains(query, true) || it.value.artist.contains(query, true) }
                if (shown.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 20.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .04f)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your music starts here", color = fg, fontWeight = FontWeight.SemiBold)
                        Text("Add a direct media URL or choose a local audio file.", color = muted, fontSize = 12.sp)
                    }
                } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp)) {
                    itemsIndexed(shown, key = { _, it -> it.value.uri }) { _, entry ->
                        val i = entry.index; val t = entry.value
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { playIndex(i) }.padding(vertical = 7.dp, horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF321D21)), contentAlignment = Alignment.Center) { if (active == i && playing) Icon(Icons.Default.GraphicEq, null, tint = red) else Text("${i + 1}".padStart(2, '0'), color = muted, fontSize = 12.sp) }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) { Text(t.title, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp); Text(t.artist, color = muted, fontSize = 11.sp, maxLines = 1) }
                            IconButton(onClick = { if (t.uri in favorites) favorites.remove(t.uri) else favorites.add(t.uri); save() }) { Icon(if (t.uri in favorites) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (t.uri in favorites) red else muted) }
                            IconButton(onClick = { tracks.remove(t); favorites.remove(t.uri); save(); if (active >= tracks.size) active = tracks.lastIndex }) { Icon(Icons.Default.Close, null, tint = muted) }
                        }
                    }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = muted)) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Browse for one audio file") }
                Text(message, color = muted, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(vertical = 4.dp))
                if (active in tracks.indices) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF261D1F)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player?.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, null, tint = fg) }
                            IconButton(onClick = { if (playing) player?.pause() else player?.play() }, modifier = Modifier.clip(CircleShape).background(red)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
                            IconButton(onClick = { player?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, null, tint = fg) }
                            Spacer(Modifier.weight(1f)); Text("${pos / 60000}:${((pos / 1000) % 60).toString().padStart(2, '0')} / ${dur / 60000}:${((dur / 1000) % 60).toString().padStart(2, '0')}", color = muted, fontSize = 10.sp)
                        }
                        Slider(value = if (dur > 0) pos.toFloat().coerceIn(0f, dur.toFloat()) else 0f, onValueChange = { pos = it.toLong() }, onValueChangeFinished = { player?.seekTo(pos) }, valueRange = 0f..dur.coerceAtLeast(1).toFloat(), colors = SliderDefaults.colors(thumbColor = red, activeTrackColor = red))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text("Add stream") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Track / station name") }, singleLine = true)
            OutlinedTextField(artist, { artist = it }, label = { Text("Artist / description") }, singleLine = true)
            OutlinedTextField(streamUrl, { streamUrl = it }, label = { Text("Direct HTTPS audio URL") }, singleLine = true)
            Text("Use a direct audio URL you own or are authorized to play; webpage links and DRM-protected links won't work.", color = muted, fontSize = 11.sp)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val u = runCatching { Uri.parse(streamUrl.trim()) }.getOrNull()
            if (u?.scheme == "https" && !u.host.isNullOrBlank()) {
                addTrack(Track(title.ifBlank { u.lastPathSegment ?: "Untitled stream" }, artist.ifBlank { "Online stream" }, u.toString()))
                dialog = false; title = ""; artist = ""; streamUrl = ""
            } else message = "Enter a valid HTTPS direct audio URL."
        }) { Text("Add and play", color = red) }
    }, dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel", color = muted) } }, containerColor = Color(0xFF211A1C), titleContentColor = fg, textContentColor = muted)
}
