package com.custom.fireflydesktop

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = desktopUserAgent
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        webView.addJavascriptInterface(BlobDownloadBridge(), "AndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Force 1280px desktop layout width & intercept blob SVG downloads
                val js = """
                    (function() {
                        var meta = document.querySelector('meta[name="viewport"]');
                        if (!meta) {
                            meta = document.createElement('meta');
                            meta.name = 'viewport';
                            document.head.appendChild(meta);
                        }
                        meta.content = 'width=1280, initial-scale=0.5, user-scalable=yes';

                        if (!window._blobHooked) {
                            window._blobHooked = true;
                            const origClick = HTMLAnchorElement.prototype.click;
                            HTMLAnchorElement.prototype.click = function() {
                                if (this.href && this.href.startsWith('blob:')) {
                                    const filename = this.download || ('firefly_vector_' + Date.now() + '.svg');
                                    fetch(this.href)
                                        .then(r => r.blob())
                                        .then(blob => {
                                            const reader = new FileReader();
                                            reader.onloadend = function() {
                                                AndroidDownloader.saveBase64File(reader.result, filename, blob.type || 'image/svg+xml');
                                            };
                                            reader.readAsDataURL(blob);
                                        });
                                    return;
                                }
                                return origClick.apply(this, arguments);
                            };
                        }
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }
        }

        webView.webChromeClient = WebChromeClient()

        webView.setDownloadListener { url, _, _, mimeType, _ ->
            if (url.startsWith("data:")) {
                val filename = "firefly_vector_${System.currentTimeMillis()}.svg"
                saveDataUrl(url, filename, mimeType ?: "image/svg+xml")
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl("https://firefly.adobe.com/generate/vector")
        }
    }

    private fun saveDataUrl(dataUrl: String, filename: String, mimeType: String) {
        try {
            val base64Part = dataUrl.substringAfter(",")
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(downloadsDir, filename)
                FileOutputStream(outFile).use { out -> out.write(bytes) }
            }

            runOnUiThread {
                Toast.makeText(this, "Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun saveBase64File(dataUrl: String, filename: String, mimeType: String) {
            saveDataUrl(dataUrl, filename, mimeType)
        }
    }
}
