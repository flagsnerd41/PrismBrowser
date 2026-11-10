package com.prism.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import eightbitlab.com.blurview.BlurView
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
    private lateinit var toolbarBlur: FrameLayout
    private lateinit var header: LinearLayout
    private val fadeBlurLayers = mutableListOf<BlurView>()
    private lateinit var toolbarParams: FrameLayout.LayoutParams
    private val prefs by lazy { getSharedPreferences("prism_browser", Context.MODE_PRIVATE) }
    private val ink = Color.rgb(20, 21, 27)
    private val pillColor = Color.rgb(30, 32, 40)
    private val muted = Color.rgb(157, 160, 175)
    private val accent = Color.rgb(190, 174, 255)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        buildInterface()
        configureWebView()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState) else loadHome()
    }

    private fun buildInterface() {
        val root = FrameLayout(this).apply { setBackgroundColor(ink) }

        webView = WebView(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        // True fade blur: many thin strips, each with progressively less blur.
        // This fades the blur strength itself instead of drawing a blurred rectangle.
        addFadeBlur(root, Gravity.TOP, 18, 1)

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(18), dp(8))
        }
        val brand = TextView(this).apply {
            text = "prism"
            textSize = 23f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val dot = TextView(this).apply {
            text = " •"
            textSize = 18f
            setTextColor(accent)
        }
        header.addView(brand)
        header.addView(dot)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val tabs = makeButton("▢", Color.TRANSPARENT) {
            Toast.makeText(this, "Tabs are coming in the next update", Toast.LENGTH_SHORT).show()
        }
        header.addView(tabs, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(header, FrameLayout.LayoutParams(-1, dp(58), Gravity.TOP))

        progressTrack = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        progressFill = View(this).apply {
            setBackgroundColor(accent)
            visibility = View.GONE
        }
        val progressFrame = FrameLayout(this)
        progressFrame.addView(progressTrack, FrameLayout.LayoutParams(-1, dp(2)))
        progressFrame.addView(progressFill, FrameLayout.LayoutParams(0, dp(2)))
        val progressParams = FrameLayout.LayoutParams(-1, dp(2), Gravity.TOP).apply {
            topMargin = dp(58)
        }
        root.addView(progressFrame, progressParams)

        // A single frosted-glass surface: BlurView samples the live webpage behind it.
        toolbarBlur = FrameLayout(this).apply {
            // Solid floating pill: the bottom edge stays clear, with no backdrop blur.
            background = rounded(Color.argb(238, 35, 37, 46), 36)
            clipToOutline = true
            elevation = dp(14).toFloat()
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = null
        }

        backButton = makeButton("‹", Color.TRANSPARENT) {
            if (webView.canGoBack()) webView.goBack()
        }
        forwardButton = makeButton("›", Color.TRANSPARENT) {
            if (webView.canGoForward()) webView.goForward()
        }
        addressBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            hint = "Search or enter address"
            setPadding(dp(8), 0, dp(8), 0)
            background = null
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
        val bookmark = makeButton("☆", Color.TRANSPARENT) { saveBookmark() }
        val reload = makeButton("↻", Color.TRANSPARENT) { webView.reload() }

        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(34), dp(42)))
        toolbar.addView(forwardButton, LinearLayout.LayoutParams(dp(30), dp(42)))
        toolbar.addView(addressBar, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolbar.addView(bookmark, LinearLayout.LayoutParams(dp(36), dp(42)))
        toolbar.addView(reload, LinearLayout.LayoutParams(dp(36), dp(42)))

        toolbarParams = FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(22)
        }
        toolbarBlur.addView(toolbar, FrameLayout.LayoutParams(-1, -1))
        root.addView(toolbarBlur, toolbarParams)

        setContentView(root)

        fadeBlurLayers.forEach { blur ->
            // One continuous BlurView gives a real, even backdrop blur without strip banding.
            blur.setupWith(root)
                .setFrameClearDrawable(root.background)
                .setBlurRadius(8f)
                .setOverlayColor(Color.TRANSPARENT)
        }

        // Extend the fades behind system bars while keeping controls clear of them.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val statusInset = insets.systemWindowInsetTop
            val navInset = insets.systemWindowInsetBottom
            (header.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = statusInset
            }.also { header.layoutParams = it }
            toolbarParams.bottomMargin = navInset + dp(14)
            toolbarBlur.layoutParams = toolbarParams
            insets
        }
        root.requestApplyInsets()
        updateNavigationButtons()
    }

    private fun addFadeBlur(
        root: FrameLayout,
        gravity: Int,
        totalHeightDp: Int,
        stripCount: Int
    ) {
        // A single full-width BlurView avoids the visible horizontal bands caused by strips.
        val blur = FadeBlurView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }
        root.addView(
            blur,
            FrameLayout.LayoutParams(-1, dp(totalHeightDp), gravity)
        )
        fadeBlurLayers.add(blur)
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
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(color, 24)
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

private class FadeBlurView(context: Context) : BlurView(context) {
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    override fun draw(canvas: Canvas) {
        if (width == 0 || height == 0) {
            super.draw(canvas)
            return
        }
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.draw(canvas)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(0f, 0.18f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        maskPaint.shader = null
        canvas.restoreToCount(checkpoint)
    }
}
