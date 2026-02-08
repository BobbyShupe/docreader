package com.bobby.docreader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLConnection
import kotlin.text.Charsets

class ReaderActivity : AppCompatActivity() {

    private lateinit var store: DocumentStore
    private var currentUri: Uri? = null

    private var scrollView: ScrollView? = null
    private var hScrollView: HorizontalScrollView? = null
    private var textContent: TextView? = null
    private var webView: WebView? = null

    private var maxObservedScrollY = 0
    private var lastSavedHeight = 0

    private val TAG = "ReaderActivity"

    // Green-on-black colors
    private val BACKGROUND_COLOR = Color.BLACK
    private val TEXT_COLOR = Color.parseColor("#00FF41")

    // RSVP state and UI
    private var rsvpOverlay: FrameLayout? = null
    private var rsvpWordView: TextView? = null
    private var rsvpSpeedSlider: SeekBar? = null
    private var rsvpPauseButton: MaterialButton? = null
    private var rsvpJob: Job? = null
    private var isRsvpRunning = false
    private var currentWpm = 400

    // Remember state before RSVP
    private var wasUsingWebViewBeforeRsvp = false
    private var wordsBeforeRsvp: List<String> = emptyList()
    private var currentWordIndex = 0

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
            Log.e(TAG, "Failed to parse URI: $uriString", e)
            finish()
            return
        }

        currentUri = uri
        val name = intent.getStringExtra(KEY_NAME) ?: "Document"

        val root = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BACKGROUND_COLOR)
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(contentLayout)

        val title = MaterialTextView(this).apply {
            text = name
            textSize = 24f
            setPadding(24, 32, 24, 16)
            setTextColor(TEXT_COLOR)
            setBackgroundColor(BACKGROUND_COLOR)
        }
        contentLayout.addView(title)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(BACKGROUND_COLOR)
        }
        contentLayout.addView(scrollView)

        hScrollView = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(BACKGROUND_COLOR)
        }
        scrollView?.addView(hScrollView)

        textContent = MaterialTextView(this).apply {
            textSize = 18f
            setPadding(24, 16, 24, 80)
            movementMethod = android.text.method.ScrollingMovementMethod()
            isHorizontalScrollBarEnabled = true
            setBackgroundColor(BACKGROUND_COLOR)
            setTextColor(TEXT_COLOR)
            setLinkTextColor(TEXT_COLOR)
            setTextIsSelectable(true)
        }
        hScrollView?.addView(textContent)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(BACKGROUND_COLOR)

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

                    view?.evaluateJavascript("""
                        (function() {
                            var style = document.createElement('style');
                            style.innerHTML = `
                                html, body {
                                    background: #000000 !important;
                                    color: #00FF41 !important;
                                    margin: 0;
                                    padding: 16px;
                                    font-family: monospace;
                                }
                                body * {
                                    background: transparent !important;
                                    color: #00FF41 !important;
                                }
                                p, div, span, h1, h2, h3, h4, h5, h6, li, td, th {
                                    color: #00FF41 !important;
                                }
                                a {
                                    color: #00CC00 !important;
                                }
                            `;
                            document.head.appendChild(style);
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
                    currentUri?.let { safeUri ->
                        textContent?.text = "Failed to load: ${error?.description ?: "Unknown"}\n\nRaw content:\n" + try {
                            contentResolver.openInputStream(safeUri)?.bufferedReader()?.use { it.readText() }
                                ?: "Cannot read file"
                        } catch (ex: Exception) { "Error reading file: ${ex.message}" }
                    } ?: run {
                        textContent?.text = "Failed to load: ${error?.description ?: "Unknown"}\n(No URI available)"
                    }
                    textContent?.isVisible = true
                    webView?.isVisible = false
                }
            }
        }
        contentLayout.addView(webView)

        // RSVP overlay
// RSVP overlay (full-screen word display)
        rsvpOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(BACKGROUND_COLOR)
            visibility = View.GONE

            rsvpWordView = TextView(this@ReaderActivity).apply {
                textSize = 80f
                setTextColor(TEXT_COLOR)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            addView(rsvpWordView)

            // Speed slider - moved higher up
            rsvpSpeedSlider = SeekBar(this@ReaderActivity).apply {
                max = 1000
                progress = currentWpm - 200
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 120   // ← increased from 120 to avoid overlap
                    leftMargin = 20
                    rightMargin = 20
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        currentWpm = 200 + progress
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            addView(rsvpSpeedSlider)

            // Pause button - moved to left side
            rsvpPauseButton = MaterialButton(this@ReaderActivity).apply {
                text = "Pause"
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.START
                    bottomMargin = 180   // aligned with slider height
                    leftMargin = 20
                }
                setOnClickListener {
                    if (rsvpJob?.isActive == true) {
                        rsvpJob?.cancel()
                        text = "Resume"
                    } else {
                        startRsvp()
                        text = "Pause"
                    }
                }
            }
            addView(rsvpPauseButton)
        }
        root.addView(rsvpOverlay)

// Floating RSVP trigger button - also moved up slightly for consistency
        val rsvpButton = MaterialButton(this).apply {
            text = "RSVP"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                bottomMargin = 180   // ← increased from 40 to match the overlay elements
                rightMargin = 40
            }
            elevation = 8f
            setBackgroundColor(Color.parseColor("#006400"))
            setOnClickListener {
                Log.d(TAG, "RSVP button clicked")
                toggleRsvp()
            }
        }
        root.addView(rsvpButton)

        setContentView(root)

        CoroutineScope(Dispatchers.Main).launch {
            loadContent(uri)
        }

        rsvpOverlay?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                exitRsvp()
                true
            } else false
        }
    }

    private fun toggleRsvp() {
        if (isRsvpRunning) {
            exitRsvp()
        } else {
            startRsvp()
        }
    }

    private fun startRsvp() {
        if (isRsvpRunning) return
        isRsvpRunning = true

        // Remember current mode and scroll position
        wasUsingWebViewBeforeRsvp = webView?.isVisible == true

        val currentScrollY = if (wasUsingWebViewBeforeRsvp) {
            webView?.scrollY ?: 0
        } else {
            scrollView?.scrollY ?: 0
        }

        val totalHeight = if (wasUsingWebViewBeforeRsvp) {
            lastSavedHeight.coerceAtLeast(webView?.contentHeight ?: 1000)
        } else {
            textContent?.layout?.height ?: 1000
        }

        val progress = if (totalHeight > 0) currentScrollY.toFloat() / totalHeight else 0f
        Log.d(TAG, "RSVP start - scrollY: $currentScrollY / totalHeight: $totalHeight, progress: $progress")

        rsvpOverlay?.isVisible = true
        rsvpPauseButton?.text = "Pause"

        CoroutineScope(Dispatchers.Main).launch {
            var fullText: String

            if (wasUsingWebViewBeforeRsvp) {
                Log.d(TAG, "Switching to text mode for RSVP (raw HTML source)")
                fullText = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(currentUri!!)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: "No content available"
                    } catch (e: Exception) {
                        "Error reading raw file: ${e.message}"
                    }
                }

                withContext(Dispatchers.Main) {
                    webView?.isVisible = false
                    textContent?.isVisible = true
                    scrollView?.isVisible = true
                    textContent?.text = fullText
                    Log.d(TAG, "Raw HTML loaded into text view (length ${fullText.length})")
                }

                delay(800)
            } else {
                fullText = textContent?.text?.toString() ?: "No readable text available"
            }

            val cleanText = cleanHtmlForReading(fullText)

            if (cleanText.trim().isEmpty()) {
                rsvpWordView?.text = "No readable text detected\n\nDocument may be mostly tables/images.\nTry scrolling or normal view."
                delay(5000)
                exitRsvp()
                return@launch
            }

            val words = cleanText.split(Regex("\\s+")).filter { it.isNotBlank() }
            val startIndex = (words.size * progress).toInt().coerceIn(0, words.size - 1)

            Log.d(TAG, "RSVP starting from word index $startIndex / ${words.size}")

            wordsBeforeRsvp = words
            currentWordIndex = startIndex

            rsvpJob = CoroutineScope(Dispatchers.Main).launch {
                words.drop(startIndex).forEachIndexed { index, word ->
                    if (!isActive) return@launch
                    rsvpWordView?.text = word
                    currentWordIndex = startIndex + index + 1
                    delay(60000L / currentWpm)
                }
                exitRsvp()
            }
        }
    }

    private fun exitRsvp() {
        isRsvpRunning = false
        rsvpJob?.cancel()
        rsvpOverlay?.isVisible = false
        rsvpWordView?.text = ""

        // Restore original view mode
        if (wasUsingWebViewBeforeRsvp) {
            Log.d(TAG, "Restoring WebView mode after RSVP")
            textContent?.isVisible = false
            scrollView?.isVisible = false
            webView?.isVisible = true

            // Update scroll position based on reading progress
            val words = wordsBeforeRsvp
            if (words.isNotEmpty() && currentWordIndex > 0) {
                val progress = currentWordIndex.toFloat() / words.size
                val totalHeight = webView?.contentHeight ?: 1000
                val targetScrollY = (progress * totalHeight).toInt().coerceIn(0, totalHeight)

                webView?.post {
                    webView?.scrollTo(0, targetScrollY)
                    Log.d(TAG, "Restored WebView scroll to ≈ $targetScrollY (progress $progress)")
                }
            }
        } else {
            Log.d(TAG, "Keeping text mode after RSVP - updating scroll")
            val words = wordsBeforeRsvp
            if (words.isNotEmpty() && currentWordIndex > 0) {
                val progress = currentWordIndex.toFloat() / words.size
                val totalHeight = textContent?.layout?.height ?: 1000
                val targetScrollY = (progress * totalHeight).toInt().coerceIn(0, totalHeight)

                scrollView?.post {
                    scrollView?.scrollTo(0, targetScrollY)
                    Log.d(TAG, "Restored text view scroll to ≈ $targetScrollY (progress $progress)")
                }
            }
        }

        // Reset tracking
        wordsBeforeRsvp = emptyList()
        currentWordIndex = 0
    }

    private fun cleanHtmlForReading(raw: String): String {
        var text = raw
            .replace(Regex("(?s)<script.*?</script>"), "")
            .replace(Regex("(?s)<style.*?</style>"), "")
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text
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
                try {
                    webView?.loadUrl(uri.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "WebView loadUrl failed", e)
                    fallbackToRawText(uri)
                }
                maxObservedScrollY = 0
            } else {
                fallbackToRawText(uri)
            }
        }
    }

    private fun fallbackToRawText(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: "Cannot open file"
        } catch (e: Exception) {
            Log.e(TAG, "Text fallback error", e)
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
        } ?: Log.w(TAG, "Cannot save height: currentUri is null")
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
        } ?: Log.w(TAG, "Cannot restore state: currentUri is null")
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
        } ?: Log.w(TAG, "onPause: currentUri is null - no position saved")
    }

    override fun onBackPressed() {
        if (isRsvpRunning) {
            exitRsvp()
        } else if (webView?.canGoBack() == true) {
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