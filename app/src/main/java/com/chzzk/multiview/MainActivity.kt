package com.chzzk.multiview

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity(), StreamTile.Listener {

    private lateinit var store: SlotStore
    private val slots = ArrayList<Slot>()
    private val tiles = ArrayList<StreamTile>()

    private lateinit var content: LinearLayout
    private lateinit var grid: MultiGridLayout
    private lateinit var chatPanel: ChatPanel
    private lateinit var emptyView: TextView
    private lateinit var muteAllBtn: TextView
    private lateinit var chatAllBtn: TextView

    /** 채팅 패널 전체 숨김 (개별 채팅 on/off 상태는 유지) */
    private var chatHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        store = SlotStore(this)
        buildUi()

        slots.addAll(store.load())
        slots.forEach { addTile(it) }
        syncAll()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (grid.focused != null) {
                    setFocus(null)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        tiles.forEach { it.start() }
        chatPanel.resumeAll()
    }

    override fun onStop() {
        super.onStop()
        tiles.forEach { it.stop() }
        chatPanel.pauseAll()
        store.save(slots)
    }

    override fun onDestroy() {
        tiles.forEach { it.destroy() }
        chatPanel.destroyAll()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 회전 시 액티비티를 재생성하지 않고 배치만 바꿔 재생이 끊기지 않게 합니다.
        applyOrientation()
    }

    // ---------- UI 구성 ----------

    private fun buildUi() {
        grid = MultiGridLayout(this)
        chatPanel = ChatPanel(this).apply {
            onCloseRequest = { id -> slots.find { it.channelId == id }?.let { toggleChat(it) } }
        }
        content = LinearLayout(this).apply {
            addView(grid)
            addView(chatPanel)
        }
        emptyView = TextView(this).apply {
            text = "＋ 버튼을 눌러 치지직 방송을 추가하세요\n\n" +
                "· 방송 탭: 컨트롤 표시\n" +
                "· 방송 길게 누르기: 그 방송만 소리 듣기\n" +
                "· 💬 채팅 켜기/끄기 · ⛶ 크게 보기\n" +
                "· 치지직 앱에서 '공유 → 치지직 멀티뷰'로도 추가 가능"
            setTextColor(0xFFBBBBBB.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }

        val addBtn = fab("＋").apply { setOnClickListener { openAddDialog() } }
        muteAllBtn = fab("🔊").apply { setOnClickListener { toggleMuteAll() } }
        chatAllBtn = fab("💬").apply { setOnClickListener { toggleChatPanel() } }
        val fabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(chatAllBtn)
            addView(muteAllBtn)
            addView(addBtn)
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0E0E10.toInt())
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(fabs, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            })
        }
        setContentView(root)
        applyOrientation()
    }

    private fun applyOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        content.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (landscape) {
            grid.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 7f)
            chatPanel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f)
        } else {
            grid.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 6f)
            chatPanel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f)
        }
        content.requestLayout()
    }

    // ---------- 슬롯 관리 ----------

    private fun addTile(slot: Slot): StreamTile {
        val tile = StreamTile(this, slot, this)
        tiles.add(tile)
        grid.addView(tile)
        return tile
    }

    private fun addChannel(channelId: String, name: String) {
        if (slots.any { it.channelId == channelId }) {
            toast("이미 추가된 채널입니다")
            return
        }
        if (slots.size >= MAX_STREAMS) {
            toast("최대 ${MAX_STREAMS}개까지 볼 수 있습니다")
            return
        }
        val slot = Slot(channelId, name)
        slots.add(slot)
        val tile = addTile(slot)
        tile.start()
        syncAll()
    }

    private fun openAddDialog() {
        AddChannelDialog(this, lifecycleScope, slots.map { it.channelId }.toSet()) { id, name ->
            addChannel(id, name)
        }.show()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val id = ChzzkApi.parseChannelId(text)
        if (id == null) {
            toast("치지직 채널 링크를 찾지 못했습니다")
        } else {
            addChannel(id, id.take(8))
        }
        intent.action = null
    }

    private fun syncAll() {
        val visibleChats = if (chatHidden) emptyList() else slots
        chatPanel.sync(visibleChats)
        tiles.forEach {
            it.refreshChatButton()
            it.setFocused(grid.focused === it)
        }
        emptyView.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
        muteAllBtn.text = if (slots.isNotEmpty() && slots.all { it.muted }) "🔇" else "🔊"
        chatAllBtn.alpha = if (chatHidden) 0.45f else 1f
        store.save(slots)
    }

    private fun toggleChat(slot: Slot) {
        slot.chatOn = !slot.chatOn
        if (slot.chatOn) chatHidden = false
        syncAll()
    }

    private fun toggleChatPanel() {
        if (slots.none { it.chatOn }) {
            toast("방송을 탭한 뒤 💬 버튼으로 채팅을 켜세요")
            return
        }
        chatHidden = !chatHidden
        syncAll()
    }

    private fun toggleMuteAll() {
        if (slots.isEmpty()) return
        val muteAll = !slots.all { it.muted }
        slots.forEach { it.muted = muteAll }
        tiles.forEach { it.applyVolume() }
        syncAll()
    }

    private fun setFocus(tile: StreamTile?) {
        grid.focused = tile
        syncAll()
    }

    // ---------- StreamTile.Listener ----------

    override fun onSlotChanged(tile: StreamTile) {
        syncAll()
    }

    override fun onChatToggle(tile: StreamTile) = toggleChat(tile.slot)

    override fun onRemove(tile: StreamTile) {
        if (grid.focused === tile) grid.focused = null
        tile.destroy()
        grid.removeView(tile)
        tiles.remove(tile)
        slots.remove(tile.slot)
        syncAll()
    }

    override fun onSolo(tile: StreamTile) {
        slots.forEach { it.muted = it !== tile.slot }
        if (tile.slot.volume == 0f) tile.slot.volume = 1f
        tiles.forEach { it.applyVolume() }
        syncAll()
    }

    override fun onFocusToggle(tile: StreamTile) {
        setFocus(if (grid.focused === tile) null else tile)
    }

    // ---------- 유틸 ----------

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun fab(label: String) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xAA000000.toInt())
            setStroke(dp(1), 0x6600FFA3)
        }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(6) }
        isClickable = true
        isFocusable = true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_STREAMS = 9
    }
}
