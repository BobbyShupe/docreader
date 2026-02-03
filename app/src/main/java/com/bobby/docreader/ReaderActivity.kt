package com.bobby.docreader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLConnection
import kotlin.text.Charsets

class ReaderActivity : AppCompatActivity() {

    private lateinit var store: DocumentStore
    private var currentUri: Uri? = null

    private var scrollView: ScrollView? = null
    private var hScrollView: HorizontalScrollView? = null
    private var textContent: MaterialTextView? = null
    private var webView: WebView? = null

    private var maxObservedScrollY = 0
    private var lastSavedHeight = 0

    private val TAG = "ReaderActivity"

    // Green-on-black colors
    private val BG = Color.BLACK
    private val TEXT = Color.parseColor("#00FF41")  // bright green

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = DocumentStore(this)

        val uriString = intent.getStringExtra(KEY_URI)
        if (uriString.isNullOrBlank()) {
            Log.e(TAG, "No URI provided")
            finish()
            return
        }

        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URI: $uriString", e)
            finish()
            return
        }

        currentUri = uri
        val name = intent.getStringExtra(KEY_NAME) ?: "Document"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BG)
            // Disable system forced dark mode on root
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                isForceDarkAllowed = false
            }
        }

        val title = MaterialTextView(this).apply {
            text = name
            textSize = 24f
            setPadding(24, 32, 24, 16)
            setTextColor(TEXT)
            setBackgroundColor(BG)
        }
        root.addView(title)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(BG)
        }
        root.addView(scrollView)

        hScrollView = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(BG)
        }
        scrollView?.addView(hScrollView)

        textContent = MaterialTextView(this).apply {
            textSize = 18f
            setPadding(24, 16, 24, 80)
            movementMethod = android.text.method.ScrollingMovementMethod()
            isHorizontalScrollBarEnabled = true
            setBackgroundColor(BG)
            setTextColor(TEXT)
            setLinkTextColor(TEXT)
            setTextIsSelectable(true)
        }
        hScrollView?.addView(textContent)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(BG)

            // Disable ALL forced dark mode on WebView
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                isForceDarkAllowed = false
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) { // Android 13+
                settings.isAlgorithmicDarkeningAllowed = false
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = true
                allowFileAccess = true
                allowContentAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    request?.url?.let { url ->
                        if (url.scheme == "http" || url.scheme == "https") {
                            startActivity(Intent(Intent.ACTION_VIEW, url))
                            return true
                        }
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "onPageFinished: $url")

                    // Very aggressive override to force green text on black
                    view?.evaluateJavascript("""
                        (function() {
                            // Remove all previous styles
                            document.querySelectorAll('style, link[rel*="stylesheet"]').forEach(el => el.remove());

                            // Force style
                            var s = document.createElement('style');
                            s.innerHTML = `
                                html, body {
                                    background: #000000 !important;
                                    color: #00ff41 !important;
                                    margin: 0;
                                    padding: 16px;
                                    font-family: monospace;
                                }
                                *, *::before, *::after {
                                    background: transparent !important;
                                    color: #00ff41 !important;
                                    border-color: #004d00 !important;
                                }
                                a {
                                    color: #00cc00 !important;
                                }
                                pre, code, kbd, samp {
                                    background: #111111 !important;
                                    color: #00ff41 !important;
                                }
                            `;
                            document.head.appendChild(s);

                            // Repeated force-apply to fight JS/CSS changes
                            const force = () => {
                                document.body.style.backgroundColor = '#000000';
                                document.body.style.color = '#00ff41';
                                document.querySelectorAll('*').forEach(el => {
                                    el.style.color = '#00ff41';
                                    el.style.backgroundColor = 'transparent';
                                });
                            };
                            force();
                            setInterval(force, 200);
                        })();
                    """.trimIndent(), null)

                    restoreWebViewState()
                    saveTotalHeightIfNeeded()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    Log.e(TAG, "WebView error: ${error?.description} (code: ${error?.errorCode})")
                    textContent?.text = "HTML failed to load: ${error?.description ?: "Unknown"}\n\nRaw content:\n" + try {
                        contentResolver.openInputStream(currentUri!!)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) { "Cannot read file" }
                    textContent?.isVisible = true
                    webView?.isVisible = false
                }
            }
        }
        root.addView(webView)

        setContentView(root)

        CoroutineScope(Dispatchers.Main).launch {
            loadContent(uri)
        }
    }

    private suspend fun loadContent(uri: Uri) = withContext(Dispatchers.IO) {
        val fileNameLower = uri.lastPathSegment?.lowercase() ?: ""
        val mimeType = URLConnection.guessContentTypeFromName(fileNameLower) ?: "text/plain"

        // Force WebView mode for EVERY file → best green-on-black reliability
        val useWebView = true  // ← this line is the key change

        Log.d(TAG, "Loading file: $fileNameLower | mime: $mimeType | forced useWebView: $useWebView")

        withContext(Dispatchers.Main) {
            if (useWebView) {
                textContent?.isVisible = false
                scrollView?.isVisible = false
                webView?.isVisible = true
                try {
                    webView?.loadUrl(uri.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "WebView load failed", e)
                    textContent?.text = "Failed to load in WebView: ${e.message}\n\nRaw content:\n" + try {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (ex: Exception) { "Cannot read file" }
                    textContent?.isVisible = true
                    webView?.isVisible = false
                }
                maxObservedScrollY = 0
            } else {
                // This branch will almost never run now
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: "Cannot open file"
                } catch (e: Exception) {
                    Log.e(TAG, "Text load error", e)
                    "Error reading file: ${e.message}"
                }

                webView?.isVisible = false
                textContent?.isVisible = true
                scrollView?.isVisible = true
                textContent?.text = text

                val savedPos = store.getPosition(uri)
                val savedHPos = store.getHorizontalPosition(uri)
                if (savedPos > 0 || savedHPos > 0) {
                    scrollView?.post {
                        if (savedPos > 0) scrollView?.scrollTo(0, savedPos)
                        if (savedHPos > 0) hScrollView?.scrollTo(savedHPos, 0)
                    }
                }

                textContent?.postDelayed({
                    val totalH = textContent?.layout?.height ?: 0
                    Log.d(TAG, "Text mode total height: $totalH")
                    if (totalH > 100) {
                        store.saveTotalHeight(uri, totalH)
                    }
                }, 800)
            }
        }
    }

    private fun saveTotalHeightIfNeeded() {
        currentUri?.let { uri ->
            webView?.postDelayed({
                val visibleHeight = webView?.height ?: 0
                val currentScroll = webView?.scrollY ?: 0
                val currentMax = maxObservedScrollY.coerceAtLeast(currentScroll)

                val estimatedTotal = currentMax + visibleHeight + 800

                Log.d(TAG, "saveTotalHeightIfNeeded - currentScroll=$currentScroll, maxObserved=$currentMax, visibleHeight=$visibleHeight, estimatedTotal=$estimatedTotal")

                if (estimatedTotal > lastSavedHeight + 500 || lastSavedHeight == 0) {
                    store.saveTotalHeight(uri, estimatedTotal)
                    lastSavedHeight = estimatedTotal
                    Log.d(TAG, "Saved new total height: $estimatedTotal for $uri")
                }
            }, 1000)
        }
    }

    private fun restoreWebViewState() {
        currentUri?.let { uri ->
            val savedZoom = store.getZoom(uri)
            val savedVScroll = store.getPosition(uri)
            val savedHScroll = store.getHorizontalPosition(uri)

            Log.d(TAG, "Restoring state: zoom=$savedZoom, v=$savedVScroll, h=$savedHScroll")

            webView?.postDelayed({
                webView?.setInitialScale((savedZoom * 100).toInt())
                if (savedVScroll > 0 || savedHScroll > 0) {
                    webView?.scrollTo(savedHScroll, savedVScroll)
                }
            }, 800)
        }
    }

    override fun onPause() {
        super.onPause()
        currentUri?.let { uri ->
            val vPos: Int
            val hPos: Int
            val zoom: Float

            if (webView?.isVisible == true) {
                vPos = webView?.scrollY ?: 0
                hPos = webView?.scrollX ?: 0
                zoom = webView?.scale ?: 1.0f

                if (vPos > maxObservedScrollY) {
                    maxObservedScrollY = vPos
                    Log.d(TAG, "onPause - updated maxObservedScrollY to $maxObservedScrollY")
                }
            } else {
                vPos = scrollView?.scrollY ?: 0
                hPos = hScrollView?.scrollX ?: 0
                zoom = 1.0f
            }

            if (vPos > 100 || hPos > 10) {
                store.savePosition(uri, vPos)
                store.saveHorizontalPosition(uri, hPos)
            }

            if (webView?.isVisible == true && zoom != 1.0f) {
                store.saveZoom(uri, zoom)
            }
        }
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val KEY_URI = "uri"
        private const val KEY_NAME = "name"
        private const val KEY_POSITION = "pos"

        fun start(context: Context, uri: Uri, name: String, lastPosition: Int) {
            context.startActivity(Intent(context, ReaderActivity::class.java).apply {
                putExtra(KEY_URI, uri.toString())
                putExtra(KEY_NAME, name)
                putExtra(KEY_POSITION, lastPosition)
            })
        }
    }
}