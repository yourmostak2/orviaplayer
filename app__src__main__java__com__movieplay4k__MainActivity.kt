@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.movieplay4k

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class Sub(val uri: Uri, val name: String)
data class Item(val uri: Uri, val name: String, val subs: List<Sub> = emptyList())
enum class Panel { Main, Speed, Subs, Audio, Fit, More, Info }

private val Accent = Color(0xFF8E7DFF)
private val Glass = Color(0x66141218)
private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)
private val FITS = listOf("Fit", "Fill (crop)", "Stretch")
private val MEDIA_EXT = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "3g2",
    "ogv", "ogm", "vob", "divx", "asf", "rm", "rmvb", "mxf", "hevc", "h265", "264", "av1", "ivf",
    "mp3", "m4a", "flac", "opus", "wav", "aac", "wma", "ogg", "ac3", "dts"
)
private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private val items = mutableStateListOf<Item>()
    private var current by mutableIntStateOf(-1)
    private var playlistOpen by mutableStateOf(false)
    private var panel by mutableStateOf<Panel?>(null)
    private var fullscreen by mutableStateOf(false)
    private var inPip by mutableStateOf(false)

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        addUris(uris)
    }
    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { scanTree(uri) }
            if (found.isEmpty()) toastNone() else addItems(found)
        }
    }
    private val pickSub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // FFmpeg software decoders are used whenever the phone's hardware decoder cannot handle a format.
        val renderers = NextRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        })

        setContent { Root() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val u = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        items.add(Item(u, queryName(u)))
        playIndex(items.lastIndex)
    }

    override fun onUserLeaveHint() {
        if (current >= 0 && player.isPlaying && Build.VERSION.SDK_INT >= 26) {
            val vs = player.videoSize
            val r = if (vs.width > 0 && vs.height > 0) Rational(vs.width.coerceAtMost(2390), vs.height.coerceAtMost(2390).coerceAtLeast(1)) else Rational(16, 9)
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(clampRatio(r)).build())
            } catch (_: Exception) {}
        }
    }

    private fun clampRatio(r: Rational): Rational {
        val v = r.toDouble()
        return when {
            v > 2.39 -> Rational(239, 100)
            v < 0.42 -> Rational(42, 100)
            else -> r
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        if (!inPip) player.pause()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- data

    private fun toastNone() {
        android.widget.Toast.makeText(this, "No video or audio files found", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun queryName(u: Uri): String {
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (_: Exception) {}
        return u.lastPathSegment?.substringAfterLast('/') ?: "Video"
    }

    private fun natural(s: String) = s.lowercase().replace(Regex("\\d+")) { it.value.padStart(10, '0') }

    private fun addUris(uris: List<Uri>) {
        val list = uris.map { u ->
            try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Item(u, queryName(u))
        }.sortedBy { natural(it.name) }
        if (list.isEmpty()) return
        addItems(list)
    }

    private fun addItems(list: List<Item>) {
        val first = items.size
        items.addAll(list)
        if (current < 0) playIndex(first)
    }

    private fun scanTree(tree: Uri): List<Item> {
        val out = mutableListOf<Item>()
        fun walk(docId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val media = mutableListOf<Pair<Uri, String>>()
            val subs = mutableListOf<Sub>()
            val dirs = mutableListOf<String>()
            try {
                contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        when {
                            mime == DocumentsContract.Document.MIME_TYPE_DIR -> dirs.add(id)
                            ext in SUB_EXT -> subs.add(Sub(uri, name))
                            mime.startsWith("video/") || mime.startsWith("audio/") || ext in MEDIA_EXT -> media.add(uri to name)
                        }
                    }
                }
            } catch (_: Exception) {}
            media.sortedBy { natural(it.second) }.forEach { (u, n) ->
                val b = n.substringBeforeLast('.').lowercase()
                val mine = subs.filter { val sb = it.name.substringBeforeLast('.').lowercase(); sb == b || sb.startsWith("$b.") }
                out.add(Item(u, n, mine))
            }
            dirs.forEach { walk(it) }
        }
        walk(DocumentsContract.getTreeDocumentId(tree))
        return out
    }

    private fun subMime(n: String) = when (n.substringAfterLast('.').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun subConfig(s: Sub, default: Boolean) =
        MediaItem.SubtitleConfiguration.Builder(s.uri)
            .setMimeType(subMime(s.name))
            .setLabel(s.name.substringBeforeLast('.'))
            .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

    private fun playIndex(i: Int) {
        if (i !in items.indices) return
        current = i
        val it = items[i]
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        val mi = MediaItem.Builder()
            .setUri(it.uri)
            .setSubtitleConfigurations(it.subs.mapIndexed { k, s -> subConfig(s, k == 0) })
            .build()
        player.setMediaItem(mi)
        player.prepare()
        player.play()
    }

    private fun addSubtitle(uri: Uri) {
        val mi = player.currentMediaItem ?: return
        val pos = player.currentPosition
        val cfg = subConfig(Sub(uri, queryName(uri)), true)
        val existing = mi.localConfiguration?.subtitleConfigurations ?: emptyList()
        val upd = mi.buildUpon().setSubtitleConfigurations(existing + cfg).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        player.setMediaItem(upd, pos)
        player.prepare()
        player.play()
    }

    private fun removeAt(i: Int) {
        if (i !in items.indices) return
        items.removeAt(i)
        when {
            items.isEmpty() -> stopPlayback()
            i < current -> current -= 1
            i == current -> playIndex(minOf(i, items.lastIndex))
        }
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        current = -1
        fullscreen = false
        panel = null
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Root() {
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val ctx = LocalContext.current
        val scheme = when {
            Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            dark -> darkColorScheme(primary = Color(0xFFCFBCFF), background = Color(0xFF141218), surface = Color(0xFF141218))
            else -> lightColorScheme(primary = Color(0xFF6750A4), background = Color(0xFFFEF7FF), surface = Color(0xFFFEF7FF))
        }
        val playing = current >= 0
        LaunchedEffect(playing, dark) {
            if (playing) enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            else enableEdgeToEdge()
        }
        LaunchedEffect(fullscreen) {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                c.hide(WindowInsetsCompat.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (resources.configuration.smallestScreenWidthDp < 600)
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        MaterialTheme(colorScheme = scheme) {
            BackHandler(enabled = true) {
                when {
                    panel != null -> panel = null
                    playlistOpen -> playlistOpen = false
                    fullscreen -> fullscreen = false
                    current >= 0 -> stopPlayback()
                    else -> finish()
                }
            }
            Surface(Modifier.fillMaxSize(), color = if (playing) Color.Black else MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    if (playing) PlayerScreen() else Welcome()
                    PlaylistPanel()
                }
            }
        }
    }

    @Composable
    private fun Welcome() {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(96.dp).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF7C6CF0), Color(0xFFC07BFF)))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(58.dp)) }
            Spacer(Modifier.height(18.dp))
            Text("ORVIA player", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Plays your videos in original quality.\nNothing is re-encoded or uploaded.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(28.dp))
            Button(onClick = { pickFiles.launch(arrayOf("video/*", "audio/*", "application/x-matroska", "application/octet-stream")) }, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Rounded.VideoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Open videos")
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { pickDir.launch(null) }, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open folder")
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { playlistOpen = true }) { Text("Playlist (${items.size})") }
        }
    }

    @Composable
    private fun PlayerScreen() {
        val ctx = LocalContext.current
        val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        var controls by remember { mutableStateOf(true) }
        var isPlaying by remember { mutableStateOf(player.isPlaying) }
        var buffering by remember { mutableStateOf(false) }
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(0L) }
        var drag by remember { mutableStateOf<Float?>(null) }
        var tracks by remember { mutableStateOf(player.currentTracks) }
        var vsize by remember { mutableStateOf(player.videoSize) }
        var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
        var fit by remember { mutableIntStateOf(0) }
        var bright by remember {
            val b = window.attributes.screenBrightness
            mutableFloatStateOf(if (b < 0) 0.5f else b)
        }
        var vol by remember {
            mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }
        var muted by remember { mutableStateOf(false) }
        var zoom by remember { mutableFloatStateOf(1f) }
        var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
        fun say(t: String) { flash = t to System.nanoTime() }

        DisposableEffect(Unit) {
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
                override fun onPlaybackStateChanged(s: Int) {
                    buffering = s == Player.STATE_BUFFERING
                    if (s == Player.STATE_ENDED) {
                        if (current < items.lastIndex) playIndex(current + 1) else controls = true
                    }
                }
                override fun onTracksChanged(t: Tracks) { tracks = t }
                override fun onVideoSizeChanged(v: VideoSize) { vsize = v }
                override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) { speed = p.speed }
                override fun onPlayerError(e: PlaybackException) { say("Can't play this file (${e.errorCodeName})") }
            }
            player.addListener(l)
            onDispose { player.removeListener(l) }
        }
        LaunchedEffect(Unit) {
            while (true) {
                pos = player.currentPosition
                dur = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
                delay(250)
            }
        }
        LaunchedEffect(controls, isPlaying, panel, playlistOpen) {
            if (controls && isPlaying && panel == null && !playlistOpen) { delay(3500); controls = false }
        }
        LaunchedEffect(flash) { if (flash != null) { delay(1300); flash = null } }
        LaunchedEffect(bright) {
            window.attributes = window.attributes.also { it.screenBrightness = bright.coerceIn(0.02f, 1f) }
        }

        val vf = remember(tracks, vsize) { player.videoFormat }
        val fps = vf?.frameRate?.takeIf { it > 0f }
        val hdr = when (vf?.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
        val meta = listOfNotNull(
            if (vsize.width > 0) "${vsize.width} × ${vsize.height}" else null,
            hdr,
            fps?.let { if (abs(it - it.toInt()) < 0.01f) "${it.toInt()} fps" else "%.2f fps".format(it) }
        ).joinToString(" • ")
        val q = run {
            val m = maxOf(vsize.height.toDouble(), vsize.width * 9.0 / 16)
            when {
                vsize.height == 0 -> "AUD"; m >= 4300 -> "8K"; m >= 2100 -> "4K"; m >= 1400 -> "2K"
                m >= 1000 -> "FHD"; m >= 700 -> "HD"; else -> "SD"
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val wide = maxWidth >= 600.dp
            val isLand = maxWidth > maxHeight
            val land = true // same minimal controls in portrait and landscape
            val bs = if (maxWidth < 480.dp) 36.dp else 40.dp
            LaunchedEffect(isLand) { if (isLand) fullscreen = true }

            AndroidView(
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom),
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.player = this@MainActivity.player
                    }
                },
                update = {
                    it.resizeMode = when (fit) {
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            )

            // MX-Player style gestures: swipe horizontally = seek, left side vertical = brightness, right side vertical = volume
            var hud by remember { mutableStateOf<Triple<ImageVector, String, Float?>?>(null) }
            var gesturing by remember { mutableStateOf(false) }
            LaunchedEffect(gesturing) { if (!gesturing) { delay(700); hud = null } }
            val volSet: (Float) -> Unit = {
                vol = it; muted = it == 0f
                player.volume = if (muted) 0f else 1f
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).toInt(), 0)
            }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controls = !controls },
                            onDoubleTap = { o ->
                                val x = o.x / size.width
                                when {
                                    x < 0.35f -> { player.seekBack(); say("−10 s") }
                                    x > 0.65f -> { player.seekForward(); say("+10 s") }
                                    else -> if (player.isPlaying) player.pause() else player.play()
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        var mode = 0; var ax = 0f; var ay = 0f; var sx = 0f
                        var p0 = 0L; var b0 = 0f; var v0 = 0f; var target = 0L
                        detectDragGestures(
                            onDragStart = { o ->
                                mode = 0; ax = 0f; ay = 0f; sx = o.x
                                p0 = player.currentPosition; b0 = bright; v0 = if (muted) 0f else vol
                            },
                            onDrag = { change, d ->
                                change.consume()
                                ax += d.x; ay += d.y
                                if (mode == 0 && (kotlin.math.abs(ax) > 24f || kotlin.math.abs(ay) > 24f)) {
                                    mode = if (kotlin.math.abs(ax) > kotlin.math.abs(ay)) 1 else if (sx < size.width / 2f) 2 else 3
                                    gesturing = true
                                }
                                when (mode) {
                                    1 -> if (player.duration > 0) {
                                        val deltaMs = (ax / size.width * 120_000f).toLong()
                                        target = (p0 + deltaMs).coerceIn(0L, player.duration)
                                        val sec = (target - p0) / 1000
                                        hud = Triple(if (sec >= 0) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                                            (if (sec >= 0) "+" else "−") + kotlin.math.abs(sec) + " s   " + fmt(target), null)
                                    }
                                    2 -> { bright = (b0 - ay / size.height * 1.3f).coerceIn(0.02f, 1f); hud = Triple(Icons.Rounded.WbSunny, "${(bright * 100).toInt()}%", bright) }
                                    3 -> { val nv = (v0 - ay / size.height * 1.3f).coerceIn(0f, 1f); volSet(nv)
                                        hud = Triple(if (nv == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "${(nv * 100).toInt()}%", nv) }
                                }
                            },
                            onDragEnd = { if (mode == 1) player.seekTo(target); gesturing = false },
                            onDragCancel = { gesturing = false }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.size >= 2) {
                                    val z = ev.calculateZoom()
                                    if (z != 1f) {
                                        zoom = (zoom * z).coerceIn(0.5f, 4f)
                                        hud = Triple(Icons.Rounded.ZoomIn, "${(zoom * 100).toInt()}%", null)
                                        gesturing = true
                                    }
                                    ev.changes.forEach { it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                            gesturing = false
                        }
                    }
            )
            hud?.let { (ic, txt, lvl) ->
                Column(
                    Modifier.align(Alignment.Center).offset(y = (-40).dp).clip(RoundedCornerShape(20.dp))
                        .background(Color(0xB3141218)).padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(ic, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(txt, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                    if (lvl != null) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x4DFFFFFF))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(lvl.coerceIn(0f, 1f)).background(Color(0xFF9B8CFF)))
                        }
                    }
                }
            }

            if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            flash?.let {
                Text(
                    it.first, color = Color.White,
                    modifier = Modifier.align(Alignment.Center).offset(y = (-120).dp)
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xCC1E1C24)).padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }

            AnimatedVisibility(controls && !inPip, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(90.dp).background(Brush.verticalGradient(listOf(Color(0x80000000), Color.Transparent))))
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (land) 190.dp else 260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xA6000000)))))

                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 12.dp)) {
                        // top bar
                        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (land) IconButton({ if (fullscreen) fullscreen = false else stopPlayback() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                            } else GlassBtn(Icons.AutoMirrored.Rounded.ArrowBack, "Back") {
                                if (fullscreen) fullscreen = false else stopPlayback()
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    items.getOrNull(current)?.name?.substringBeforeLast('.') ?: "", color = Color.White,
                                    fontSize = if (land) 16.sp else 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(meta, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(
                                Modifier.height(36.dp).glass(RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                                    .clickable { panel = Panel.Info }.padding(horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                            ) {
                                Text(q, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
                                Text(hdr ?: "ORIGINAL", color = Color(0xB3FFFFFF), fontSize = 7.sp, lineHeight = 8.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                            GlassBtn(Icons.Rounded.MoreVert, "More", bs) { panel = Panel.Main }
                        }

                        // center transport
                        if (!land) Row(
                            Modifier.align(Alignment.Center).offset(y = if (wide) 0.dp else 70.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 22.dp)
                        ) {
                            if (wide) GlassBtn(Icons.Rounded.SkipPrevious, "Previous", 64.dp) {
                                if (player.currentPosition > 5000 || current <= 0) player.seekTo(0) else playIndex(current - 1)
                            }
                            GlassBtn(Icons.Rounded.Replay10, "Back 10 seconds", 64.dp) { player.seekBack() }
                            Box(
                                Modifier.size(96.dp).clip(CircleShape)
                                    .background(Brush.radialGradient(listOf(Color(0x55FFFFFF), Color(0x667C6CF0))))
                                    .clickable { if (isPlaying) player.pause() else player.play() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color.White, modifier = Modifier.size(56.dp))
                            }
                            GlassBtn(Icons.Rounded.Forward10, "Forward 10 seconds", 64.dp) { player.seekForward() }
                            if (wide) GlassBtn(Icons.Rounded.SkipNext, "Next", 64.dp) {
                                if (current < items.lastIndex) playIndex(current + 1)
                            }
                        }

                        // bottom: seek + dock
                        if (land) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val shown = drag ?: if (dur > 0) pos.toFloat() / dur else 0f
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(fmt(if (drag != null) (drag!! * dur).toLong() else pos), color = Color(0xE6FFFFFF), fontSize = 12.sp, modifier = Modifier.width(50.dp))
                                Slider(
                                    value = shown.coerceIn(0f, 1f),
                                    onValueChange = { drag = it },
                                    onValueChangeFinished = { drag?.let { f -> if (dur > 0) player.seekTo((f * dur).toLong()) }; drag = null },
                                    modifier = Modifier.weight(1f).height(24.dp),
                                    thumb = { SliderDefaults.Thumb(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, thumbSize = androidx.compose.ui.unit.DpSize(12.dp, 12.dp), colors = SliderDefaults.colors(thumbColor = Color.White)) },
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFF9B8CFF), inactiveTrackColor = Color(0x40FFFFFF))
                                )
                                Text(fmt(dur), color = Color(0xE6FFFFFF), fontSize = 12.sp, modifier = Modifier.width(54.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                                Tap(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlist", bs) { playlistOpen = true }
                                Tap(Icons.Rounded.SkipPrevious, "Previous", bs) {
                                    if (player.currentPosition > 5000 || current <= 0) player.seekTo(0) else playIndex(current - 1)
                                }
                                Box(Modifier.size(bs + 14.dp).clip(CircleShape).clickable { if (isPlaying) player.pause() else player.play() }, contentAlignment = Alignment.Center) {
                                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color.White, modifier = Modifier.size(38.dp))
                                }
                                Tap(Icons.Rounded.SkipNext, "Next", bs) { if (current < items.lastIndex) playIndex(current + 1) }
                                Tap(Icons.Rounded.AspectRatio, "Aspect ratio", bs) { fit = (fit + 1) % 3; zoom = 1f; say(FITS[fit]) }
                                Tap(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Fullscreen", bs) { fullscreen = !fullscreen }
                            }
                        } else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 10.dp)) {
                            val shown = drag ?: if (dur > 0) pos.toFloat() / dur else 0f
                            Slider(
                                value = shown.coerceIn(0f, 1f),
                                onValueChange = { drag = it },
                                onValueChangeFinished = { drag?.let { f -> if (dur > 0) player.seekTo((f * dur).toLong()) }; drag = null },
                                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color(0x4DFFFFFF))
                            )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(fmt(if (drag != null) (drag!! * dur).toLong() else pos), color = Color.White, fontSize = 15.sp)
                                Text(fmt(dur), color = Color.White, fontSize = 15.sp)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier.widthIn(max = 720.dp).align(Alignment.CenterHorizontally).fillMaxWidth()
                                    .clip(RoundedCornerShape(34.dp)).background(Glass).padding(vertical = 10.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                DockBtn(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlist", false) { playlistOpen = true }
                                DockBtn(Icons.Rounded.ClosedCaption, "Subtitles", false) { panel = Panel.Subs }
                                DockBtn(Icons.Rounded.GraphicEq, "Audio", false) { panel = Panel.Audio }
                                DockBtn(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Screen", fullscreen) { fullscreen = !fullscreen }
                                DockBtn(Icons.Rounded.Settings, "Settings", false) { panel = Panel.Main }
                            }
                        }
                    }
                }
            }

            // ---- settings panels
            panel?.let { p ->
                Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { panel = null })
                Box(
                    Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = 56.dp, end = 12.dp, start = 12.dp).widthIn(max = 300.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp), color = Color.Transparent, contentColor = Color.White,
                        modifier = Modifier.glass(RoundedCornerShape(22.dp), 2)
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).heightIn(max = if (land) 240.dp else 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                            when (p) {
                                Panel.Main -> {
                                    val selSub = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
                                    val selAud = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                                    Row1(Icons.Rounded.HighQuality, "Quality", "Original ${if (vsize.height > 0) "${vsize.height}p" else ""}") { panel = Panel.Info }
                                    Row1(Icons.Rounded.Speed, "Playback speed", if (speed == 1f) "Normal" else "${speed}×") { panel = Panel.Speed }
                                    Row1(Icons.Rounded.ClosedCaption, "Subtitles", if (selSub != null) trackName(selSub, 0) else "Off") { panel = Panel.Subs }
                                    Row1(Icons.Rounded.GraphicEq, "Audio track", selAud?.let { trackName(it, 0) } ?: "Default") { panel = Panel.Audio }
                                    Row1(Icons.Rounded.AspectRatio, "Screen fit", FITS[fit]) { panel = Panel.Fit }
                                    Row1(Icons.Rounded.MoreHoriz, "More", "") { panel = Panel.More }
                                }
                                Panel.Speed -> { Head("Playback speed") { panel = Panel.Main }
                                    SPEEDS.forEach { s -> Opt(if (s == 1f) "Normal" else "${s}×", speed == s) { player.setPlaybackSpeed(s); panel = null } } }
                                Panel.Fit -> { Head("Screen fit") { panel = Panel.Main }
                                    FITS.forEachIndexed { i, n -> Opt(n, fit == i) { fit = i; panel = null } } }
                                Panel.Subs -> {
                                    Head("Subtitles") { panel = Panel.Main }
                                    val texts = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                                    Opt("Off", texts.none { it.isSelected }) {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); panel = null
                                    }
                                    texts.forEachIndexed { i, g ->
                                        Opt(trackName(g, i), g.isSelected) {
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build(); panel = null
                                        }
                                    }
                                    Opt("＋ Add subtitle from phone (.srt .vtt .ass)", false) { panel = null; pickSub.launch(arrayOf("*/*")) }
                                }
                                Panel.Audio -> {
                                    Head("Audio track") { panel = Panel.Main }
                                    val auds = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                                    if (auds.isEmpty()) Note("No audio tracks")
                                    auds.forEachIndexed { i, g ->
                                        Opt(trackName(g, i), g.isSelected) {
                                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build(); panel = null
                                        }
                                    }
                                }
                                Panel.More -> {
                                    Head("More") { panel = Panel.Main }
                                    Opt("Loop video: " + if (player.repeatMode == Player.REPEAT_MODE_ONE) "On" else "Off", false) {
                                        player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE; panel = null
                                    }
                                    Opt("Video info", false) { panel = Panel.Info }
                                }
                                Panel.Info -> {
                                    Head("Video info") { panel = Panel.Main }
                                    val af = player.audioFormat
                                    val rows = listOf(
                                        "Quality" to "Original (never re-encoded or downscaled)",
                                        "Resolution" to if (vsize.width > 0) "${vsize.width} × ${vsize.height}" else "-",
                                        "Frame rate" to (fps?.let { "%.3f fps".format(it) } ?: "-"),
                                        "HDR" to (hdr ?: "No / SDR"),
                                        "Video codec" to (vf?.codecs ?: vf?.sampleMimeType ?: "-"),
                                        "Video bitrate" to (vf?.bitrate?.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1e6) } ?: "-"),
                                        "Audio codec" to (af?.codecs ?: af?.sampleMimeType ?: "-"),
                                        "Audio channels" to (af?.channelCount?.takeIf { it > 0 }?.toString() ?: "-")
                                    )
                                    rows.forEach { (k, v) -> Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                                        Text(k, Modifier.weight(0.45f), color = Color(0xB3FFFFFF), fontSize = 14.sp)
                                        Text(v, Modifier.weight(0.55f), fontSize = 14.sp)
                                    } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaylistPanel() {
        if (!playlistOpen) return
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 700.dp
            Box(Modifier.fillMaxSize().background(Color(0x73000000)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { playlistOpen = false })
            Surface(
                modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter)
                    .then(if (wide) Modifier.fillMaxHeight().width(420.dp) else Modifier.fillMaxWidth().fillMaxHeight(0.78f)),
                shape = if (wide) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Playlist", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("  ${items.size} item${if (items.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton({ playlistOpen = false }) { Icon(Icons.Rounded.Close, "Close") }
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip({ pickFiles.launch(arrayOf("video/*", "audio/*", "application/x-matroska", "application/octet-stream")) }, { Text("Add videos") }, leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) })
                        AssistChip({ pickDir.launch(null) }, { Text("Add folder") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)) })
                        AssistChip({ items.clear(); stopPlayback() }, { Text("Clear") }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp)) })
                    }
                    if (items.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Your playlist is empty.\nAdd videos or a whole folder.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        itemsIndexed(items) { i, it ->
                            val cur = i == current
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .background(if (cur) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { playIndex(i); if (!wide) playlistOpen = false }.padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (cur) Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                                else Text("${i + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                Column(Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 8.dp)) {
                                    Text(it.name.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                                    if (it.subs.isNotEmpty()) Text("with subtitles", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton({ removeAt(i) }) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(20.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- small widgets

    private fun trackName(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(0)
        val lang = f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.takeIf { d -> d.isNotBlank() } }
        return f.label ?: lang ?: "Track ${i + 1}"
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
    }

    /** Liquid-glass look: dark tint + frosted white gradient + specular rim. */
    private fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape, level: Int = 0): Modifier = this
        .background(
            color = when (level) { 1 -> Color(0x00000000); 2 -> Color(0x99101014); else -> Color(0x40000000) },
            shape = shape
        )
        .background(
            brush = Brush.verticalGradient(
                when (level) {
                    1 -> listOf(Color(0xF08B7CFF), Color(0xF0372A9E))
                    2 -> listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))
                    else -> listOf(Color(0x47FFFFFF), Color(0x1AFFFFFF))
                }
            ),
            shape = shape
        )
        .border(
            1.2.dp,
            Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0x14FFFFFF), Color(0x80FFFFFF), Color(0x14FFFFFF))),
            shape
        )

    @Composable
    private fun Tap(icon: ImageVector, desc: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
        Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }

    @Composable
    private fun GlassBtn(icon: ImageVector, desc: String, size: androidx.compose.ui.unit.Dp = 40.dp, onClick: () -> Unit) {
        Box(Modifier.size(size).glass(CircleShape).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = Color.White, modifier = Modifier.size(size * 0.52f))
        }
    }

    @Composable
    private fun DockBtn(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (on) Accent else Color(0x1AFFFFFF)), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = Color.White)
            }
            Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun VSlider(value: Float, onChange: (Float) -> Unit, icon: ImageVector, modifier: Modifier) {
        val cb by rememberUpdatedState(onChange)
        Column(
            modifier.width(52.dp).height(190.dp).clip(RoundedCornerShape(26.dp)).background(Glass).padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)
                    .pointerInput(Unit) { detectTapGestures { o -> cb((1f - o.y / size.height).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) { detectVerticalDragGestures { ch, _ -> cb((1f - ch.position.y / size.height).coerceIn(0f, 1f)) } },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(Color(0x4DFFFFFF)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(value.coerceIn(0f, 1f)).background(Accent))
                }
            }
        }
    }

    @Composable
    private fun Head(title: String, back: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }

    @Composable
    private fun Row1(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
        Column {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Text(label, Modifier.padding(start = 12.dp).weight(1f), fontSize = 14.sp, color = Color.White)
                Text(value, color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
                Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xCCFFFFFF), modifier = Modifier.size(18.dp))
            }
            Box(Modifier.fillMaxWidth().padding(start = 52.dp, end = 10.dp).height(1.dp).background(Color(0x1FFFFFFF)))
        }
    }

    @Composable
    private fun FlatSlider(value: Float, onChange: (Float) -> Unit, icon: ImageVector, modifier: Modifier) {
        val cb by rememberUpdatedState(onChange)
        Column(modifier.width(36.dp).height(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color(0xE6FFFFFF), modifier = Modifier.size(20.dp))
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(top = 10.dp)
                    .pointerInput(Unit) { detectTapGestures { o -> cb((1f - o.y / size.height).coerceIn(0f, 1f)) } }
                    .pointerInput(Unit) { detectVerticalDragGestures { ch, _ -> cb((1f - ch.position.y / size.height).coerceIn(0f, 1f)) } },
                contentAlignment = Alignment.TopCenter
            ) {
                val v = value.coerceIn(0f, 1f)
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color(0x4DFFFFFF)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight(v).background(Color(0xFF9B8CFF)))
                }
                Box(Modifier.offset(y = (maxHeight - 14.dp) * (1f - v)).size(14.dp).clip(CircleShape).background(Color(0xFFD9D0FF)))
            }
        }
    }

    @Composable
    private fun Opt(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
                .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Rounded.Check, null, tint = Accent) }
            Text(label, Modifier.padding(start = 12.dp), fontSize = 14.sp)
        }
    }

    @Composable
    private fun Note(t: String) = Text(t, Modifier.padding(14.dp), color = Color(0xB3FFFFFF), fontSize = 14.sp)

}
