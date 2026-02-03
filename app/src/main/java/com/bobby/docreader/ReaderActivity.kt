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
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
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

                    view?.evaluateJavascript(
                        """
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
                    """.trimIndent(), null
                    )

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
                    textContent?.text =
                        "HTML failed to load: ${error?.description ?: "Unknown"}\n\nRaw content:\n" + try {
                            contentResolver.openInputStream(currentUri!!)?.bufferedReader()
                                ?.use { it.readText() }
                        } catch (e: Exception) {
                            "Cannot read file"
                        }
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

        val isMht = fileNameLower.endsWith(".mht") || fileNameLower.endsWith(".mhtml")

        val hasHtmlExtension = fileNameLower.contains(".htm") || fileNameLower.contains(".html")
        val looksLikeHtml = contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(1024)
            val bytesRead = input.read(buffer)
            if (bytesRead > 50) {
                val start = String(buffer, 0, bytesRead, Charsets.UTF_8).trim().lowercase()
                start.startsWith("<!doctype html") ||
                        start.startsWith("<html")
            } else false
        } ?: false

        val useWebView =
            (hasHtmlExtension || looksLikeHtml || mimeType.startsWith("text/html")) && !isMht

        Log.d(
            TAG,
            "Loading file: $fileNameLower | mime: $mimeType | useWebView: $useWebView | isMht: $isMht"
        )

        withContext(Dispatchers.Main) {
            if (isMht) {
                val extractedHtml = extractHtmlFromMht(uri)
                if (extractedHtml != null) {
                    textContent?.isVisible = false
                    scrollView?.isVisible = false
                    webView?.isVisible = true
                    webView?.loadDataWithBaseURL(null, extractedHtml, "text/html", "UTF-8", null)
                } else {
                    textContent?.text =
                        "Could not extract HTML from .mht file.\n\nRaw content:\n" + try {
                            contentResolver.openInputStream(uri)?.bufferedReader()
                                ?.use { it.readText() }
                        } catch (e: Exception) {
                            "Cannot read"
                        }
                    textContent?.isVisible = true
                    webView?.isVisible = false
                }
            } else if (useWebView) {
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
                val visibleHeight = webView?.height ?: 0
                val currentScroll = webView?.scrollY ?: 0
                val currentMax = maxObservedScrollY.coerceAtLeast(currentScroll)

                val estimatedTotal = currentMax + visibleHeight + 800

                Log.d(
                    TAG,
                    "saveTotalHeightIfNeeded - currentScroll=$currentScroll, maxObserved=$currentMax, visibleHeight=$visibleHeight, estimatedTotal=$estimatedTotal"
                )

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

    private fun extractHtmlFromMht(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                // Reading as text to find the boundary
                val rawContent = inputStream.bufferedReader(Charsets.UTF_8).readText()

                val boundaryMatch = Regex("""boundary="([^"]+)"""").find(rawContent)
                val boundary = boundaryMatch?.groupValues?.get(1) ?: return@use null

                val parts = rawContent.split("--$boundary")

                for (part in parts) {
                    if (part.contains("Content-Type: text/html", ignoreCase = true)) {
                        val contentStart = part.indexOf("\r\n\r\n")
                        if (contentStart >= 0) {
                            val encodedHtml = part.substring(contentStart + 4).trim()

                            // MHT files almost always use quoted-printable encoding
                            return@use if (part.contains("quoted-printable", ignoreCase = true)) {
                                decodeQuotedPrintable(encodedHtml)
                            } else {
                                encodedHtml
                            }
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract .mht content", e)
            null
        }
    }

    private fun decodeQuotedPrintable(input: String): String {
        return input
            // 1. Remove "Soft Line Breaks" (an '=' at the very end of a line)
            .replace("=\r\n", "")
            .replace("=\n", "")
            // 2. Decode Hex characters (like =3D to = or =20 to space)
            .let { text ->
                val regex = Regex("=[0-9A-Fa-f]{2}")
                regex.replace(text) { match ->
                    val hex = match.value.substring(1)
                    hex.toInt(16).toChar().toString()
                }
            }
    }
}