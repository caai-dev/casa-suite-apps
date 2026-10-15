package com.casaaudit.suite

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var web: WebView

    // Pages allowed to stay inside the app (and to see the native bridge).
    private val allowedHosts = setOf("casa-suite-protocol.apper.so")

    private fun isInternal(uri: Uri): Boolean {
        if (uri.scheme == "file" && (uri.path ?: "").startsWith("/android_asset/")) return true
        return uri.scheme == "https" && uri.host in allowedHosts
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false          // asset:// pages still load via file:///android_asset
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(NativeBridge(), "CasaAndroid")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isInternal(uri)) return false
                openExternally(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                injectShim(view, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectShim(view, url)
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(BuildConfig.START_URL)
    }

    /** Defines window.CasaNative (same shape as the Windows app) on trusted pages only. */
    private fun injectShim(view: WebView, url: String?) {
        val u = url?.let { Uri.parse(it) } ?: return
        if (!isInternal(u)) return
        val js = """
            (function(){
              if(window.CasaNative||!window.CasaAndroid)return;
              window.CasaNative={
                platform:'android', version:1,
                isInstalled:function(p){return Promise.resolve(window.CasaAndroid.isInstalled(p))},
                open:function(p){return Promise.resolve(window.CasaAndroid.open(p))}
              };
              window.dispatchEvent(new Event('casanativeready'));
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun openExternally(uri: Uri) {
        if (uri.scheme == "intent") {
            openIntentUrl(uri.toString())
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /**
     * Handles Chrome-style intent:// links from older web code:
     * 1) if the target package is installed, launch it directly
     * 2) else try the intent itself
     * 3) else open S.browser_fallback_url (e.g. Play Store page)
     */
    private fun openIntentUrl(url: String) {
        val parsed = try {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            return
        }
        val fallback = parsed.getStringExtra("browser_fallback_url")

        // market://launch?id=<pkg> via Play Store -> launch <pkg> ourselves
        val marketPkg = parsed.data?.takeIf { it.scheme == "market" }?.getQueryParameter("id")
        val pkg = marketPkg ?: parsed.`package`
        if (pkg != null && pkg != "com.android.vending") {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                try {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
                } catch (_: Exception) {
                }
            }
        }
        if (marketPkg == null) {
            try {
                parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                parsed.component = null
                parsed.selector = null
                startActivity(parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            } catch (_: Exception) {
            }
        }
        if (!fallback.isNullOrBlank()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun isInstalled(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            return packageManager.getLaunchIntentForPackage(pkg) != null
        }

        /** Returns true if the app was found and launched, false if not installed. */
        @JavascriptInterface
        fun open(pkg: String?): Boolean {
            if (pkg.isNullOrBlank()) return false
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runOnUiThread {
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
            return true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }
}
