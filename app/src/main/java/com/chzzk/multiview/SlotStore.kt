package com.chzzk.multiview

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 화면에 올라간 방송 하나의 상태. */
data class Slot(
    val channelId: String,
    var name: String,
    /** 0~100 */
    var volume: Int = 100,
    var muted: Boolean = false,
    var chatOn: Boolean = false,
)

/** 선택한 채널 구성/볼륨/채팅 상태를 앱 재시작 후에도 유지합니다. */
class SlotStore(context: Context) {
    private val prefs = context.getSharedPreferences("multiview", Context.MODE_PRIVATE)

    fun load(): MutableList<Slot> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Slot(
                    channelId = o.getString("id"),
                    name = o.optString("name", o.getString("id")),
                    volume = (if (o.has("vol100")) o.optInt("vol100", 100)
                    else Math.round(o.optDouble("vol", 1.0) * 100).toInt()).coerceIn(0, 100),
                    muted = o.optBoolean("muted", false),
                    chatOn = o.optBoolean("chat", false),
                )
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(slots: List<Slot>) {
        val arr = JSONArray()
        slots.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.channelId)
                    .put("name", it.name)
                    .put("vol100", it.volume)
                    .put("muted", it.muted)
                    .put("chat", it.chatOn)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "slots"
    }
}
