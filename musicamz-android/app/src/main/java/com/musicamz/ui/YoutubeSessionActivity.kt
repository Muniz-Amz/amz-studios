package com.musicamz.ui

import android.app.Activity
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.musicamz.download.CookieStore
import com.musicamz.download.YoutubeWebSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** A user-driven, temporary browsing session. Saving does not claim authentication or download success. */
class YoutubeSessionActivity : ComponentActivity() {
    private var browser: WebView? = null
    private lateinit var status: TextView
    private lateinit var saveButton: Button
    private lateinit var reloadButton: Button
    private lateinit var closeButton: Button
    private var saving = false
    private var pageReady = false
    private var failed = false
    private var currentUrl = "https://www.youtube.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Session screens must not enter recent-app thumbnails or screenshots containing account data.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setResult(Activity.RESULT_CANCELED)
        currentUrl = YoutubeWebSession.initialUrl(intent.getStringExtra(EXTRA_LINK))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setBackgroundColor(Color.rgb(20, 23, 32))
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(4))
        }
        layout.addView(label("Verificar acesso ao YouTube", 20f))
        layout.addView(label("Abra o vídeo ou conclua a verificação na página. Toque em Usar esta sessão para guardá-la somente neste aparelho e substituir a sessão anterior. Isso não garante o download.", 13f))
        layout.addView(label("Se o Google recusar o login nesta janela, volte ao AMZ. A importação por arquivo continua nas opções avançadas.", 12f))
        status = label("Abrindo uma sessão temporária…", 12f)
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layout.addView(status)
        val actions = LinearLayout(this)
        saveButton = Button(this).apply { text = "Usar esta sessão"; isEnabled = false; setOnClickListener { saveSession() } }
        closeButton = Button(this).apply { text = "Voltar"; setOnClickListener { if (!saving) finish() } }
        reloadButton = Button(this).apply {
            text = "Recarregar"; isEnabled = false
            setOnClickListener { browser?.loadUrl(currentUrl) }
        }
        actions.addView(closeButton, LinearLayout.LayoutParams(0, -2, 0.7f))
        actions.addView(reloadButton, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(saveButton, LinearLayout.LayoutParams(0, -2, 1.4f))
        layout.addView(actions)
        setContentView(layout)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!saving) finish() }
        })
        try {
            val web = WebView(this)
            browser = web
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
                @Suppress("DEPRECATION")
                saveFormData = false
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(web, false)
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            web.setDownloadListener { _, _, _, _, _ ->
                status.text = "Volte ao AMZ para baixar o áudio pelo botão Baixar MP3."
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = YoutubeWebSession.allowsNavigation(request.url.toString())
                    if (!allowed && request.isForMainFrame) {
                        status.text = "Esta janela abre somente YouTube e as páginas de acesso do Google."
                    }
                    return !allowed
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageReady = false; failed = false; saveButton.isEnabled = false
                    if (!YoutubeWebSession.allowsNavigation(url)) {
                        view.stopLoading(); failed = true
                        status.text = "Este endereço não pode ser aberto nesta janela."
                        return
                    }
                    currentUrl = url
                    status.text = "Carregando ${URI(url).host}…"
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (failed || !YoutubeWebSession.allowsNavigation(url) || saving) return
                    pageReady = true
                    // Only offer saving while on YouTube, never on an account/login error page.
                    val onYoutube = URI(url).host in setOf("youtube.com", "www.youtube.com", "m.youtube.com")
                    saveButton.isEnabled = onYoutube
                    status.text = if (onYoutube) "Página aberta. Se conseguiu acessar, toque em Usar esta sessão."
                        else "Conclua a verificação. Se o Google recusar o acesso, use Voltar."
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) pageError("Não foi possível abrir a página. Confira a conexão e toque em Recarregar.")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) pageError("O site recusou esta página. Você pode recarregar ou voltar ao AMZ.")
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    pageError("Não foi possível confirmar a segurança da conexão. Volte e tente mais tarde.")
                }
            }
            layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            // A fresh window must not silently reuse a previous person's Google login.
            clearBrowserSession {
                if (!isFinishing && !isDestroyed) {
                    reloadButton.isEnabled = true
                    web.loadUrl(currentUrl)
                }
            }
        } catch (_: Exception) {
            status.text = "A janela não pôde ser aberta. Atualize o Android System WebView ou use as opções avançadas."
        }
    }

    private fun pageError(message: String) {
        failed = true; pageReady = false; saveButton.isEnabled = false
        status.text = message
    }

    private fun saveSession() {
        if (saving || !pageReady) return
        val headers = YoutubeWebSession.cookieOrigins.associateWith { CookieManager.getInstance().getCookie(it) }
        saving = true
        saveButton.isEnabled = false; closeButton.isEnabled = false; reloadButton.isEnabled = false
        browser?.stopLoading(); browser?.visibility = View.INVISIBLE
        status.text = "Guardando sessão no aparelho…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { CookieStore(applicationContext).importWebSession(headers) }
                setResult(Activity.RESULT_OK)
                finish()
            } catch (_: Exception) {
                status.text = "Não foi possível guardar a sessão. Abra o vídeo e tente novamente. Sua sessão anterior foi mantida."
                browser?.visibility = View.VISIBLE
                saveButton.isEnabled = pageReady
            } finally {
                saving = false; closeButton.isEnabled = true; reloadButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        browser?.let { web ->
            web.stopLoading()
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.clearCache(true); web.clearHistory(); web.destroy()
            clearBrowserSession()
        }
        browser = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LINK = "youtube_link"
        fun clearBrowserSession(onCleared: () -> Unit = {}) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                onCleared()
            }
        }
    }
}
