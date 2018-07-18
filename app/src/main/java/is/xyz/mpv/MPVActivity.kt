package `is`.xyz.mpv

import kotlinx.android.synthetic.main.player.*

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.support.v4.content.ContextCompat
import android.view.*
import android.widget.SeekBar
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import android.widget.Toast.makeText
import kotlinx.android.synthetic.main.player.view.*

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.truncate

class MPVActivity : Activity(), EventObserver, TouchGesturesObserver {
    private lateinit var fadeHandler: Handler
    private lateinit var fadeRunnable: FadeOutControlsRunnable

    private var activityIsForeground = true
    private var userIsOperatingSeekbar = false

    private lateinit var toast: Toast
    private lateinit var gestures: TouchGestures
    private lateinit var audioManager: AudioManager

    private val seekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser)
                return
            player.timePos = progress
            updatePlaybackPos(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar) {
            userIsOperatingSeekbar = false
        }
    }

    private var statsEnabled = false
    private var statsOnlyFPS = false
    private var statsLuaMode = 0 // ==0 disabled, >0 page number

    private var gesturesEnabled = true

    private var backgroundPlayMode = ""

    private fun initListeners() {
        controls.cycleAudioBtn.setOnClickListener { _ ->  cycleAudio() }
        controls.cycleAudioBtn.setOnLongClickListener { _ -> pickAudio(); true }

        controls.cycleSubsBtn.setOnClickListener { _ ->cycleSub() }
        controls.cycleSubsBtn.setOnLongClickListener { _ -> pickSub(); true }
    }

    private fun initMessageToast() {
        toast = makeText(applicationContext, "This totally shouldn't be seen", LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 0)
    }

    private var playbackHasStarted = false
    private var onload_commands = ArrayList<Array<String>>()

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        // Do copyAssets here and not in MainActivity because mpv can be launched from a file browser
        copyAssets()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.player)

        // Init controls to be hidden and view fullscreen
        initControls()

        // Initialize listeners for the player view
        initListeners()

        // Initialize toast used for short messages
        initMessageToast()

        // set up a callback handler and a runnable for fading the controls out
        fadeHandler = Handler()
        fadeRunnable = FadeOutControlsRunnable(this, controls)

        syncSettings()

        val filepath: String?
        if (intent.action == Intent.ACTION_VIEW) {
            filepath = resolveUri(intent.data)
            parseIntentExtras(intent.extras)
        } else {
            filepath = intent.getStringExtra("filepath")
        }

        if (filepath == null) {
            Log.e(TAG, "No file given, exiting")
            finish()
            return
        }

        player.initialize(applicationContext.filesDir.path)
        player.addObserver(this)
        player.playFile(filepath)

        playbackSeekbar.setOnSeekBarChangeListener(seekBarChangeListener)

        if (this.gesturesEnabled) {
            val dm = resources.displayMetrics
            gestures = TouchGestures(dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), this)
            player.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onDestroy() {
        // take the background service with us
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)

        player.removeObserver(this)
        player.destroy()
        super.onDestroy()
    }

    private fun copyAssets() {
        val assetManager = applicationContext.assets
        val files = arrayOf("subfont.ttf", "cacert.pem")
        val configDir = applicationContext.filesDir.path
        for (filename in files) {
            var ins: InputStream? = null
            var out: OutputStream? = null
            try {
                ins = assetManager.open(filename, AssetManager.ACCESS_STREAMING)
                val outFile = File("$configDir/$filename")
                // Note that .available() officially returns an *estimated* number of bytes available
                // this is only true for generic streams, asset streams return the full file size
                if (outFile.length() == ins.available().toLong()) {
                    Log.w(TAG, "Skipping copy of asset file (exists same size): $filename")
                    continue
                }
                out = FileOutputStream(outFile)
                ins.copyTo(out)
                Log.w(TAG, "Copied asset file: $filename")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to copy asset file: $filename", e)
            } finally {
                ins?.close()
                out?.close()
            }
        }
    }

    private fun shouldBackground(): Boolean {
        if (isFinishing) // about to exit?
            return false
        if (player.paused ?: true)
            return false
        when (backgroundPlayMode) {
            "always" -> return true
            "never" -> return false
        }

        // backgroundPlayMode == "audio-only"
        val fmt = MPVLib.getPropertyString("video-format")
        return fmt.isNullOrEmpty() || arrayOf("mjpeg", "png", "bmp").indexOf(fmt) != -1
    }

    override fun onPause() {
        val multiWindowMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInMultiWindowMode else false
        if (multiWindowMode) {
            Log.v(TAG, "Going into multi-window mode")
            super.onPause()
            return
        }

        val shouldBackground = shouldBackground()
        if (shouldBackground && !MPVLib.getPropertyString("video-format").isNullOrEmpty())
            BackgroundPlaybackService.thumbnail = MPVLib.grabThumbnail(THUMB_SIZE)
        else
            BackgroundPlaybackService.thumbnail = null

        player.onPause()
        super.onPause()

        activityIsForeground = false
        if (shouldBackground) {
            Log.v(TAG, "Resuming playback in background")
            // start background playback service
            val serviceIntent = Intent(this, BackgroundPlaybackService::class.java)
            applicationContext.startService(serviceIntent)
        }
    }

    private fun syncSettings() {
        // FIXME: settings should be in their own class completely
        val prefs = getDefaultSharedPreferences(this.applicationContext)

        val statsMode = prefs.getString("stats_mode", "")
        if (statsMode.isNullOrBlank()) {
            this.statsEnabled = false
            this.statsLuaMode = 0
        } else if (statsMode == "native" || statsMode == "native_fps") {
            this.statsEnabled = true
            this.statsLuaMode = 0
            this.statsOnlyFPS = statsMode == "native_fps"
        } else if (statsMode == "lua1" || statsMode == "lua2") {
            this.statsEnabled = false
            this.statsLuaMode = if (statsMode == "lua1") 1 else 2
        }
        this.gesturesEnabled = prefs.getBoolean("touch_gestures", true)
        this.backgroundPlayMode = prefs.getString("background_play", "never")

        if (this.statsOnlyFPS)
            statsTextView.setTextColor((0xFF00FF00).toInt()) // green
    }

    override fun onResume() {
        // If we weren't actually in the background (e.g. multi window mode), don't reinitialize stuff
        if (activityIsForeground) {
            super.onResume()
            return
        }

        // Init controls to be hidden and view fullscreen
        initControls()
        syncSettings()

        activityIsForeground = true
        refreshUi()
        // stop background playback if still running
        val intent = Intent(this, BackgroundPlaybackService::class.java)
        applicationContext.stopService(intent)

        player.onResume()
        super.onResume()
    }

    private fun updateStats() {
        if (this.statsOnlyFPS) {
            statsTextView.text = "${player.estimatedVfFps} FPS"
            return
        }

        val text = "File: ${player.filename}\n\n" +
                "Video: ${player.videoCodec} hwdec: ${player.hwdecActive}\n" +
                "\tA-V: ${player.avsync}\n" +
                "\tDropped: decoder: ${player.decoderFrameDropCount}, VO: ${player.frameDropCount}\n" +
                "\tFPS: ${player.fps} (specified) ${player.estimatedVfFps} (estimated)\n" +
                "\tResolution: ${player.videoW}x${player.videoH}\n\n" +
                "Audio: ${player.audioCodec}\n" +
                "\tSample rate: ${player.audioSampleRate} Hz\n" +
                "\tChannels: ${player.audioChannels}"
        statsTextView.text = text
    }

    private fun showControls() {
        // remove all callbacks that were to be run for fading
        fadeHandler.removeCallbacks(fadeRunnable)

        // set the main controls as 75%, actual seek bar|buttons as 100%
        controls.alpha = 1f

        // Open, Sesame!
        controls.visibility = View.VISIBLE

        if (this.statsEnabled) {
            updateStats()
            statsTextView.visibility = View.VISIBLE
        }

        window.decorView.systemUiVisibility = 0

        // add a new callback to hide the controls once again
        fadeHandler.postDelayed(fadeRunnable, CONTROLS_DISPLAY_TIMEOUT)
    }

    fun initControls() {
        /* Init controls to be hidden */
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        controls.visibility = View.GONE
        statsTextView.visibility = View.GONE

        val flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE
        window.decorView.systemUiVisibility = flags
    }

    private fun hideControls() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.post(fadeRunnable)
    }

    private fun toggleControls(): Boolean {
        return if (controls.visibility == View.VISIBLE) {
            hideControls()
            false
        } else {
            showControls()
            true
        }
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        showControls()
        return super.dispatchKeyEvent(ev)
    }

    private var mightWantToToggleControls = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (super.dispatchTouchEvent(ev)) {
            // reset delay if the event has been handled
            if (controls.visibility == View.VISIBLE)
                showControls()
            if (ev.action == MotionEvent.ACTION_UP)
                return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN)
            mightWantToToggleControls = true
        if (ev.action == MotionEvent.ACTION_UP && mightWantToToggleControls)
            toggleControls()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CAPTIONS -> cycleSub()
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> player.cyclePause()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycleAudio()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekRelative(BUTTON_SEEK_RANGE)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.paused = true
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.paused = false
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekRelative(-BUTTON_SEEK_RANGE)
            KeyEvent.KEYCODE_INFO -> toggleControls()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    fun playPause(view: View) = player.cyclePause()
    @Suppress("UNUSED_PARAMETER")
    fun playlistPrev(view: View) = MPVLib.command(arrayOf("playlist-prev"))
    @Suppress("UNUSED_PARAMETER")
    fun playlistNext(view: View) = MPVLib.command(arrayOf("playlist-next"))

    private fun showToast(msg: String) {
        toast.setText(msg)
        toast.show()
    }

    private fun seekRelative(offset: Int) {
        MPVLib.command(arrayOf("seek", offset.toString(), "relative"))
    }

    private fun resolveUri(data: Uri): String? {
        val filepath = when (data.scheme) {
            "file" -> data.path
            "content" -> openContentFd(data)
            "http", "https", "rtmp", "rtmpe", "rtmps", "rtp", "rtsp", "mms", "mmst", "mmsh", "udp"
            -> data.toString()
            else -> null
        }

        if (filepath == null)
            Log.e(TAG, "unknown scheme: ${data.scheme}")
        return filepath
    }

    private fun openContentFd(uri: Uri): String? {
        val resolver = applicationContext.contentResolver
        return try {
            val fd = resolver.openFileDescriptor(uri, "r")
            "fdclose://${fd.detachFd()}"
        } catch(e: Exception) {
            Log.e(TAG, "Failed to open content fd: $e")
            null
        }
    }

    private fun parseIntentExtras(extras: Bundle?) {
        onload_commands.clear()
        if (extras == null)
            return

        // API reference: http://mx.j2inter.com/api (partially implemented)
        if (extras.getByte("decode_mode") == 2.toByte())
            onload_commands.add(arrayOf("set", "file-local-options/hwdec", "no"))
        if (extras.containsKey("subs")) {
            val subList = extras.getParcelableArray("subs")?.mapNotNull { it as? Uri } ?: emptyList()
            val subsToEnable = extras.getParcelableArray("subs.enable")?.mapNotNull { it as? Uri } ?: emptyList()

            for (suburi in subList) {
                val subfile = resolveUri(suburi) ?: continue
                val flag = if (subsToEnable.filter({ it.compareTo(suburi) == 0 }).any()) "select" else "auto"

                Log.v(TAG, "Adding subtitles from intent extras: $subfile")
                onload_commands.add(arrayOf("sub-add", subfile, flag))
            }
        }
        if (extras.getInt("position", 0) > 0) {
            val pos = extras.getInt("position", 0) / 1000f
            onload_commands.add(arrayOf("set", "start", pos.toString()))
        }
    }

    data class TrackData(val track_id: Int, val track_type: String)
    private fun trackSwitchNotification(f: () -> TrackData) {
        val (track_id, track_type) = f()
        val trackPrefix = when (track_type) {
            "audio" -> "Audio"
            "sub"   -> "Subs"
            "video" -> "Video"
            else    -> "Unknown"
        }

        if (track_id == -1) {
            showToast("$trackPrefix Off")
            return
        }

        val trackName = player.tracks[track_type]?.firstOrNull{ it.mpvId == track_id }?.name ?: "???"
        showToast("$trackPrefix $trackName")
    }

    private fun cycleAudio() = trackSwitchNotification {
        player.cycleAudio(); TrackData(player.aid, "audio")
    }

    private fun cycleSub() = trackSwitchNotification {
        player.cycleSub(); TrackData(player.sid, "sub")
    }

    private fun selectTrack(type: String, get: () -> Int, set: (Int) -> Unit) {
        val tracks = player.tracks[type]!!
        val selectedMpvId = get()
        val selectedIndex = tracks.indexOfFirst { it.mpvId == selectedMpvId }
        val wasPlayerPaused = player.paused ?: true // default to not changing state after switch

        player.paused = true

        with (AlertDialog.Builder(this)) {
            setSingleChoiceItems(tracks.map { it.name }.toTypedArray(), selectedIndex) { dialog, item ->
                val trackId = tracks[item].mpvId

                set(trackId)
                dialog.dismiss()
                trackSwitchNotification { TrackData(trackId, type) }
            }
            setOnDismissListener { if (!wasPlayerPaused) player.paused = false }
            create().show()
        }
    }

    private fun pickAudio() = selectTrack("audio", { player.aid }, { player.aid = it })

    private fun pickSub() = selectTrack("sub", { player.sid }, { player.sid = it })

    @Suppress("UNUSED_PARAMETER")
    fun switchDecoder(view: View) {
        player.cycleHwdec()
        updateDecoderButton()
    }

    private fun prettyTime(d: Int): String {
        val hours = d / 3600
        val minutes = d % 3600 / 60
        val seconds = d % 60
        if (hours == 0)
            return "%02d:%02d".format(minutes, seconds)
        return "%d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun refreshUi() {
        // forces update of entire UI, used when returning from background playback
        if (player.timePos == null)
            return
        updatePlaybackStatus(player.paused!!)
        updatePlaybackPos(player.timePos!!)
        updatePlaybackDuration(player.duration!!)
        updatePlaylistButtons()
    }

    fun updatePlaybackPos(position: Int) {
        playbackPositionTxt.text = prettyTime(position)
        if (!userIsOperatingSeekbar)
            playbackSeekbar.progress = position
        updateDecoderButton()
    }

    private fun updatePlaybackDuration(duration: Int) {
        playbackDurationTxt.text = prettyTime(duration)
        if (!userIsOperatingSeekbar)
            playbackSeekbar.max = duration
    }

    private fun updatePlaybackStatus(paused: Boolean) {
        val r = if (paused) R.drawable.ic_play_arrow_black_24dp else R.drawable.ic_pause_black_24dp
        playBtn.setImageResource(r)
    }

    private fun updateDecoderButton() {
        cycleDecoderBtn.text = if (player.hwdecActive!!) "HW" else "SW"
    }

    private fun updatePlaylistButtons() {
        val plCount = MPVLib.getPropertyInt("playlist-count") ?: 1
        val plPos = MPVLib.getPropertyInt("playlist-pos") ?: 0

        if (plCount == 1) {
            // use View.GONE so the buttons won't take up any space
            prevBtn.visibility = View.GONE
            nextBtn.visibility = View.GONE
            return
        }
        prevBtn.visibility = View.VISIBLE
        nextBtn.visibility = View.VISIBLE

        val g = ContextCompat.getColor(applicationContext, R.color.tint_disabled)
        val w = ContextCompat.getColor(applicationContext, R.color.tint_normal)
        prevBtn.imageTintList = ColorStateList.valueOf(if (plPos == 0) g else w)
        nextBtn.imageTintList = ColorStateList.valueOf(if (plPos == plCount-1) g else w)
    }

    private fun eventPropertyUi(property: String) {
        when (property) {
            "track-list" -> player.loadTracks()
        }
    }

    private fun eventPropertyUi(property: String, value: Boolean) {
        when (property) {
            "pause" -> updatePlaybackStatus(value)
        }
    }

    private fun eventPropertyUi(property: String, value: Long) {
        when (property) {
            "time-pos" -> updatePlaybackPos(value.toInt())
            "duration" -> updatePlaybackDuration(value.toInt())
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun eventPropertyUi(property: String, value: String) {
    }

    private fun eventUi(eventId: Int) {
        when (eventId) {
            MPVLib.mpvEventId.MPV_EVENT_PLAYBACK_RESTART -> updatePlaybackStatus(player.paused!!)
            MPVLib.mpvEventId.MPV_EVENT_START_FILE -> updatePlaylistButtons()
        }
    }

    override fun eventProperty(property: String) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property) }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: Long) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property, value) }
    }

    override fun eventProperty(property: String, value: String) {
        if (!activityIsForeground) return
        runOnUiThread { eventPropertyUi(property, value) }
    }

    override fun event(eventId: Int) {
        // exit properly even when in background
        if (playbackHasStarted && eventId == MPVLib.mpvEventId.MPV_EVENT_IDLE)
            finish()

        if (!activityIsForeground) return

        // deliberately not on the UI thread
        if (eventId == MPVLib.mpvEventId.MPV_EVENT_START_FILE) {
            playbackHasStarted = true
            for (c in onload_commands)
                MPVLib.command(c)
            if (this.statsLuaMode > 0) {
                MPVLib.command(arrayOf("script-binding", "stats/display-stats-toggle"))
                MPVLib.command(arrayOf("script-binding", "stats/${this.statsLuaMode}"))
            }
        }
        runOnUiThread { eventUi(eventId) }
    }

    private fun getInitialBrightness(): Float {
        // "local" brightness first
        val lp = window.attributes
        if (lp.screenBrightness >= 0f)
            return lp.screenBrightness

        // read system pref: https://stackoverflow.com/questions/4544967//#answer-8114307
        // (doesn't work with auto-brightness mode)
        val resolver = applicationContext.contentResolver
        return try {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private var lastDiff = 0f
    private var initialSeek = 0
    private var initialBright = 0f
    private var initialVolume = 0
    private var maxVolume = 0
    private var subHeightDiff = 0f

    override fun onPropertyChange(p: PropertyChange, diff: Float) {
        when (p) {
            PropertyChange.Init -> {
                mightWantToToggleControls = false

                lastDiff = 0f
                initialSeek = player.timePos ?: -1
                initialBright = getInitialBrightness()
                initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                subHeightDiff = 0f

                gestureTextView.visibility = View.VISIBLE
                gestureTextView.text = ""
            }
            PropertyChange.Seek -> {
                // disable seeking on livestreams and when timePos is not available
                if (player.duration ?: 0 == 0 || initialSeek < 0)
                    return
                val newPos = Math.min(Math.max(0, initialSeek + diff.toInt()), player.duration!!)
                val newDiff = newPos - initialSeek
                // seek faster than assigning to timePos but less precise
                MPVLib.command(arrayOf("seek", newPos.toString(), "absolute", "keyframes"))
                updatePlaybackPos(newPos)

                val diffText = (if (newDiff >= 0) "+" else "-") + prettyTime(Math.abs(newDiff.toInt()))
                gestureTextView.text = "${prettyTime(newPos)}\n[$diffText]"
            }
            PropertyChange.SeekSub -> {
                val offset = diff.roundToInt();

                // TODO: Add an OSD message here
                MPVLib.command(arrayOf("sub-seek", offset.toString()))
            }
            PropertyChange.Volume -> {
                val newVolume = Math.min(Math.max(0, initialVolume + (diff * maxVolume).toInt()), maxVolume)
                val newVolumePercent = 100 * newVolume / maxVolume
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

                gestureTextView.text = "V: $newVolumePercent%"
            }
            PropertyChange.Bright -> {
                val lp = window.attributes
                val newBright = Math.min(Math.max(0f, initialBright + diff), 1f)
                lp.screenBrightness = newBright
                window.attributes = lp

                gestureTextView.text = "B: ${Math.round(newBright * 100)}%"
            }
            PropertyChange.SubHeight -> {
                subHeightDiff += lastDiff - diff

                // TODO: This only works with `--sub-ass-override=scale`, this should probably be set as the default
                //       somewhere else
                MPVLib.command(arrayOf("set", "sub-ass-override", "scale"))

                // mpv does accept float values for properties, but rounding would cause a bias in one direction
                val integerChange = truncate(subHeightDiff).toInt()
                if (integerChange !== 0) {
                    // TODO: Add an OSD message here
                    // The argument here should always include a sign, even if the number is positive
                    MPVLib.command(arrayOf("add", "sub-pos", "%+d".format(Locale.US, integerChange)))

                    subHeightDiff -= integerChange
                }
            }
            // TODO: Add a gesture for subtitle scale
            PropertyChange.Finalize -> gestureTextView.visibility = View.GONE
        }

        lastDiff = diff
    }

    companion object {
        private val TAG = "mpv"
        // how long should controls be displayed on screen
        private val CONTROLS_DISPLAY_TIMEOUT = 2000L
        // how far to seek backward/forward with (currently) TV remote buttons
        private val BUTTON_SEEK_RANGE = 10
        // size (px) of the thumbnail displayed with background play notification
        private val THUMB_SIZE = 192
    }
}

internal class FadeOutControlsRunnable(private val activity: MPVActivity, private val controls: View) : Runnable {

    override fun run() {
        // use GONE here instead of INVISIBLE (which makes more sense) because of Android bug with surface views
        // see http://stackoverflow.com/a/12655713/2606891
        controls.animate().alpha(0f).setDuration(500).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                activity.initControls()
            }
        })
    }
}
