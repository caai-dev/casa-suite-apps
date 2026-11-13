package com.casaaudit.casana

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.casaaudit.common.UpdateChecker
import java.io.File

/**
 * Casana for Android: the live Casana web app in a WebView.
 *
 * A WebView is a stripped-down browser: by itself it cannot pick files, save
 * downloads, open popups/new tabs or copy to the clipboard. This class adds
 * those back in a general way (for whatever the web app does), so Casana
 * behaves like it does in Chrome:
 *  - file inputs open the phone's file picker
 *  - <a download> / blob: files are saved to Downloads and opened
 *  - window.open(...) (new tab, "about:blank" then a link) works: Casana pages
 *    open here, other sites in the browser, blob: files in the phone's viewer
 *  - navigator.clipboard.writeText copies to the phone clipboard
 *  - Casana links from other apps (CASA Suite) open here
 *  - a "new update available" card when a newer Casana app is published
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var updater: UpdateChecker
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    // Pages allowed to stay inside the app. Everything else opens in the browser.
    private val allowedHosts = setOf("apex-casa-workdesk.apper.so")

    private fun isInternal(uri: Uri): Boolean =
        uri.scheme == "https" && uri.host in allowedHosts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        updater = UpdateChecker(this, "casana", "Casana", BuildConfig.VERSION_CODE)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }
        web.addJavascriptInterface(Bridge(), "CasanaAndroid")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (isInternal(uri)) return false
                if (uri.scheme == "blob" || uri.scheme == "data" || uri.scheme == "about") return false
                openExternally(uri)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                injectShim(view, url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                injectShim(view, url)
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
        // blob:/data: files are read by the page shim and saved by Bridge.saveFile.
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                web.evaluateJavascript("window.__casanaSave&&window.__casanaSave(${jsString(url)},${jsString(name)})", null)
            } else {
                openExternally(Uri.parse(url))
            }
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            loadFresh(linkFrom(intent) ?: BuildConfig.START_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        updater.check()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkFrom(intent)?.let { loadFresh(it) }
    }

    /** Always ask the server for the page itself, so a new Publish shows up at once. */
    private fun loadFresh(url: String) {
        web.loadUrl(url, mapOf("Cache-Control" to "no-cache"))
    }

    /** A Casana https link this app was opened with (from CASA Suite or another app). */
    private fun linkFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return if (intent.action == Intent.ACTION_VIEW && isInternal(data)) data.toString() else null
    }

    /**
     * Browser features a WebView lacks, added inside Casana pages only:
     * saving blob:/data: files with their real name, window.open, clipboard.
     */
    private fun injectShim(view: WebView, url: String?) {
        val u = url?.let { Uri.parse(it) } ?: return
        if (!isInternal(u)) return
        val js = """
            (function(){
              if(window.__casanaSave||!window.CasanaAndroid)return;
              var A=window.CasanaAndroid;

              // Pages often revoke a file link right after clicking it; keep it alive
              // long enough to be read.
              var revoke=URL.revokeObjectURL;
              URL.revokeObjectURL=function(u){setTimeout(function(){revoke.call(URL,u)},60000)};

              window.__casanaSave=function(href,name){
                fetch(href).then(function(r){return r.blob()}).then(function(b){
                  var fr=new FileReader();
                  fr.onload=function(){A.saveFile(String(fr.result),name||'',b.type||'')};
                  fr.readAsDataURL(b);
                }).catch(function(){A.failed(name||'')});
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

              // window.open / new tabs: Casana pages here, files in the phone's
              // viewer, other sites in the browser. Returns a stand-in window so
              // code like  w=window.open('about:blank'); w.location.href=link  works.
              function go(v){
                v=String(v||'');
                if(!v||v.indexOf('about:')===0)return;
                if(/^(blob|data):/.test(v)){window.__casanaSave(v,'');return}
                var a=document.createElement('a');a.href=v;
                if(a.host===location.host){location.href=a.href}else{A.openExternal(a.href)}
              }
              window.open=function(url){
                var w={closed:false,opener:null,
                  close:function(){w.closed=true},focus:function(){},blur:function(){},
                  addEventListener:function(){},removeEventListener:function(){},postMessage:function(){},
                  document:{write:function(){},writeln:function(){},close:function(){},open:function(){}}};
                var loc={assign:go,replace:go,reload:function(){},toString:function(){return 'about:blank'}};
                Object.defineProperty(loc,'href',{get:function(){return 'about:blank'},set:go});
                Object.defineProperty(w,'location',{get:function(){return loc},set:go});
                go(url);
                return w;
              };

              // Clipboard
              try{
                if(navigator.clipboard){
                  navigator.clipboard.writeText=function(t){A.copy(String(t==null?'':t));return Promise.resolve()};
                }
              }catch(e){}
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

    inner class Bridge {
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
            val mime = mimeType?.takeIf { it.isNotBlank() }
                ?: header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }
            val name = withExtension(safeName(fileName), mime)

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
                    Toast.makeText(this@MainActivity, "Saved to Downloads: $name", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@MainActivity, "Could not open ${safeName(fileName)}", Toast.LENGTH_LONG).show()
            }
        }

        /** Links to other sites (from window.open) go to the browser / mail / phone app. */
        @JavascriptInterface
        fun openExternal(url: String?) {
            val uri = url?.let { Uri.parse(it) } ?: return
            if (uri.scheme !in setOf("https", "http", "mailto", "tel")) return
            runOnUiThread {
                if (isInternal(uri)) web.loadUrl(uri.toString()) else openExternally(uri)
            }
        }

        @JavascriptInterface
        fun copy(text: String?) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Casana", text ?: ""))
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun safeName(name: String?): String {
            val n = (name ?: "").replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            return n.ifBlank { "Casana file" }.take(120)
        }

        private fun withExtension(name: String, mime: String): String {
            if (name.contains('.')) return name
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
            return "$name.$ext"
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
