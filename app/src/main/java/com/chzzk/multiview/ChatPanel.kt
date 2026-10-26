package com.chzzk.multiview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 선택한 방송들의 채팅창을 세로로 쌓아 보여주는 패널.
 * 치지직 웹 채팅 팝업을 그대로 사용하므로 네이버 로그인 시 채팅 입력도 가능합니다.
 * 한 번 연 채팅 WebView는 캐시해서 다른 채팅을 켜고 끌 때 다시 로딩되지 않게 합니다.
 */
class ChatPanel(context: Context) : LinearLayout(context) {

    private class Entry(val container: LinearLayout, val title: TextView, val web: WebView)

    private val entries = LinkedHashMap<String, Entry>()
    var onCloseRequest: ((channelId: String) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF141517.toInt())
        CookieManager.getInstance().setAcceptCookie(true)
    }

    /** 현재 켜져 있어야 할 채팅 목록으로 패널을 동기화합니다. */
    fun sync(slots: List<Slot>) {
        val wanted = slots.filter { it.chatOn }
        val wantedIds = wanted.map { it.channelId }.toSet()

        // 꺼진 채팅 정리
        val removed = entries.keys.filter { it !in wantedIds }
        removed.forEach { id ->
            entries.remove(id)?.let {
                removeView(it.container)
                it.web.stopLoading()
                it.web.destroy()
            }
        }

        // 순서를 화면 타일 순서와 맞춰 다시 붙임
        removeAllViews()
        wanted.forEach { slot ->
            val entry = entries.getOrPut(slot.channelId) { createEntry(slot.channelId) }
            entry.title.text = "💬  ${slot.name}"
            addView(entry.container, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        visibility = if (wanted.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 로그인/로그아웃 후 쿠키가 바뀌었을 때 채팅창을 새로 불러옵니다. */
    fun reloadAll() = entries.values.forEach { it.web.reload() }

    fun pauseAll() = entries.values.forEach { it.web.onPause() }
    fun resumeAll() = entries.values.forEach { it.web.onResume() }

    fun destroyAll() {
        entries.values.forEach {
            it.web.stopLoading()
            it.web.destroy()
        }
        entries.clear()
        removeAllViews()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createEntry(channelId: String): Entry {
        val density = resources.displayMetrics.density
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding((10 * density).toInt(), (6 * density).toInt(), 0, (6 * density).toInt())
        }
        val close = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            minWidth = (40 * density).toInt()
            isClickable = true
            setOnClickListener { onCloseRequest?.invoke(channelId) }
        }
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1F2023.toInt())
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val web = WebView(context).apply {
            setBackgroundColor(0xFF141517.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.userAgentString = ChzzkApi.USER_AGENT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = WebViewClient() // 로그인 등 이동도 앱 안에서 처리
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(ChzzkApi.chatUrl(channelId))
        }
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // 패널 간 구분선
            setPadding(0, 0, 0, (2 * density).toInt())
            setBackgroundColor(0xFF000000.toInt())
        }
        return Entry(container, title, web)
    }
}
