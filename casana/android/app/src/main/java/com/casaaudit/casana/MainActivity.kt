package com.casaaudit.casana

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.File

/**
 * Casana for Android: the live Casana web app in a WebView, plus what a plain
 * WebView lacks - picking files to attach, saving files the app creates
 * (exports, backups), and opening Casana links from other apps (CASA Suite).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Pages allowed to stay inside the app. Everything else opens in the browser.
    private val allowedHosts = setOf("apex-casa-workdesk.apper.so")

    private fun isInternal(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host in allowedHosts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(FileBridge(), "CasanaAndroid")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isInternal(uri)) return false
                if (uri.scheme == "blob" || uri.scheme == "data") return false
                openExternally(uri)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectDownloadShim(view, url)
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    startActivityForResult(Intent.createChooser(pick, "Choose file"), FILE_REQUEST)
                    true
                } catch (_: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    false
                }
            }
        }

        // Files served from a web address: let the browser download them.
        // blob:/data: files are saved by the shim below (it knows the file name).
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                web.evaluateJavascript("window.__casanaSave&&window.__casanaSave(${jsString(url)},${jsString(name)})", null)
            } else {
                openExternally(Uri.parse(url))
            }
        }

        when {
            savedInstanceState != null -> web.restoreState(savedInstanceState)
            else -> web.loadUrl(linkFrom(intent) ?: BuildConfig.START_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { web.loadUrl(it) }
    }

    /** A Casana https link this app was opened with (from CASA Suite or another app). */
    private fun linkFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return if (intent.action == Intent.ACTION_VIEW && isInternal(data)) data.toString() else null
    }

    /**
     * Web apps save files by clicking a hidden <a download="name" href="blob:...">.
     * A WebView cannot follow blob: links, so read the file in the page and hand
     * it to FileBridge.saveFile with its real name.
     */
    private fun injectDownloadShim(view: WebView, url: String?) {
        val u = url?.let { Uri.parse(it) } ?: return
        if (!isInternal(u)) return
        val js = """
            (function(){
              if(window.__casanaSave||!window.CasanaAndroid)return;
              var revoke=URL.revokeObjectURL;
              URL.revokeObjectURL=function(u){setTimeout(function(){revoke.call(URL,u)},60000)};
              window.__casanaSave=function(href,name){
                fetch(href).then(function(r){return r.blob()}).then(function(b){
                  var fr=new FileReader();
                  fr.onload=function(){window.CasanaAndroid.saveFile(String(fr.result),name||'download',b.type||'')};
                  fr.readAsDataURL(b);
                }).catch(function(){window.CasanaAndroid.failed(name||'download')});
              };
              function wanted(a){return a&&a.hasAttribute&&a.hasAttribute('download')&&/^(blob|data):/.test(a.href||'')}
              var click=HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click=function(){
                if(wanted(this)){window.__casanaSave(this.href,this.getAttribute('download'));return}
                return click.apply(this,arguments);
              };
              document.addEventListener('click',function(e){
                var a=e.target&&e.target.closest?e.target.closest('a'):null;
                if(wanted(a)){e.preventDefault();window.__casanaSave(a.href,a.getAttribute('download'))}
              },true);
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != FILE_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        var result: Array<Uri>? = null
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            result = if (clip != null && clip.itemCount > 0) {
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            } else {
                data.data?.let { arrayOf(it) }
            }
        }
        fileCallback?.onReceiveValue(result)
        fileCallback = null
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    inner class FileBridge {
        /** Saves a file the page created (data: URL) to Downloads and opens it. */
        @JavascriptInterface
        fun saveFile(dataUrl: String?, fileName: String?, mimeType: String?) {
            if (dataUrl.isNullOrBlank()) return
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            val header = dataUrl.substring(0, comma)
            val bytes = try {
                if (header.endsWith(";base64")) Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                else Uri.decode(dataUrl.substring(comma + 1)).toByteArray()
            } catch (_: Exception) {
                failed(fileName); return
            }
            val name = safeName(fileName)
            val mime = mimeType?.takeIf { it.isNotBlank() }
                ?: header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }

            runOnUiThread {
                try {
                    val saved: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, name)
                            put(MediaStore.Downloads.MIME_TYPE, mime)
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("no uri")
                        contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                        uri
                    } else {
                        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                        val file = File(dir, name)
                        file.writeBytes(bytes)
                        Uri.fromFile(file)
                    }
                    Toast.makeText(this@MainActivity, "Saved: $name", Toast.LENGTH_LONG).show()
                    if (saved.scheme == "content") {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(saved, mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "Could not save $name", Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun failed(fileName: String?) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Could not save ${safeName(fileName)}", Toast.LENGTH_LONG).show()
            }
        }

        private fun safeName(name: String?): String {
            val n = (name ?: "").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            return n.ifBlank { "download" }.take(120)
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

    companion object {
        private const val FILE_REQUEST = 4101
    }
}
