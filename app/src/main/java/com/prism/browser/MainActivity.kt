package com.prism.browser

import android.app.Activity
import android.app.AlertDialog
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
import eightbitlab.com.blurview.BlurTarget
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
    private lateinit var blurTarget: BlurTarget
    private lateinit var addressBar: EditText
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var backButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var toolbarBlur: BlurView
    private lateinit var header: LinearLayout
    private val fadeBlurLayers = mutableListOf<BlurView>()
    private val tabs = mutableListOf<String?>(null)
    private var currentTab = 0
    private var desktopMode = false
    private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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

        // BlurTarget records the webpage once through BlurView's hardware RenderNode pipeline.
        blurTarget = BlurTarget(this)
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        blurTarget.addView(webView, FrameLayout.LayoutParams(-1, -1))
        root.addView(blurTarget, FrameLayout.LayoutParams(-1, -1))

        // True fade blur: many thin strips, each with progressively less blur.
        // This fades the blur strength itself instead of drawing a blurred rectangle.
        addFadeBlur(root, Gravity.TOP, 40, 1)

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
        val tabsButton = makeButton("▢", Color.TRANSPARENT) { showTabs() }
        header.addView(tabsButton, LinearLayout.LayoutParams(dp(42), dp(42)))
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

        // Safari-inspired split floating toolbar: navigation, address, and reload are separate pills.
        val navBlur = BlurView(this).apply {
            background = rounded(Color.argb(48, 35, 37, 46), 30)
            clipToOutline = true
            elevation = dp(14).toFloat()
        }
        toolbarBlur = BlurView(this).apply {
            background = rounded(Color.argb(48, 35, 37, 46), 30)
            clipToOutline = true
            elevation = dp(14).toFloat()
        }
        val reloadBlur = BlurView(this).apply {
            background = rounded(Color.argb(48, 35, 37, 46), 30)
            clipToOutline = true
            elevation = dp(14).toFloat()
        }

        val navGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(4), dp(3), dp(4))
        }
        backButton = makeButton("‹", Color.TRANSPARENT) {
            if (webView.canGoBack()) webView.goBack()
        }
        forwardButton = makeButton("›", Color.TRANSPARENT) {
            if (webView.canGoForward()) webView.goForward()
        }
        navGroup.addView(backButton, LinearLayout.LayoutParams(dp(38), dp(42)))
        navGroup.addView(forwardButton, LinearLayout.LayoutParams(dp(34), dp(42)))
        navBlur.addView(navGroup, FrameLayout.LayoutParams(-1, -1))

        val addressGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(4), dp(5), dp(4))
        }
        addressBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            hint = "Search or enter address"
            setPadding(dp(8), 0, dp(4), 0)
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
        addressGroup.addView(addressBar, LinearLayout.LayoutParams(0, dp(42), 1f))
        addressGroup.addView(bookmark, LinearLayout.LayoutParams(dp(38), dp(42)))
        toolbarBlur.addView(addressGroup, FrameLayout.LayoutParams(-1, -1))

        val reload = makeButton("↻", Color.TRANSPARENT) { webView.reload() }
        reloadBlur.addView(reload, FrameLayout.LayoutParams(-1, -1))

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolbar.addView(navBlur, LinearLayout.LayoutParams(dp(80), dp(54)))
        toolbar.addView(toolbarBlur, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            leftMargin = dp(8)
            rightMargin = dp(8)
        })
        toolbar.addView(reloadBlur, LinearLayout.LayoutParams(dp(54), dp(54)))

        toolbarParams = FrameLayout.LayoutParams(-1, dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(22)
        }
        root.addView(toolbar, toolbarParams)

        setContentView(root)

        fadeBlurLayers.forEach { blur ->
            // One continuous BlurView gives a real, even backdrop blur without strip banding.
            blur.setupWith(blurTarget)
                .setFrameClearDrawable(root.background)
                .setBlurRadius(6f)
                .setOverlayColor(Color.TRANSPARENT)
        }

        listOf(navBlur, toolbarBlur, reloadBlur).forEach { pill ->
            pill.setupWith(blurTarget)
                .setFrameClearDrawable(root.background)
                .setBlurRadius(8f)
                .setOverlayColor(Color.argb(18, 35, 37, 46))
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
                if (url != null && !url.startsWith("https://prism.local/")) {
                    tabs[currentTab] = url
                    prefs.edit().putString("last_page", url).apply()
                }
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
        val html = """
            <!doctype html>
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <link rel="preconnect" href="https://fonts.googleapis.com">
            <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
            <link href="https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=Space+Grotesk:wght@500;600;700&display=swap" rel="stylesheet">
            <style>
            *{box-sizing:border-box}html,body{margin:0;min-height:100%;font-family:'DM Sans',sans-serif;color:#fff}
            body{min-height:100vh;background:#11131b;background-size:cover;background-position:center;background-attachment:fixed;transition:background-image .35s ease}
            body:before{content:'';position:fixed;inset:0;background:linear-gradient(180deg,rgba(8,10,17,.34),rgba(8,10,17,.56) 42%,rgba(8,10,17,.88));z-index:0}
            .page{position:relative;z-index:1;max-width:680px;margin:auto;padding:15vh 25px 44px}
            .topline{display:flex;align-items:center;justify-content:space-between;margin-bottom:34px}
            .brand{font:700 32px 'Space Grotesk',sans-serif;letter-spacing:-1.8px;text-shadow:0 2px 18px #0006}.brand span{color:#c7b7ff}
            .date{font-size:12px;color:#ffffffb8;letter-spacing:.4px}
            .greeting{font:600 clamp(27px,7vw,39px) 'Space Grotesk',sans-serif;letter-spacing:-1.3px;margin:0 0 7px}
            .sub{margin:0 0 25px;color:#ffffffb8;font-size:14px}
            .search{display:flex;align-items:center;gap:12px;padding:0 17px;height:58px;border:1px solid #ffffff38;border-radius:19px;background:#ffffff1c;backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);box-shadow:0 12px 36px #0002}
            .search svg{width:19px;height:19px;opacity:.8;flex:none}.search input{width:100%;border:0;outline:0;background:transparent;color:white;font:500 15px 'DM Sans',sans-serif}.search input::placeholder{color:#ffffffa8}
            .section{display:flex;align-items:center;justify-content:space-between;margin:31px 2px 13px}.section strong{font:600 14px 'Space Grotesk',sans-serif;letter-spacing:.2px}.section span{font-size:11px;color:#ffffffa0}
            .sites{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}
            .site{min-height:91px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:9px;text-decoration:none;color:#fff;border:1px solid #ffffff24;border-radius:18px;background:#ffffff17;backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);transition:transform .18s,background .18s}
            .site:active{transform:scale(.96);background:#ffffff30}.site img{width:27px;height:27px;border-radius:8px;background:#fff;padding:3px;object-fit:contain}.site span{font-size:11px;font-weight:600}
            .wallpapers{display:flex;gap:10px;overflow-x:auto;padding:2px 1px 8px;scrollbar-width:none}.wallpapers::-webkit-scrollbar{display:none}
            .wall{flex:none;width:88px;height:58px;border:2px solid #ffffff50;border-radius:14px;background-size:cover;background-position:center;position:relative;overflow:hidden}.wall.active{border-color:#d3c5ff;box-shadow:0 0 0 2px #bdaaff55}.wall:after{content:attr(data-name);position:absolute;inset:auto 0 0;padding:13px 5px 5px;text-align:center;font-size:10px;font-weight:600;background:linear-gradient(transparent,#0009)}
            .foot{margin-top:28px;text-align:center;color:#ffffff80;font-size:11px;letter-spacing:.3px}
            @media(min-width:600px){.page{padding-top:12vh}.sites{gap:13px}.site{min-height:105px}.site span{font-size:12px}}
            </style></head>
            <body><main class="page">
            <div class="topline"><div class="brand">prism<span>.</span></div><div class="date" id="clock">YOUR SPACE</div></div>
            <h1 class="greeting">A little space to explore.</h1><p class="sub">Search the web, pick up where you left off.</p>
            <form class="search" action="https://www.google.com/search" method="get">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="11" cy="11" r="7.2"/><path d="m16.5 16.5 4 4"/></svg>
            <input name="q" autocomplete="off" placeholder="Search anything..." aria-label="Search the web"></form>
            <div class="section"><strong>Quick access</strong><span>YOUR FAVOURITES</span></div>
            <div class="sites">
            <a class="site" href="https://www.google.com"><img src="https://www.google.com/s2/favicons?domain=google.com&sz=64"><span>Google</span></a>
            <a class="site" href="https://www.youtube.com"><img src="https://www.google.com/s2/favicons?domain=youtube.com&sz=64"><span>YouTube</span></a>
            <a class="site" href="https://www.github.com"><img src="https://www.google.com/s2/favicons?domain=github.com&sz=64"><span>GitHub</span></a>
            <a class="site" href="https://www.wikipedia.org"><img src="https://www.google.com/s2/favicons?domain=wikipedia.org&sz=64"><span>Wikipedia</span></a>
            <a class="site" href="https://www.reddit.com"><img src="https://www.google.com/s2/favicons?domain=reddit.com&sz=64"><span>Reddit</span></a>
            <a class="site" href="https://www.instagram.com"><img src="https://www.google.com/s2/favicons?domain=instagram.com&sz=64"><span>Instagram</span></a>
            <a class="site" href="https://www.chatgpt.com"><img src="https://www.google.com/s2/favicons?domain=chatgpt.com&sz=64"><span>ChatGPT</span></a>
            <a class="site" href="https://www.x.com"><img src="https://www.google.com/s2/favicons?domain=x.com&sz=64"><span>X</span></a>
            </div>
            <div class="section"><strong>Wallpapers</strong><span>TAP TO CHANGE</span></div>
            <div class="wallpapers" id="wallpapers">
            <button class="wall active" data-name="Alpine" data-bg="https://images.unsplash.com/photo-1470770841072-f978cf4d019e?auto=format&fit=crop&w=1400&q=85" style="background-image:url('https://images.unsplash.com/photo-1470770841072-f978cf4d019e?auto=format&fit=crop&w=240&q=65')"></button>
            <button class="wall" data-name="Golden" data-bg="https://images.unsplash.com/photo-1470252649378-9c29740c9fa8?auto=format&fit=crop&w=1400&q=85" style="background-image:url('https://images.unsplash.com/photo-1470252649378-9c29740c9fa8?auto=format&fit=crop&w=240&q=65')"></button>
            <button class="wall" data-name="Coast" data-bg="https://images.unsplash.com/photo-1500375592092-40eb2168fd21?auto=format&fit=crop&w=1400&q=85" style="background-image:url('https://images.unsplash.com/photo-1500375592092-40eb2168fd21?auto=format&fit=crop&w=240&q=65')"></button>
            <button class="wall" data-name="Forest" data-bg="https://images.unsplash.com/photo-1448375240586-882707db888b?auto=format&fit=crop&w=1400&q=85" style="background-image:url('https://images.unsplash.com/photo-1448375240586-882707db888b?auto=format&fit=crop&w=240&q=65')"></button>
            <button class="wall" data-name="Night" data-bg="https://images.unsplash.com/photo-1519681393784-d120267933ba?auto=format&fit=crop&w=1400&q=85" style="background-image:url('https://images.unsplash.com/photo-1519681393784-d120267933ba?auto=format&fit=crop&w=240&q=65')"></button>
            </div><div class="foot">MADE FOR THE WAY YOU BROWSE</div>
            </main><script>
            const walls=[...document.querySelectorAll('.wall')];
            function setWall(button){document.body.style.backgroundImage='url("'+button.dataset.bg+'")';walls.forEach(w=>w.classList.toggle('active',w===button));try{localStorage.setItem('prism-wallpaper',button.dataset.name)}catch(e){}}
            walls.forEach(w=>w.addEventListener('click',()=>setWall(w)));
            try{const saved=localStorage.getItem('prism-wallpaper');const chosen=walls.find(w=>w.dataset.name===saved);setWall(chosen||walls[0])}catch(e){setWall(walls[0])}
            const clock=document.getElementById('clock');const updateClock=()=>{const d=new Date();clock.textContent=d.toLocaleDateString(undefined,{weekday:'short',month:'short',day:'numeric'}).toUpperCase()};updateClock();
            </script></body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL("https://prism.local/", html, "text/html", "UTF-8", null)
    }
    private fun showTabs() {
        val labels = mutableListOf("＋  New tab", "Desktop site: " + if (desktopMode) "On ✓" else "Off")
        tabs.forEachIndexed { index, url ->
            val title = if (url.isNullOrBlank()) "Start page" else Uri.parse(url).host ?: url
            labels.add((if (index == currentTab) "● " else "") + (index + 1) + ". " + title)
        }
        AlertDialog.Builder(this).setTitle("Tabs · ${tabs.size}")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> { tabs.add(null); currentTab = tabs.lastIndex; loadHome() }
                    1 -> toggleDesktopMode()
                    else -> {
                        currentTab = which - 2
                        val url = tabs[currentTab]
                        if (url.isNullOrBlank()) loadHome() else webView.loadUrl(url)
                    }
                }
            }
            .setNeutralButton("Close current tab") { _, _ ->
                if (tabs.size > 1) { tabs.removeAt(currentTab); currentTab = (currentTab - 1).coerceAtLeast(0) }
                else tabs[0] = null
                val url = tabs[currentTab]; if (url.isNullOrBlank()) loadHome() else webView.loadUrl(url)
            }.setNegativeButton("Done", null).show()
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        prefs.edit().putBoolean("desktop_mode", desktopMode).apply()
        webView.settings.apply {
            userAgentString = if (desktopMode) desktopUserAgent else android.webkit.WebSettings.getDefaultUserAgent(this@MainActivity)
            useWideViewPort = desktopMode
            loadWithOverviewMode = desktopMode
        }
        Toast.makeText(this, if (desktopMode) "Desktop site enabled" else "Mobile site enabled", Toast.LENGTH_SHORT).show()
        val currentUrl = webView.url
        if (currentUrl.isNullOrBlank() || currentUrl.startsWith("https://prism.local/")) loadHome()
        else webView.reload()
    }

    private fun showBookmarks() {
        val saved = prefs.getStringSet("bookmarks", emptySet())?.toList()?.sorted() ?: emptyList()
        if (saved.isEmpty()) { Toast.makeText(this, "No bookmarks yet", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(saved.map { it.substringBefore("|") }.toTypedArray()) { _, which -> navigate(saved[which].substringAfter("|", "")) }
            .setNegativeButton("Close", null).show()
    }

    private fun saveBookmark() {
        val url = webView.url ?: return
        val title = webView.title?.takeIf { it.isNotBlank() } ?: url
        val saved = prefs.getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
        saved.add(title + "|" + url)
        prefs.edit().putStringSet("bookmarks", saved).apply()
        Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
        showBookmarks()
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
