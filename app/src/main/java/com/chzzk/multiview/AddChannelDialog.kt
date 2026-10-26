package com.chzzk.multiview

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 채널 추가 다이얼로그.
 * - 처음 열면 현재 인기 라이브 목록
 * - 스트리머 이름 검색 (라이브 중인 채널이 위로)
 * - 채널 URL/ID 붙여넣기 시 바로 추가
 */
class AddChannelDialog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val alreadyAdded: Set<String>,
    private val startWithFollowings: Boolean = false,
    private val onPick: (channelId: String, name: String) -> Unit,
) {
    private var job: Job? = null
    private lateinit var dialog: AlertDialog
    private val items = ArrayList<ChzzkApi.ChannelInfo>()

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val input = EditText(context).apply {
        hint = "스트리머 이름 · 채널 URL · 채널 ID"
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
    }
    private val info = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFFAAAAAA.toInt())
        setPadding(dp(4), dp(6), dp(4), dp(6))
    }
    private val progress = ProgressBar(context).apply { visibility = View.GONE }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                addView(TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.WHITE)
                })
                addView(TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(0xFFAAAAAA.toInt())
                    maxLines = 1
                })
            }
            val ch = items[position]
            val title = row.getChildAt(0) as TextView
            val sub = row.getChildAt(1) as TextView
            val added = ch.channelId in alreadyAdded
            title.text = buildString {
                append(if (ch.isLive) "🔴 " else "⚪ ")
                append(ch.channelName)
                if (added) append("  (추가됨)")
            }
            title.alpha = if (added) 0.5f else 1f
            val subText = when {
                !ch.liveTitle.isNullOrBlank() -> "${ch.liveTitle} · 시청자 ${"%,d".format(ch.viewers)}"
                ch.isLive -> "방송 중"
                else -> "오프라인 (추가해두면 방송 시작 시 자동 재생)"
            }
            sub.text = subText
            return row
        }
    }

    fun show() {
        val searchBtn = Button(context).apply { text = "검색" }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(searchBtn)
        }
        val popularBtn = Button(context).apply {
            text = "🔥 인기 라이브"
            setOnClickListener { loadPopular() }
        }
        val followBtn = Button(context).apply {
            text = "⭐ 팔로잉"
            setOnClickListener { loadFollowings() }
        }
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(popularBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(followBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val list = ListView(context).apply {
            adapter = this@AddChannelDialog.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val ch = items[pos]
                if (ch.channelId !in alreadyAdded) {
                    onPick(ch.channelId, ch.channelName)
                    dialog.dismiss()
                }
            }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(bar)
            addView(tabs)
            addView(info)
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320)))
        }

        searchBtn.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit(); true
            } else false
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("방송 추가")
            .setView(root)
            .setNegativeButton("닫기", null)
            .create()
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        if (startWithFollowings) loadFollowings() else loadPopular()
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) {
            loadPopular(); return
        }
        val id = ChzzkApi.parseChannelId(text)
        if (id != null) {
            if (id in alreadyAdded) {
                info.text = "이미 화면에 있는 채널입니다."
            } else {
                onPick(id, id.take(8))
                dialog.dismiss()
            }
            return
        }
        run("“$text” 검색 결과") { ChzzkApi.searchChannels(text) }
    }

    private fun loadPopular() = run("지금 인기 라이브") { ChzzkApi.popularLives() }

    private fun loadFollowings() {
        if (!ChzzkApi.hasSession()) {
            items.clear()
            adapter.notifyDataSetChanged()
            info.text = "팔로잉 목록은 상단 👤 버튼으로 치지직 로그인 후 볼 수 있습니다."
            return
        }
        run("내 팔로잉 (방송 중 우선)") { ChzzkApi.followings() }
    }

    private fun run(label: String, block: suspend () -> List<ChzzkApi.ChannelInfo>) {
        job?.cancel()
        progress.visibility = View.VISIBLE
        info.text = "불러오는 중…"
        job = scope.launch {
            val result = try {
                block()
            } catch (e: Exception) {
                null
            }
            progress.visibility = View.GONE
            items.clear()
            if (result == null) {
                info.text = "불러오기 실패. 네트워크를 확인해 주세요."
            } else {
                items.addAll(result)
                info.text = if (result.isEmpty()) "$label: 결과 없음" else label
            }
            adapter.notifyDataSetChanged()
        }
    }
}
