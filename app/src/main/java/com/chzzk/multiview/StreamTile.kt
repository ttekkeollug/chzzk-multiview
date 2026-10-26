package com.chzzk.multiview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 방송 하나를 담당하는 타일. 자체 ExoPlayer를 가지므로 채널별 독립 볼륨이 가능합니다.
 * 타일 크기에 맞춰 화질 상한을 자동으로 낮춰 여러 개를 띄워도 데이터/디코더 부담을 줄입니다.
 */
@OptIn(UnstableApi::class)
@SuppressLint("ViewConstructor")
class StreamTile(
    context: Context,
    val slot: Slot,
    private val listener: Listener,
) : FrameLayout(context) {

    interface Listener {
        fun onSlotChanged(tile: StreamTile)
        fun onChatToggle(tile: StreamTile)
        fun onRemove(tile: StreamTile)
        fun onSolo(tile: StreamTile)
        fun onFocusToggle(tile: StreamTile)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var loadJob: Job? = null
    private var player: ExoPlayer? = null
    private var active = false
    private var maxHeightCap = Int.MAX_VALUE

    private val playerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true)
    }

    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = rounded(0xAA000000.toInt(), 8)
    }

    private val nameView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = rounded(0x99000000.toInt(), 6)
    }

    private val muteBtn = iconButton("🔊")
    private val chatBtn = iconButton("💬")
    private val focusBtn = iconButton("⛶")
    private val removeBtn = iconButton("✕")
    private val volumeLabel = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        minWidth = dp(30)
        gravity = Gravity.CENTER
    }
    private val volumeBar = SeekBar(context).apply {
        max = 100
        progress = (slot.volume * 100).toInt()
    }

    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(4), dp(2))
        background = rounded(0xB3000000.toInt(), 0)
        addView(muteBtn)
        addView(volumeBar, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(volumeLabel)
        addView(chatBtn)
        addView(focusBtn)
        addView(removeBtn)
    }

    private val hideControls = Runnable { setControlsVisible(false) }
    private val retry = Runnable { start() }

    init {
        setBackgroundColor(Color.BLACK)
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(statusView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(nameView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(6), dp(6), dp(60), 0)
        })
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        nameView.text = slot.name

        // 탭: 컨트롤 표시/숨김, 길게 누르기: 이 방송만 소리 듣기(솔로)
        setOnClickListener { setControlsVisible(controls.visibility != View.VISIBLE) }
        setOnLongClickListener {
            listener.onSolo(this)
            flashStatus("🎧 이 방송만 듣기")
            true
        }
        muteBtn.setOnClickListener {
            slot.muted = !slot.muted
            applyVolume()
            listener.onSlotChanged(this)
            bumpHideTimer()
        }
        chatBtn.setOnClickListener {
            listener.onChatToggle(this)
            bumpHideTimer()
        }
        focusBtn.setOnClickListener {
            listener.onFocusToggle(this)
            bumpHideTimer()
        }
        removeBtn.setOnClickListener { listener.onRemove(this) }
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                slot.volume = progress / 100f
                if (slot.muted && progress > 0) slot.muted = false
                applyVolume()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                listener.onSlotChanged(this@StreamTile)
                bumpHideTimer()
            }
        })
        applyVolume()
        refreshChatButton()
        bumpHideTimer()
    }

    // ---------- 재생 ----------

    fun start() {
        active = true
        handler.removeCallbacks(retry)
        loadJob?.cancel()
        setStatus("불러오는 중…")
        loadJob = scope.launch {
            val result = try {
                ChzzkApi.liveDetail(slot.channelId)
            } catch (e: Exception) {
                null
            }
            if (!active) return@launch
            when (result) {
                null -> {
                    setStatus("네트워크 오류 · 10초 후 재시도")
                    scheduleRetry(10_000)
                }
                is ChzzkApi.LiveResult.Live -> {
                    updateName(result.channelName)
                    play(result.hlsUrl)
                }
                is ChzzkApi.LiveResult.Offline -> {
                    result.channelName?.let { updateName(it) }
                    releasePlayer()
                    setStatus("오프라인 · 방송 시작 시 자동 재생")
                    scheduleRetry(60_000)
                }
                is ChzzkApi.LiveResult.Restricted -> {
                    result.channelName?.let { updateName(it) }
                    releasePlayer()
                    setStatus(result.reason)
                }
            }
        }
    }

    fun stop() {
        active = false
        loadJob?.cancel()
        handler.removeCallbacks(retry)
        releasePlayer()
    }

    fun destroy() {
        stop()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    private fun play(url: String) {
        releasePlayer()
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(ChzzkApi.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
        // 하드웨어 디코더가 부족하면 소프트웨어 디코더로 자동 전환
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(context, renderers).build()
        p.setMediaSource(HlsMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(url)))
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> setStatus("버퍼링…")
                    Player.STATE_READY -> setStatus(null)
                    Player.STATE_ENDED -> {
                        setStatus("방송 종료 확인 중…")
                        scheduleRetry(5_000)
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                setStatus("재연결 중…")
                scheduleRetry(4_000)
            }
        })
        player = p
        playerView.player = p
        applyVolume()
        applyQualityCap()
        p.playWhenReady = true
        p.prepare()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun scheduleRetry(delayMs: Long) {
        if (!active) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delayMs)
    }

    // ---------- 볼륨 ----------

    fun applyVolume() {
        val effective = if (slot.muted) 0f else slot.volume
        player?.volume = effective
        muteBtn.text = if (effective == 0f) "🔇" else "🔊"
        volumeLabel.text = if (slot.muted) "음소거" else "${(slot.volume * 100).toInt()}"
        if (volumeBar.progress != (slot.volume * 100).toInt()) volumeBar.progress = (slot.volume * 100).toInt()
        nameView.alpha = if (effective == 0f) 0.6f else 1f
    }

    // ---------- 화질 자동 조절 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 타일의 실제 픽셀 높이를 기준으로 필요한 만큼만 화질을 받음
        val videoH = minOf(h, w * 9 / 16)
        maxHeightCap = when {
            videoH <= 300 -> 480
            videoH <= 560 -> 720
            else -> Int.MAX_VALUE
        }
        applyQualityCap()
    }

    private fun applyQualityCap() {
        val p = player ?: return
        val maxW = if (maxHeightCap == Int.MAX_VALUE) Int.MAX_VALUE else maxHeightCap * 16 / 9 + 2
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(maxW, maxHeightCap)
            .build()
    }

    // ---------- UI 상태 ----------

    fun refreshChatButton() {
        chatBtn.alpha = if (slot.chatOn) 1f else 0.45f
        chatBtn.background = if (slot.chatOn) rounded(0x5500FFA3, 6) else null
    }

    fun setFocused(isFocused: Boolean) {
        focusBtn.text = if (isFocused) "▣" else "⛶"
    }

    private fun updateName(name: String) {
        if (name.isNotBlank() && name != slot.name) {
            slot.name = name
            listener.onSlotChanged(this)
        }
        nameView.text = slot.name
    }

    private fun setStatus(text: String?) {
        statusView.text = text ?: ""
        statusView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun flashStatus(text: String) {
        val wasVisible = statusView.visibility == View.VISIBLE
        val previous = statusView.text
        setStatus(text)
        handler.postDelayed({ if (wasVisible) setStatus(previous.toString()) else setStatus(null) }, 1200)
    }

    private fun setControlsVisible(visible: Boolean) {
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        nameView.visibility = controls.visibility
        if (visible) bumpHideTimer()
    }

    private fun bumpHideTimer() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 5_000)
    }

    private fun iconButton(label: String) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        minWidth = dp(36)
        minHeight = dp(34)
        isClickable = true
        isFocusable = true
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
