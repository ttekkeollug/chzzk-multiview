package com.chzzk.multiview

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), StreamTile.Listener {

    private lateinit var store: SlotStore
    private val slots = ArrayList<Slot>()
    private val tiles = ArrayList<StreamTile>()

    private lateinit var content: SplitLayout
    private lateinit var topBar: LinearLayout
    private lateinit var barHandle: View
    private var barShown = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val autoHideBar = Runnable { if (slots.isNotEmpty()) setBarShown(false) }
    private lateinit var grid: MultiGridLayout
    private lateinit var chatPanel: ChatPanel
    private lateinit var emptyView: TextView
    private lateinit var muteAllBtn: TextView
    private lateinit var chatAllBtn: TextView
    private lateinit var accountBtn: TextView
    private var nickname: String? = null

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) onSessionChanged(showWelcome = true)
    }

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
        refreshAccount(showWelcome = false)
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
        grid = MultiGridLayout(this).apply {
            onOrderChanged = { order -> applyOrder(order) }
        }
        chatPanel = ChatPanel(this).apply {
            onCloseRequest = { id -> slots.find { it.channelId == id }?.let { toggleChat(it) } }
        }
        content = SplitLayout(this, grid, chatPanel).apply {
            onChanged = { dock, ratio -> saveSplit(dock, ratio) }
        }
        emptyView = TextView(this).apply {
            text = "＋ 버튼을 눌러 치지직 방송을 추가하세요\n\n" +
                "· 방송 탭: 컨트롤 표시\n" +
                "· 방송 길게 눌러 끌기: 원하는 자리로 이동\n" +
                "· 볼륨 0~100: 슬라이더, −/＋(길게 10씩), 숫자 탭해서 직접 입력\n" +
                "· 🎧 이 방송만 듣기 · 💬 채팅 켜기/끄기 · ⛶ 크게 보기\n" +
                "· 👤 치지직 로그인: 채팅 입력, 팔로잉 목록, 연령 제한 방송\n" +
                "· 채팅창 경계선 끌기: 크기 조절 · ⠿ 손잡이 끌기: 위치 이동\n" +
                "· 상단 메뉴는 3초 뒤 숨겨짐 · 위쪽 가운데 막대를 탭하면 다시 표시\n" +
                "· 치지직 앱에서 '공유 → 치지직 멀티뷰'로도 추가 가능"
            setTextColor(0xFFBBBBBB.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
        }

        val addBtn = fab("＋").apply { setOnClickListener { keepBar(); openAddDialog() } }
        muteAllBtn = fab("🔊").apply { setOnClickListener { keepBar(); toggleMuteAll() } }
        chatAllBtn = fab("💬").apply { setOnClickListener { keepBar(); toggleChatPanel() } }
        accountBtn = fab("👤").apply { setOnClickListener { keepBar(); openAccount() } }
        val hideBtn = fab("⌃").apply { setOnClickListener { setBarShown(false) } }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(0xE6141517.toInt())
                setStroke(dp(1), 0x4400FFA3)
            }
            addView(accountBtn)
            addView(chatAllBtn)
            addView(muteAllBtn)
            addView(addBtn)
            addView(hideBtn)
            // 메뉴 바 위의 터치는 아래 방송으로 전달되지 않게 막습니다.
            isClickable = true
        }
        // 메뉴가 숨겨졌을 때 상단 가운데에 남는 작은 손잡이. 탭하거나 아래로 끌면 메뉴가 나옵니다.
        barHandle = FrameLayout(this).apply {
            val pill = View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(0x99FFFFFF.toInt())
                }
            }
            addView(pill, FrameLayout.LayoutParams(dp(44), dp(5), Gravity.CENTER))
            isClickable = true
            setOnClickListener { setBarShown(true) }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0E0E10.toInt())
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(barHandle, FrameLayout.LayoutParams(dp(96), dp(22), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(BAR_TOP_MARGIN_DP)
            })
            // 배치가 바뀔 때마다 메뉴 바와 겹치는 부분을 다시 계산
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> uiHandler.post { updateBarInsets() } }
        }
        setContentView(root)
        topBar.visibility = View.INVISIBLE
        applyOrientation()
        setBarShown(true)
    }

    // ---------- 상단 메뉴 바 (자동 숨김) ----------

    private fun setBarShown(show: Boolean) {
        uiHandler.removeCallbacks(autoHideBar)
        if (show) {
            uiHandler.postDelayed(autoHideBar, BAR_AUTO_HIDE_MS)
            if (barShown) return
            barShown = true
            topBar.visibility = View.VISIBLE
            topBar.animate().cancel()
            topBar.translationY = -dp(60).toFloat()
            topBar.alpha = 0f
            topBar.animate().translationY(0f).alpha(1f).setDuration(180).start()
            barHandle.visibility = View.GONE
            updateBarInsets()
        } else {
            if (!barShown) return
            barShown = false
            topBar.animate().cancel()
            topBar.animate().translationY(-dp(60).toFloat()).alpha(0f).setDuration(180).withEndAction {
                if (!barShown) {
                    topBar.visibility = View.INVISIBLE
                    barHandle.visibility = View.VISIBLE
                    updateBarInsets()
                }
            }.start()
        }
    }

    /** 메뉴 바 버튼을 누를 때마다 자동 숨김 시간을 연장 */
    private fun keepBar() {
        if (barShown) {
            uiHandler.removeCallbacks(autoHideBar)
            uiHandler.postDelayed(autoHideBar, BAR_AUTO_HIDE_MS)
        }
    }

    /**
     * 메뉴 바가 보이는 동안 그 아래에 걸치는 방송 타일의 버튼/이름과 채팅창 머리글을
     * 메뉴 바 높이만큼 내려서 서로 겹치지 않게 합니다.
     */
    private fun updateBarInsets() {
        val barRect = Rect()
        if (barShown && topBar.width > 0) {
            val loc = IntArray(2)
            topBar.getLocationInWindow(loc)
            val top = loc[1] - topBar.translationY.toInt()
            barRect.set(loc[0], top, loc[0] + topBar.width, top + topBar.height + dp(4))
        }
        val loc = IntArray(2)
        tiles.forEach { tile ->
            tile.getLocationInWindow(loc)
            val r = Rect(loc[0], loc[1], loc[0] + tile.width, loc[1] + tile.height)
            val inset = if (!barRect.isEmpty && Rect.intersects(r, barRect)) barRect.bottom - r.top else 0
            tile.setTopInset(inset.coerceAtLeast(0))
        }
        chatPanel.getLocationInWindow(loc)
        val cr = Rect(loc[0], loc[1], loc[0] + chatPanel.width, loc[1] + chatPanel.height)
        val chatInset = if (!barRect.isEmpty && chatPanel.visibility == View.VISIBLE && Rect.intersects(cr, barRect)) {
            (barRect.bottom - cr.top).coerceAtLeast(0)
        } else 0
        if (chatPanel.paddingTop != chatInset) chatPanel.setPadding(0, chatInset, 0, 0)
    }

    // ---------- 채팅창 크기/위치 저장 ----------

    private fun splitKey() =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"

    private fun saveSplit(dock: SplitLayout.Dock, ratio: Float) {
        getSharedPreferences("layout", MODE_PRIVATE).edit()
            .putString("dock_${splitKey()}", dock.name)
            .putFloat("ratio_${splitKey()}", ratio)
            .apply()
        uiHandler.post { updateBarInsets() }
    }

    /** 가로/세로 화면마다 사용자가 정한 채팅창 위치와 비율을 따로 기억합니다. */
    private fun applyOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val prefs = getSharedPreferences("layout", MODE_PRIVATE)
        val defaultDock = if (landscape) SplitLayout.Dock.RIGHT else SplitLayout.Dock.BOTTOM
        val defaultRatio = if (landscape) 0.3f else 0.4f
        content.dock = prefs.getString("dock_${splitKey()}", null)
            ?.let { name -> SplitLayout.Dock.values().firstOrNull { it.name == name } } ?: defaultDock
        content.ratio = prefs.getFloat("ratio_${splitKey()}", defaultRatio)
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
        val slot = Slot(channelId, name)
        slots.add(slot)
        val tile = addTile(slot)
        tile.start()
        syncAll()
    }

    private fun openAddDialog(startWithFollowings: Boolean = false) {
        AddChannelDialog(
            this, lifecycleScope, slots.map { it.channelId }.toSet(), startWithFollowings,
        ) { id, name ->
            addChannel(id, name)
        }.show()
    }

    // ---------- 계정 연동 ----------

    private fun openAccount() {
        if (!ChzzkApi.hasSession()) {
            loginLauncher.launch(Intent(this, LoginActivity::class.java))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("👤 ${nickname ?: "치지직 계정"} 로그인됨")
            .setItems(arrayOf("⭐ 팔로잉 방송 추가", "🔄 계정 정보 새로고침", "🚪 로그아웃")) { _, which ->
                when (which) {
                    0 -> openAddDialog(startWithFollowings = true)
                    1 -> refreshAccount(showWelcome = true)
                    2 -> logout()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun logout() {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            nickname = null
            onSessionChanged(showWelcome = false)
            toast("로그아웃했습니다")
        }
    }

    /** 로그인 상태가 바뀌면 채팅창과 방송(연령 제한 등)을 새 세션으로 다시 불러옵니다. */
    private fun onSessionChanged(showWelcome: Boolean) {
        chatPanel.reloadAll()
        tiles.forEach { it.start() }
        refreshAccount(showWelcome)
    }

    private fun refreshAccount(showWelcome: Boolean) {
        updateAccountButton()
        if (!ChzzkApi.hasSession()) return
        lifecycleScope.launch {
            val status = try {
                ChzzkApi.userStatus()
            } catch (e: Exception) {
                null
            }
            if (status == null) return@launch
            nickname = if (status.loggedIn) status.nickname else null
            updateAccountButton()
            if (showWelcome) {
                toast(if (status.loggedIn) "${status.nickname ?: "치지직"} 계정으로 연동되었습니다" else "로그인 세션이 만료되었습니다")
            }
        }
    }

    private fun updateAccountButton() {
        val on = ChzzkApi.hasSession()
        accountBtn.alpha = if (on) 1f else 0.55f
        (accountBtn.background as? GradientDrawable)?.setStroke(dp(if (on) 2 else 1), if (on) 0xFF00FFA3.toInt() else 0x6600FFA3)
    }

    // ---------- 드래그 순서 ----------

    private fun applyOrder(order: List<View>) {
        val newTiles = order.filterIsInstance<StreamTile>()
        if (newTiles.size != tiles.size) return
        tiles.clear()
        tiles.addAll(newTiles)
        slots.clear()
        slots.addAll(newTiles.map { it.slot })
        syncAll()
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
        // 방송이 하나도 없으면 메뉴를 계속 보여 줍니다.
        if (slots.isEmpty()) setBarShown(true)
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
        if (tile.slot.volume == 0) tile.slot.volume = 100
        tiles.forEach { it.applyVolume() }
        syncAll()
    }

    override fun onStartDrag(tile: StreamTile) {
        grid.startDrag(tile, tile.slot.name)
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
        const val BAR_AUTO_HIDE_MS = 3_000L
        const val BAR_TOP_MARGIN_DP = 6
    }
}
