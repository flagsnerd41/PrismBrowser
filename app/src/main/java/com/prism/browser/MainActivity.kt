package com.prism.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private val prefs by lazy { getSharedPreferences("prism_browser", Context.MODE_PRIVATE) }
    private val ink = Color.rgb(20, 21, 27)
    private val panel = Color.rgb(34, 36, 45)
    private val muted = Color.rgb(157, 160, 175)
    private val accent = Color.rgb(190, 174, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = ink
        window.navigationBarColor = ink
        buildInterface()
        configureWebView()
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadHome()
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        val brand = TextView(this).apply {
            text = "prism"
            textSize = 23f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val brandDot = TextView(this).apply {
            text = "  •"
            textSize = 18f
            setTextColor(accent)
        }
        header.addView(brand)
        header.addView(brandDot)
        val spacer = View(this)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        val tabs = makeButton("▢", panel) {
            Toast.makeText(this, "Tabs are coming in the next update", Toast.LENGTH_SHORT).show()
        }
        header.addView(tabs, LinearLayout.LayoutParams(dp(42), dp(38)))
        root.addView(header)

        progressTrack = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        progressFill = View(this).apply {
            setBackgroundColor(accent)
            visibility = View.GONE
        }
        val progressFrame = FrameLayout(this)
        progressFrame.addView(progressTrack, FrameLayout.LayoutParams(-1, dp(2)))
        progressFrame.addView(progressFill, FrameLayout.LayoutParams(0, dp(2)))
        root.addView(progressFrame)

        webView = WebView(this).apply { setBackgroundColor(ink) }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(10))
            setBackgroundColor(ink)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 30)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }

        backButton = makeButton("‹", panel) { if (webView.canGoBack()) webView.goBack() }
        forwardButton = makeButton("›", panel) { if (webView.canGoForward()) webView.goForward() }
        addressBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            hint = "Search or enter address"
            setPadding(dp(12), 0, dp(8), 0)
            background = rounded(Color.rgb(48, 50, 61), 22)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                    navigate(addressBar.text.toString())
                    true
                } else false
            }
        }
        val bookmark = makeButton("☆", panel) { saveBookmark() }
        val reload = makeButton("↻", panel) { webView.reload() }

        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(36), dp(40)))
        toolbar.addView(forwardButton, LinearLayout.LayoutParams(dp(32), dp(40)))
        toolbar.addView(addressBar, LinearLayout.LayoutParams(0, dp(40), 1f))
        toolbar.addView(bookmark, LinearLayout.LayoutParams(dp(36), dp(40)).apply { leftMargin = dp(5) })
        toolbar.addView(reload, LinearLayout.LayoutParams(dp(36), dp(40)))
        bottomArea.addView(toolbar)

        val caption = TextView(this).apply {
            text = "PRIVATE BY DESIGN  ·  PRISM BROWSER"
            textSize = 9f
            letterSpacing = 1.3f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(9), 0, 0)
        }
        bottomArea.addView(caption)
        root.addView(bottomArea)
        setContentView(root)
        updateNavigationButtons()
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return uri.scheme !in listOf("http", "https", "about")
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                addressBar.setText(url.orEmpty())
                progressFill.visibility = View.VISIBLE
                progressFill.layoutParams = progressFill.layoutParams.apply { width = 0 }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                addressBar.setText(url.orEmpty())
                progressFill.visibility = View.GONE
                updateNavigationButtons()
                prefs.edit().putString("last_page", url).apply()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val parentWidth = (progressTrack.parent as? View)?.width ?: 0
                progressFill.layoutParams = progressFill.layoutParams.apply {
                    width = (parentWidth * newProgress / 100f).toInt()
                }
                progressFill.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .setTitle(filename)
                    .setDescription("Downloading with PrismBrowser")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't start download", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val destination = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(" ") || !input.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(input)
            else -> "https://$input"
        }
        webView.loadUrl(destination)
        addressBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun loadHome() {
        addressBar.setText("")
        webView.loadUrl("https://www.google.com")
    }

    private fun saveBookmark() {
        val url = webView.url ?: return
        val title = webView.title?.takeIf { it.isNotBlank() } ?: url
        val saved = prefs.getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        saved.add(title + "|" + url)
        prefs.edit().putStringSet("bookmarks", saved).apply()
        Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
    }

    private fun updateNavigationButtons() {
        if (!::backButton.isInitialized) return
        backButton.alpha = if (webView.canGoBack()) 1f else 0.45f
        forwardButton.alpha = if (webView.canGoForward()) 1f else 0.45f
    }

    private fun makeButton(label: String, color: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(color, 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
