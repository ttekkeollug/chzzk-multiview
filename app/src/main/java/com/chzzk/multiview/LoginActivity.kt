package com.chzzk.multiview

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * 네이버(치지직) 계정 로그인 화면.
 * 앱 안의 WebView에서 네이버 공식 로그인 페이지를 열고, 로그인이 끝나 세션 쿠키가 생기면
 * 자동으로 닫힙니다. 아이디/비밀번호는 앱이 읽거나 저장하지 않고 네이버 페이지에서만 입력됩니다.
 * 같은 쿠키를 채팅창 WebView와 API 요청이 공유하므로 채팅 입력, 팔로우 목록, 연령 제한 방송 시청이 가능해집니다.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density

        val title = TextView(this).apply {
            text = "치지직(네이버) 로그인"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((16 * density).toInt(), 0, 0, 0)
        }
        val close = TextView(this).apply {
            text = "닫기"
            setTextColor(0xFF00FFA3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            isClickable = true
            setOnClickListener { finishWith(false) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1F2023.toInt())
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 네이버 모바일 로그인 화면이 나오도록 기기 기본 UA 사용
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    progress.visibility = View.VISIBLE
                    checkLoggedIn()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progress.visibility = View.GONE
                    checkLoggedIn()
                }
            }
            loadUrl(ChzzkApi.LOGIN_URL)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E0E10.toInt())
            fitsSystemWindows = true
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * density).toInt()))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finishWith(false)
            }
        })
    }

    private fun checkLoggedIn() {
        if (!finished && ChzzkApi.hasSession()) {
            CookieManager.getInstance().flush()
            finishWith(true)
        }
    }

    private fun finishWith(success: Boolean) {
        if (finished) return
        finished = true
        setResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
