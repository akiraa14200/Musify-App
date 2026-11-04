package com.streamgurd.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.*
import android.widget.*
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private data class SiteEntry(val name: String, val url: String, val category: String)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val catalogueRefresh = object : Runnable {
        override fun run() {
            refreshCatalogue(false)
            mainHandler.postDelayed(this, 15 * 60 * 1000L)
        }
    }
    private val animeCatalogueCsvUrl = "https://docs.google.com/spreadsheets/d/1VLCJE_YxSQlPbjQojaFTy1ZzrmpitetsAXDgTUpbHzs/export?format=csv"
    private val mangaCatalogueCsvUrl = "https://docs.google.com/spreadsheets/d/1VLCJE_YxSQlPbjQojaFTy1ZzrmpitetsAXDgTUpbHzs/gviz/tq?tqx=out:csv&sheet=Sheet2"
    private lateinit var root: FrameLayout
    private lateinit var contentLayout: LinearLayout
    private lateinit var peekButton: TextView
    private var chromeVisible = true
    private lateinit var web: WebView
    private lateinit var url: EditText
    private lateinit var status: TextView
    private lateinit var homePanel: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var addressBar: LinearLayout
    private lateinit var actionRow: LinearLayout
    private lateinit var statusRow: LinearLayout
    private lateinit var browserBar: LinearLayout

    private var blocking = true
    private var host = ""
    private var trustedHost = ""
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private val prefs by lazy { getSharedPreferences("streamgurd", MODE_PRIVATE) }
    private val blocked = setOf(
        "doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com",
        "adnxs.com","adsrvr.org","advertising.com","adform.net","adroll.com","criteo.com",
        "taboola.com","outbrain.com","popads.net","popcash.net","propellerads.com","exoclick.com",
        "adsterra.com","yllix.com","trafficjunky.net","mgid.com","revcontent.com","zedo.com",
        "smartadserver.com","rubiconproject.com","openx.net","pubmatic.com","casalemedia.com",
        "lijit.com","sharethrough.com","bidvertiser.com","hilltopads.net","onclickads.net",
        "pushengage.com","pushwoosh.com","adpushup.com","adskeeper.co.uk","ad-maven.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blocking = prefs.getBoolean("blocking", true)
        window.statusBarColor = Color.rgb(9,13,24)
        window.navigationBarColor = Color.rgb(9,13,24)
        buildUi()
        configureWeb()
        prefs.getString("last_url", null)?.let { open(it) }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun buildUi() {
        val bg = Color.rgb(8, 12, 23)
        val surface = Color.rgb(23, 33, 53)
        val muted = Color.rgb(145, 161, 190)
        val accent = Color.rgb(171, 198, 255)

        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(4))
            setBackgroundColor(bg)
        }
        root.addView(contentLayout, FrameLayout.LayoutParams(-1, -1))

        headerBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, dp(2), dp(3)) }
        val brandMark = TextView(this).apply {
            text = "S"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Color.rgb(9, 15, 29))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(190, 211, 255), Color.rgb(115, 151, 220))).apply { cornerRadius = dp(11).toFloat() }
        }
        headerBar.addView(brandMark, LinearLayout.LayoutParams(dp(34), dp(34)))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        brandText.addView(text("StreamGurd", 16f, Color.WHITE, true))
        brandText.addView(text("PRIVATE BROWSING", 8f, muted, true))
        headerBar.addView(brandText)
        headerBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        lateinit var shield: TextView
        shield = actionButton(if (blocking) "盾 Protected" else "盾 Paused") {
            blocking = !blocking
            prefs.edit().putBoolean("blocking", blocking).apply()
            shield.text = if (blocking) "盾 Protected" else "盾 Paused"
            web.reload()
        }
        shield.textSize = 9f
        headerBar.addView(shield, LinearLayout.LayoutParams(dp(92), dp(32)).apply { rightMargin = dp(6) })
        val eye = actionButton("◉") { toggleBrowserChrome() }.apply { textSize = 17f }
        headerBar.addView(eye, LinearLayout.LayoutParams(dp(36), dp(32)))
        contentLayout.addView(headerBar, LinearLayout.LayoutParams(-1, dp(38)))

        addressBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), 0, dp(4), 0)
            background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(13).toFloat(); setStroke(dp(1), Color.rgb(42, 56, 82)) }
        }
        addressBar.addView(text("⌕", 20f, accent, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(26), dp(40)))
        url = EditText(this).apply {
            hint = "Search or enter website"; setHintTextColor(Color.rgb(112, 128, 154)); setTextColor(Color.WHITE)
            textSize = 12f; setSingleLine(true); setPadding(dp(3), 0, dp(3), 0); setBackgroundColor(Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> open(text.toString()); true }
        }
        addressBar.addView(url, LinearLayout.LayoutParams(0, dp(42), 1f))
        addressBar.addView(actionButton("GO", true) { open(url.text.toString()) }, LinearLayout.LayoutParams(dp(46), dp(34)))
        contentLayout.addView(addressBar, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(3) })

        actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(2)) }
        actionRow.addView(actionButton("＋ Add") { addLink() }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(3) })
        actionRow.addView(actionButton("☆ Saved") { showSaved() }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        actionRow.addView(actionButton("☷ Sites") { showCatalogue() }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        actionRow.addView(actionButton("⋯ Menu") { menu() }, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(3) })
        contentLayout.addView(actionRow, LinearLayout.LayoutParams(-1, dp(38)))

        statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), 0, dp(3), dp(2)) }
        val statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(104, 215, 163)) } }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(6), dp(6)))
        status = text("  READY · Protection active", 9f, muted)
        statusRow.addView(status)
        contentLayout.addView(statusRow, LinearLayout.LayoutParams(-1, dp(17)))

        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(24, 37, 61), Color.rgb(14, 21, 36))).apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.rgb(42, 59, 88)) }
        }
        val heroIcon = TextView(this).apply { text = "◈"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(accent); background = GradientDrawable().apply { setColor(Color.rgb(35, 53, 83)); cornerRadius = dp(20).toFloat() } }
        homePanel.addView(heroIcon, LinearLayout.LayoutParams(dp(62), dp(62)).apply { gravity = Gravity.CENTER; bottomMargin = dp(14) })
        homePanel.addView(text("Browse with more control.", 21f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        homePanel.addView(text("Your browser, your choice. StreamGurd blocks known ad and tracker domains and helps stop unwanted site redirects.", 12f, Color.rgb(164, 180, 207)).apply { gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(4), dp(9), dp(4), dp(16)) })
        val featureRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        featureRow.addView(featureChip("◉ Tracker filter"), LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        featureRow.addView(featureChip("↗ Redirect guard"), LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4) })
        homePanel.addView(featureRow)
        homePanel.addView(actionButton("＋ OPEN A WEBSITE", true) { addLink() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(18) })
        contentLayout.addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))

        web = WebView(this)
        contentLayout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        browserBar = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0); background = shape(Color.rgb(12, 19, 32), 12) }
        browserBar.addView(actionButton("‹ Back") { if (web.canGoBack()) web.goBack() }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(3) })
        browserBar.addView(actionButton("Forward ›") { if (web.canGoForward()) web.goForward() }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        browserBar.addView(actionButton("↻ Reload") { web.reload() }, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(3) })
        contentLayout.addView(browserBar, LinearLayout.LayoutParams(-1, dp(38)))

        peekButton = actionButton("◉") { toggleBrowserChrome() }.apply { textSize = 18f; visibility = View.GONE; elevation = dp(8).toFloat() }
        root.addView(peekButton, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(10); bottomMargin = dp(10) })
        setContentView(root)
        web.visibility = View.GONE
        browserBar.visibility = View.GONE
        showHome()
    }

    private fun toggleBrowserChrome() { setBrowserChromeVisible(!chromeVisible) }

    private fun setBrowserChromeVisible(visible: Boolean) {
        chromeVisible = visible
        val v = if (visible) View.VISIBLE else View.GONE
        headerBar.visibility = v; addressBar.visibility = v; actionRow.visibility = v; statusRow.visibility = v
        browserBar.visibility = if (visible && web.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        peekButton.visibility = if (visible) View.GONE else View.VISIBLE
    }
    private fun cachedCatalogue(category: String): List<SiteEntry> {
        val key = if (category == "Manga") "catalogue_manga_csv" else "catalogue_anime_csv"
        val csv = prefs.getString(key, "").orEmpty()
        return if (csv.isBlank()) emptyList() else parseCatalogue(csv, category)
    }

    private fun downloadCatalogueCsv(endpoint: String): String {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 12000; readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "StreamGurd Android")
                setRequestProperty("Accept", "text/csv,text/plain,*/*")
            }
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection?.disconnect() }
    }

    private fun refreshCatalogue(showMessage: Boolean) {
        Thread {
            var anime = 0
            var manga = 0
            try {
                val csv = downloadCatalogueCsv(animeCatalogueCsvUrl)
                val rows = parseCatalogue(csv, "Anime")
                if (rows.isNotEmpty()) {
                    prefs.edit().putString("catalogue_anime_csv", csv).putLong("catalogue_anime_updated_at", System.currentTimeMillis()).apply()
                    anime = rows.size
                }
            } catch (_: Exception) { }
            try {
                val csv = downloadCatalogueCsv(mangaCatalogueCsvUrl)
                val rows = parseCatalogue(csv, "Manga")
                if (rows.isNotEmpty()) {
                    prefs.edit().putString("catalogue_manga_csv", csv).putLong("catalogue_manga_updated_at", System.currentTimeMillis()).apply()
                    manga = rows.size
                }
            } catch (_: Exception) { }
            runOnUiThread {
                if (showMessage) {
                    if (anime + manga > 0) toast("Catalogue refreshed · Anime: $anime · Manga: $manga")
                    else toast("Refresh failed. Showing saved catalogue.")
                }
            }
        }.start()
    }

    private fun parseCatalogue(csv: String, category: String): List<SiteEntry> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < csv.length) {
            val ch = csv[i]
            when {
                ch == '"' && quoted && i + 1 < csv.length && csv[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { row.add(cell.toString().trim()); cell.setLength(0) }
                (ch == '\n' || ch == '\r') && !quoted -> {
                    row.add(cell.toString().trim()); cell.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row.toList())
                    row.clear()
                    if (ch == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++
                }
                else -> cell.append(ch)
            }
            i++
        }
        row.add(cell.toString().trim())
        if (row.any { it.isNotBlank() }) rows.add(row.toList())
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().removePrefix("\uFEFF").lowercase(Locale.ROOT) }
        val nameIndex = header.indexOfFirst { it == "site name" || it == "sitename" || it == "name" }
        val urlIndex = header.indexOfFirst { it == "site url" || it == "siteurl" || it == "url" }
        if (nameIndex < 0 || urlIndex < 0) return emptyList()
        return rows.drop(1).mapNotNull { cells ->
            val name = cells.getOrNull(nameIndex)?.trim().orEmpty()
            val siteUrl = normalized(cells.getOrNull(urlIndex).orEmpty()) ?: return@mapNotNull null
            if (name.isBlank()) null else SiteEntry(name, siteUrl, category)
        }.distinctBy { it.url }
    }

    private fun showCatalogue() {
        var category = "Anime"
        var entries = cachedCatalogue(category)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)) }
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val animeTab = actionButton("Anime", true) {}
        val mangaTab = actionButton("Manga") {}
        tabs.addView(animeTab, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        tabs.addView(mangaTab, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(4) })
        val search = EditText(this).apply {
            hint = "Search sites"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            background = shape(Color.rgb(35,45,64),12); setPadding(dp(12),0,dp(12),0)
        }
        val info = text("",11f,Color.LTGRAY).apply { setPadding(dp(2),dp(8),dp(2),dp(8)) }
        val list = ListView(this)
        val filtered = entries.toMutableList()
        val adapter = ArrayAdapter(this,android.R.layout.simple_list_item_1,filtered.map { it.name }.toMutableList())
        list.adapter = adapter
        box.addView(tabs,LinearLayout.LayoutParams(-1,dp(40)))
        box.addView(search,LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(6) })
        box.addView(info)
        box.addView(list,LinearLayout.LayoutParams(-1,dp(330)))
        fun render(which: String) {
            category=which
            entries=cachedCatalogue(which)
            val q=search.text.toString().trim()
            val matches=entries.filter { it.name.contains(q,true)||it.url.contains(q,true) }
            filtered.clear(); filtered.addAll(matches)
            adapter.clear(); adapter.addAll(matches.map { it.name })
            animeTab.background=shape(if(which=="Anime") Color.rgb(174,199,246) else Color.rgb(22,32,51),14)
            mangaTab.background=shape(if(which=="Manga") Color.rgb(174,199,246) else Color.rgb(22,32,51),14)
            animeTab.setTextColor(if(which=="Anime") Color.rgb(10,17,31) else Color.rgb(215,226,245))
            mangaTab.setTextColor(if(which=="Manga") Color.rgb(10,17,31) else Color.rgb(215,226,245))
            info.text=if(entries.isEmpty()) "$which sheet is empty or unavailable" else "${matches.size} of ${entries.size} $which sites"
        }
        animeTab.setOnClickListener { render("Anime") }
        mangaTab.setOnClickListener { render("Manga") }
        val dialog=AlertDialog.Builder(this).setTitle("Site Catalogue").setView(box).setNegativeButton("Close",null).setPositiveButton("Refresh",null).create()
        list.setOnItemClickListener { _,_,position,_ ->
            val item=filtered.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss(); open(item.url)
        }
        search.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { render(category) }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                refreshCatalogue(true); toast("Refreshing Anime and Manga sheets…")
            }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(120,160,230))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.LTGRAY)
        render("Anime")
        if(cachedCatalogue("Anime").isEmpty() || cachedCatalogue("Manga").isEmpty()) refreshCatalogue(false)
    }

    private fun featureChip(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(174, 194, 228))
        background = GradientDrawable().apply {
            setColor(Color.rgb(27, 42, 66))
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun actionButton(label: String, prominent: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (prominent) Color.rgb(10, 17, 31) else Color.rgb(215, 226, 245))
        background = GradientDrawable().apply {
            setColor(if (prominent) Color.rgb(174, 199, 246) else Color.rgb(22, 32, 51))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), if (prominent) Color.rgb(198, 216, 255) else Color.rgb(42, 57, 82))
        }
        setOnClickListener { click() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWeb() {
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString.replace("; wv","")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                val h = uri.host?.lowercase(Locale.ROOT) ?: return null
                val sameSite = isTrustedSiteHost(h)
                val denied = blocking && !sameSite && blocked.any { h == it || h.endsWith(".$it") }
                return if(denied) WebResourceResponse("text/plain","utf-8",204,"Blocked", emptyMap(),java.io.ByteArrayInputStream(ByteArray(0)))
                    else super.shouldInterceptRequest(view,request)
            }
            override fun onPageStarted(view: WebView?, page: String?, favicon: android.graphics.Bitmap?) {
                page?.let { url.setText(it); host = hostOf(it); status.text = "LOADING · $host" }
            }
            override fun onPageFinished(view: WebView?, page: String?) {
                page?.let { prefs.edit().putString("last_url",it).apply(); saveHistory(it) }
                status.text = if(blocking) "PROTECTION ACTIVE · $host" else "PROTECTION PAUSED · $host"
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true
                if (scheme !in listOf("http", "https")) return true

                // Only guard top-level page changes. Embedded players, images, scripts,
                // and other subframe resources remain available unless their host is blocked.
                if (request.isForMainFrame) {
                    val destination = uri.host?.lowercase(Locale.ROOT) ?: return true
                    val isKnownAdHost = blocked.any { destination == it || destination.endsWith(".$it") }
                    if (isKnownAdHost || !isTrustedSiteHost(destination)) {
                        status.text = "BLOCKED REDIRECT · $destination"
                        return true
                    }
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = try { URI(url ?: return true) } catch (_: Exception) { return true }
                val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true
                if (scheme !in listOf("http", "https")) return true
                val destination = uri.host?.lowercase(Locale.ROOT) ?: return true
                val isKnownAdHost = blocked.any { destination == it || destination.endsWith(".$it") }
                if (isKnownAdHost || !isTrustedSiteHost(destination)) {
                    status.text = "BLOCKED REDIRECT · $destination"
                    return true
                }
                return false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if(fullView != null) { callback?.onCustomViewHidden(); return }
                fullView = view; fullCallback = callback
                root.visibility = View.GONE
                addContentView(view,ViewGroup.LayoutParams(-1,-1))
                window.decorView.systemUiVisibility = 5894 or 1024 or 512
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
    }

    private fun hostOf(value: String) = try { URI(value).host?.lowercase(Locale.ROOT) ?: "" } catch (_: Exception) { "" }

    private fun canonicalHost(value: String): String =
        value.lowercase(Locale.ROOT).removePrefix("www.")

    private fun isTrustedSiteHost(destination: String): Boolean {
        val trusted = canonicalHost(trustedHost)
        val target = canonicalHost(destination)
        return trusted.isNotBlank() && (target == trusted || target.endsWith(".$trusted"))
    }
    private fun normalized(raw: String): String? {
        var value = raw.trim()
        if(value.isEmpty()) return null
        if(!value.contains("://")) value = "https://$value"
        return try { val u = URI(value); if(u.scheme in listOf("http","https") && !u.host.isNullOrBlank()) value else null } catch (_: Exception) { null }
    }
    private fun open(raw: String) {
        val value = normalized(raw)
        if (value == null) { toast("Enter a valid website URL"); return }
        trustedHost = canonicalHost(hostOf(value))
        host = hostOf(value)
        homePanel.visibility = View.GONE
        web.visibility = View.VISIBLE
        url.setText(value)
        setBrowserChromeVisible(true)
        web.loadUrl(value)
    }
    private fun showHome() {
        web.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        setBrowserChromeVisible(true)
        browserBar.visibility = View.GONE
    }
    private fun addLink() {
        val field = EditText(this).apply { hint = "https://example.com"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Add website link").setView(field)
            .setNegativeButton("Cancel",null).setPositiveButton("Open") { _,_ -> open(field.text.toString()) }.show()
    }
    private fun saved() = prefs.getString("saved","").orEmpty().split("\n").filter { it.isNotBlank() }.toMutableList()
    private fun showSaved() {
        val list = saved()
        if(list.isEmpty()) { toast("No saved websites"); return }
        AlertDialog.Builder(this).setTitle("Saved websites").setItems(list.toTypedArray()) { _,i -> open(list[i]) }
            .setNeutralButton("Save current") { _,_ -> web.url?.let { val next = (listOf(it)+list).distinct(); prefs.edit().putString("saved",next.joinToString("\n")).apply(); toast("Saved") } }.show()
    }
    private fun saveHistory(value: String) {
        val list = prefs.getString("history","").orEmpty().split("\n").filter { it.isNotBlank() }.toMutableList()
        list.remove(value); list.add(0,value)
        prefs.edit().putString("history",list.take(80).joinToString("\n")).apply()
    }
    private fun menu() {
        val choices = arrayOf(if(blocking) "Pause ad blocking" else "Enable ad blocking","Refresh","Back","Forward","Save current website","History","Clear cookies and cache","About")
        AlertDialog.Builder(this).setTitle("StreamGurd").setItems(choices) { _,i ->
            when(i) {
                0 -> { blocking = !blocking; prefs.edit().putBoolean("blocking",blocking).apply(); web.reload() }
                1 -> web.reload()
                2 -> if(web.canGoBack()) web.goBack()
                3 -> if(web.canGoForward()) web.goForward()
                4 -> web.url?.let { val list = (listOf(it)+saved()).distinct(); prefs.edit().putString("saved",list.joinToString("\n")).apply(); toast("Website saved") }
                5 -> { val h = prefs.getString("history","").orEmpty().split("\n").filter { it.isNotBlank() }; if(h.isNotEmpty()) AlertDialog.Builder(this).setTitle("History").setItems(h.toTypedArray()) { _,n -> open(h[n]) }.show() else toast("No history") }
                6 -> { CookieManager.getInstance().removeAllCookies(null); web.clearCache(true); web.clearHistory(); toast("Browser data cleared") }
                7 -> AlertDialog.Builder(this).setTitle("About StreamGurd").setMessage("A local-first Android browser with domain-based ad and tracker blocking. It cannot guarantee removal of first-party or in-stream ads.").setPositiveButton("OK",null).show()
            }
        }.show()
    }
    private fun exitFullscreen() {
        fullView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        root.visibility = View.VISIBLE; window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }
    private fun toast(message: String) = Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        when { fullView != null -> exitFullscreen(); web.visibility == View.VISIBLE && web.canGoBack() -> web.goBack(); web.visibility == View.VISIBLE -> showHome(); else -> super.onBackPressed() }
    }
    override fun onPause() {
        web.onPause()
        mainHandler.removeCallbacks(catalogueRefresh)
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        web.onResume()
        refreshCatalogue(false)
        mainHandler.removeCallbacks(catalogueRefresh)
        mainHandler.postDelayed(catalogueRefresh, 15 * 60 * 1000L)
    }
    override fun onDestroy() {
        mainHandler.removeCallbacks(catalogueRefresh)
        web.destroy()
        super.onDestroy()
    }
}