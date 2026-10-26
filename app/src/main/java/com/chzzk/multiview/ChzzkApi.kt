package com.chzzk.multiview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 치지직 공개 웹 API 래퍼.
 * 로그인 없이 접근 가능한 엔드포인트만 사용합니다.
 */
object ChzzkApi {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private const val BASE = "https://api.chzzk.naver.com"
    private val CHANNEL_ID_REGEX = Regex("[0-9a-f]{32}")

    data class ChannelInfo(
        val channelId: String,
        val channelName: String,
        val isLive: Boolean,
        val liveTitle: String? = null,
        val viewers: Int = 0,
    )

    sealed class LiveResult {
        data class Live(
            val channelName: String,
            val title: String,
            val hlsUrl: String,
            val viewers: Int,
        ) : LiveResult()

        data class Offline(val channelName: String?) : LiveResult()
        data class Restricted(val channelName: String?, val reason: String) : LiveResult()
    }

    /** URL, 공유 텍스트, 채널 ID 등 어떤 형태든 32자리 채널 ID를 뽑아냅니다. */
    fun parseChannelId(input: String): String? =
        CHANNEL_ID_REGEX.find(input.lowercase())?.value

    fun chatUrl(channelId: String) = "https://chzzk.naver.com/live/$channelId/chat"

    suspend fun searchChannels(keyword: String, size: Int = 20): List<ChannelInfo> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(keyword, "UTF-8")
            val content = getJson("$BASE/service/v1/search/channels?keyword=$q&offset=0&size=$size")
                .optJSONObject("content") ?: return@withContext emptyList()
            val data = content.optJSONArray("data") ?: return@withContext emptyList()
            buildList {
                for (i in 0 until data.length()) {
                    val ch = data.optJSONObject(i)?.optJSONObject("channel") ?: continue
                    val id = ch.optString("channelId")
                    if (id.isEmpty()) continue
                    add(
                        ChannelInfo(
                            channelId = id,
                            channelName = ch.optString("channelName", id),
                            isLive = ch.optBoolean("openLive", false),
                        )
                    )
                }
            }.sortedByDescending { it.isLive }
        }

    suspend fun popularLives(size: Int = 30): List<ChannelInfo> = withContext(Dispatchers.IO) {
        val content = getJson("$BASE/service/v1/lives?size=$size&sortType=POPULAR")
            .optJSONObject("content") ?: return@withContext emptyList()
        val data = content.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val live = data.optJSONObject(i) ?: continue
                val ch = live.optJSONObject("channel") ?: continue
                val id = ch.optString("channelId")
                if (id.isEmpty()) continue
                add(
                    ChannelInfo(
                        channelId = id,
                        channelName = ch.optString("channelName", id),
                        isLive = true,
                        liveTitle = live.optString("liveTitle"),
                        viewers = live.optInt("concurrentUserCount"),
                    )
                )
            }
        }
    }

    suspend fun liveDetail(channelId: String): LiveResult = withContext(Dispatchers.IO) {
        val root = getJson("$BASE/service/v3/channels/$channelId/live-detail")
        if (root.isNull("content")) return@withContext LiveResult.Offline(null)
        val c = root.getJSONObject("content")
        val name = c.optJSONObject("channel")?.optString("channelName")

        if (c.optString("status") != "OPEN") return@withContext LiveResult.Offline(name)

        if (c.isNull("livePlaybackJson")) {
            val reason = if (c.optBoolean("adult")) "연령 제한 방송 (앱에서 재생 불가)"
            else "재생 정보를 받을 수 없는 방송"
            return@withContext LiveResult.Restricted(name, reason)
        }

        val playback = JSONObject(c.getString("livePlaybackJson"))
        val media = playback.optJSONArray("media")
            ?: return@withContext LiveResult.Restricted(name, "재생 경로 없음")

        var hls: String? = null
        var fallback: String? = null
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            if (m.optString("protocol") != "HLS") continue
            val path = m.optString("path")
            if (path.isEmpty()) continue
            // 일반 HLS가 저지연(LLHLS)보다 다중 재생 시 버퍼링에 강함
            if (m.optString("mediaId") == "HLS") hls = path else if (fallback == null) fallback = path
        }
        val url = hls ?: fallback ?: return@withContext LiveResult.Restricted(name, "HLS 경로 없음")

        LiveResult.Live(
            channelName = name ?: channelId,
            title = c.optString("liveTitle"),
            hlsUrl = url,
            viewers = c.optInt("concurrentUserCount"),
        )
    }

    private fun getJson(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
