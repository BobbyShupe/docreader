package com.bobby.docreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = DocumentStore(this)

        val uriString = intent.getStringExtra(KEY_URI) ?: return finish()
        val uri = Uri.parse(uriString)
        currentUri = uri
        val name = intent.getStringExtra(KEY_NAME) ?: "Document"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val title = MaterialTextView(this).apply {
            text = name
            textSize = 24f
            setPadding(24, 32, 24, 16)
        }
        root.addView(title)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(scrollView)

        hScrollView = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView?.addView(hScrollView)

        textContent = MaterialTextView(this).apply {
            textSize = 18f
            setPadding(24, 16, 24, 80)
            movementMethod = android.text.method.ScrollingMovementMethod()
            isHorizontalScrollBarEnabled = true
        }
        hScrollView?.addView(textContent)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
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
                    Log.d(TAG, "onPageFinished called for $url")
                    restoreWebViewState()
                    saveTotalHeightIfNeeded()
                }
            }

            // Track max scroll aggressively
            viewTreeObserver.addOnScrollChangedListener {
                if (visibility == View.VISIBLE) {
                    val currentY = scrollY
                    if (currentY > maxObservedScrollY) {
                        maxObservedScrollY = currentY
                        Log.d(TAG, "Scroll changed - new maxObservedScrollY: $maxObservedScrollY")
                        // Save updated height on every significant scroll change
                        saveTotalHeightIfNeeded()
                    }
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

        val hasHtmlExtension = fileNameLower.contains(".htm") || fileNameLower.contains(".html")
        val looksLikeHtml = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024)
            val bytesRead = input.read(buffer)
            if (bytesRead > 50) {
                val start = String(buffer, 0, bytesRead, Charsets.UTF_8).trim().lowercase()
                start.startsWith("<!doctype html") ||
                        start.startsWith("<html") ||
                        start.contains("<head>") ||
                        start.contains("<body>") ||
                        start.contains("<title>") ||
                        start.contains("<meta ") ||
                        start.contains("<!doctype ")
            } else false
        } ?: false

        val useWebView = hasHtmlExtension || looksLikeHtml || mimeType.startsWith("text/html")

        Log.d(TAG, "Loading file: $fileNameLower | mime: $mimeType | useWebView: $useWebView")

        withContext(Dispatchers.Main) {
            if (useWebView) {
                textContent?.isVisible = false
                scrollView?.isVisible = false
                webView?.isVisible = true
                webView?.loadUrl(uri.toString())
                maxObservedScrollY = 0
            } else {
                val text = try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: "Cannot open file"
                } catch (e: Exception) {
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
                if (webView?.visibility != View.VISIBLE) return@postDelayed

                val view = webView ?: return@postDelayed

                // ────────────────────────────────────────────────
                // Most accurate for WebView in most cases
                val realContentHeight = view.contentHeight          // in CSS pixels
                val scale = view.scale                              // zoom factor
                val totalHeightInPixels = (realContentHeight * scale).toInt()

                val visibleHeight = view.height
                val currentScroll = view.scrollY

                // Only save if we have meaningful data
                if (totalHeightInPixels > visibleHeight * 1.2 && totalHeightInPixels > 300) {
                    val previous = lastSavedHeight
                    if (totalHeightInPixels > previous + 300 || previous < 500) {
                        store.saveTotalHeight(uri, totalHeightInPixels)
                        lastSavedHeight = totalHeightInPixels
                        Log.d(TAG, "Saved accurate WebView height: $totalHeightInPixels px (was $previous)")
                    }
                }

                // Also keep maxObserved as fallback/secondary check
                val estimated = maxObservedScrollY.coerceAtLeast(currentScroll) + visibleHeight + 400
                if (estimated > totalHeightInPixels + 200) {
                    // rare case — content grew after first measurement
                    store.saveTotalHeight(uri, estimated)
                    lastSavedHeight = estimated
                    Log.d(TAG, "Fallback save - estimated: $estimated")
                }

            }, 1200)   // give more time for layout/images to settle
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
                saveTotalHeightIfNeeded()
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

            // Save height on pause too
            saveTotalHeightIfNeeded()
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