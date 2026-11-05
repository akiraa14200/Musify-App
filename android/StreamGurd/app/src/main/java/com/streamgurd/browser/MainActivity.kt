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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.*
import android.widget.*
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import android.os.SystemClock

class MainActivity : Activity() {
    private data class SiteEntry(val name: String, val url: String, val category: String)
    private data class MusicTrack(val id: String, val title: String, val artist: String, val album: String, val duration: Int, val image: String)
    private var musicSearchRequest = 0
    private val musicSearchHandler = Handler(Looper.getMainLooper())
    private var pendingMusicSearch: Runnable? = null
    private var currentMusic: MusicTrack? = null
    private var musicEmbedView: WebView? = null
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

    private var blocking = true
    private var host = ""
    private var trustedHost = ""
    private var lastUserTouchAt = 0L
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
        "pushengage.com","pushwoosh.com","adpushup.com","adskeeper.co.uk","ad-maven.com",
        "juicyads.com","juicyads.com","adcash.com","adclick.g.doubleclick.net","popunder.net",
        "popunderjs.com","onclickmega.com","onclickmega.com","understoodads.com","trafficstars.com",
        "adf.ly","shorte.st","clk.sh","linkvertise.com","loot-link.com","lootlinks.co",
        "adsco.re","a-ads.com","adfoc.us","cointraffic.io","hilltopads.com","monetag.com",
        "monetag.com","galaksion.com","dtscout.com","tsyndicate.com","exosrv.com","exoclick.org",
        "adnium.com","adnium.net","adspyglass.com","adskeeper.com","adsterra.org","propellerads.com",
        "onclickads.net","popunder.com","popads.net","popcash.net","trafficjunky.com",
        "ad-delivery.net","adnami.io","admanmedia.com","admedo.com","adnetwork.vn"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blocking = prefs.getBoolean("blocking", true)
        // Create the root view before attaching its system-insets listener.
        buildUi()
        applyBrowserSystemBars()
        configureWeb()
        prefs.getString("last_url", null)?.let { open(it) }
    }

    private fun applyBrowserSystemBars() {
        window.statusBarColor = Color.rgb(16, 18, 20)
        window.navigationBarColor = Color.rgb(16, 18, 20)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        // Explicitly reserve the status-bar/notch and bottom system-gesture areas.
        // Content and browser controls are laid out only inside the remaining safe area.
        root?.setOnApplyWindowInsetsListener { view, insets ->
            val safeInsets = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.displayCutout()
                )
                Pair(maxOf(bars.top, insets.displayCutout?.safeInsetTop ?: 0), bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                Pair(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
            }
            view.setPadding(0, safeInsets.first, 0, safeInsets.second)
            insets
        }
        root?.requestApplyInsets()
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
        val bg = Color.rgb(16, 18, 20)
        val surface = Color.rgb(23, 33, 53)
        val muted = Color.rgb(178, 183, 190)
        val accent = Color.rgb(226, 229, 232)

        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(4))
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(contentLayout, FrameLayout.LayoutParams(-1, -1))

        headerBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, dp(2), dp(3)) }
        val brandMark = ImageView(this).apply {
            setImageResource(R.drawable.strawhat_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(11).toFloat()
            }
            clipToOutline = true
            contentDescription = "Strawhat Pirate logo"
        }
        headerBar.addView(brandMark, LinearLayout.LayoutParams(dp(38), dp(38)))
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
        brandText.addView(text("Strawhat Pirate", 16f, Color.WHITE, true))
        brandText.addView(text("THE GRAND LINE BROWSER", 8f, muted, true))
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
        val eye = actionButton("◉ Hide controls") { toggleBrowserChrome() }.apply { textSize = 10f }
        contentLayout.addView(headerBar, LinearLayout.LayoutParams(-1, dp(38)))

        addressBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), 0, dp(4), 0)
            background = glassShape(0xB82A3B55.toInt(), 13)
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
        // Address/search bar is hidden; use Add Website from the floating menu.

        actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(1))
        }
        fun addFeature(label: String, onClick: () -> Unit) {
            actionRow.addView(actionButton(label, false, onClick).apply {
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                background = darkGlassShape(0x8A171A20.toInt(), 15)
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(7) })
        }
        addFeature("♫  Music Player · YouTube Music", ::showMusicPlayer)
        addFeature("＋  Add Website", ::addLink)
        addFeature("☆  Saved Websites", ::showSaved)
        addFeature("☷  Anime & Manga Sites", ::showCatalogue)
        addFeature("⋯  Browser Menu", ::menu)
        addFeature(if (blocking) "盾  Redirect Protection: On" else "盾  Redirect Protection: Off") {
            blocking = !blocking
            prefs.edit().putBoolean("blocking", blocking).apply()
            shield.text = if (blocking) "盾 Protected" else "盾 Paused"
            (actionRow.getChildAt(4) as? TextView)?.text =
                if (blocking) "盾  Redirect Protection: On" else "盾  Redirect Protection: Off"
            web.reload()
        }
        addFeature("◉  Hide Browser Controls", ::toggleBrowserChrome)
        addFeature("⌂  App Home", ::showHome)
        val topActions = LinearLayout(this).apply {
            tag = "top-actions-panel"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(1))
            background = darkGlassShape(0xC0101218.toInt(), 22)
            elevation = dp(16).toFloat()
            visibility = View.GONE
        }
        topActions.addView(actionRow, LinearLayout.LayoutParams(dp(246), -2))
        val topActionsToggle = actionButton("☰") {
            topActions.visibility = if (topActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }.apply {
            textSize = 21f
            background = darkGlassShape(0xD014171D.toInt(), 28)
            elevation = dp(18).toFloat()
        }
        root.addView(topActions, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(66); rightMargin = dp(12)
        })
        topActionsToggle.tag = "top-actions-toggle"
        val floatingParams = FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.START).apply {
            leftMargin = prefs.getInt("menu_x", dp(12)).coerceAtLeast(0)
            topMargin = prefs.getInt("menu_y", dp(72)).coerceAtLeast(0)
        }
        root.addView(topActionsToggle, floatingParams)
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var dragged = false
        topActionsToggle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    val lp = view.layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin; startTop = lp.topMargin
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) dragged = true
                    if (dragged) {
                        val lp = view.layoutParams as FrameLayout.LayoutParams
                        lp.gravity = Gravity.TOP or Gravity.START
                        lp.leftMargin = (startLeft + dx).coerceIn(0, (root.width - view.width).coerceAtLeast(0))
                        lp.topMargin = (startTop + dy).coerceIn(0, (root.height - view.height).coerceAtLeast(0))
                        view.layoutParams = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        val lp = view.layoutParams as FrameLayout.LayoutParams
                        prefs.edit().putInt("menu_x", lp.leftMargin).putInt("menu_y", lp.topMargin).apply()
                    } else {
                        view.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        topActionsToggle.setOnClickListener {
            topActions.visibility = if (topActions.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        statusRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), 0, dp(3), dp(2)) }
        val statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(104, 215, 163)) } }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(6), dp(6)))
        status = text("  READY · Protection active", 9f, muted)
        statusRow.addView(status)
        contentLayout.addView(statusRow, LinearLayout.LayoutParams(-1, dp(17)))

        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(43, 46, 49), Color.rgb(25, 27, 29))).apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.rgb(105, 109, 113)) }
        }
        val heroIcon = TextView(this).apply { text = "🏴‍☠️"; textSize = 34f; gravity = Gravity.CENTER; setTextColor(accent); background = GradientDrawable().apply { setColor(Color.rgb(58, 61, 64)); cornerRadius = dp(20).toFloat() } }
        homePanel.addView(heroIcon, LinearLayout.LayoutParams(dp(62), dp(62)).apply { gravity = Gravity.CENTER; bottomMargin = dp(14) })
        homePanel.addView(text("Set sail across the web.", 21f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        homePanel.addView(text("Your browser, your choice. Strawhat Pirate filters known ad and tracker domains and helps stop unwanted redirects.", 12f, Color.rgb(164, 180, 207)).apply { gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f); setPadding(dp(4), dp(9), dp(4), dp(16)) })
        val featureRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        featureRow.addView(featureChip("◉ Tracker filter"), LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        featureRow.addView(featureChip("↗ Redirect guard"), LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4) })
        homePanel.addView(featureRow)
        val entryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        entryActions.addView(actionButton("＋ ADD WEBSITE", true) { addLink() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(5) })
        entryActions.addView(actionButton("☷ SITE CATALOGUE", false) { showCatalogue() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(5) })
        homePanel.addView(entryActions, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(18) })
        contentLayout.addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))

        web = WebView(this)
        root.addView(web, 0, FrameLayout.LayoutParams(-1, -1))
        web.visibility = View.GONE
        peekButton = actionButton("◉") { toggleBrowserChrome() }.apply { textSize = 18f; visibility = View.GONE; elevation = dp(8).toFloat() }
        root.addView(peekButton, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(10); bottomMargin = dp(10) })
        setContentView(root)
        web.visibility = View.GONE
        showHome()
    }

    private fun toggleBrowserChrome() { setBrowserChromeVisible(!chromeVisible) }

    private fun setBrowserChromeVisible(visible: Boolean) {
        chromeVisible = visible
        val v = if (visible) View.VISIBLE else View.GONE
        headerBar.visibility = if (visible && web.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        addressBar.visibility = v; statusRow.visibility = v
        // The expandable floating top-actions control is part of the root overlay.
        root.findViewWithTag<View>("top-actions-toggle")?.visibility = v
        root.findViewWithTag<View>("top-actions-panel")?.visibility =
            if (visible) View.GONE else View.GONE
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
            background = shape(Color.rgb(48, 51, 54),12); setPadding(dp(12),0,dp(12),0)
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
            animeTab.background=shape(if(which=="Anime") Color.rgb(225, 228, 230) else Color.rgb(43, 46, 49),14)
            mangaTab.background=shape(if(which=="Manga") Color.rgb(225, 228, 230) else Color.rgb(43, 46, 49),14)
            animeTab.setTextColor(if(which=="Anime") Color.rgb(25, 27, 29) else Color.rgb(235, 237, 239))
            mangaTab.setTextColor(if(which=="Manga") Color.rgb(25, 27, 29) else Color.rgb(235, 237, 239))
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
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(205, 209, 212))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.LTGRAY)
        render("Anime")
        if(cachedCatalogue("Anime").isEmpty() || cachedCatalogue("Manga").isEmpty()) refreshCatalogue(false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showMusicPlayer() {
        val playerWeb = WebView(this).apply {
            setBackgroundColor(Color.rgb(8, 8, 10))
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                loadsImagesAutomatically = true
                databaseEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return true
                    return uri.scheme !in listOf("https", "http")
                }
            }
        }
        musicEmbedView = playerWeb
        currentMusic = null

        val dialog = AlertDialog.Builder(this).setView(playerWeb).create()
        var minimized = false
        fun resizePlayer(mini: Boolean) {
            minimized = mini
            val window = dialog.window ?: return
            window.setBackgroundDrawableResource(android.R.color.transparent)
            val width = if (mini) -1 else (resources.displayMetrics.widthPixels * 0.98f).toInt()
            val height = if (mini) dp(90) else (resources.displayMetrics.heightPixels * 0.94f).toInt()
            window.setLayout(width, height)
            window.setGravity(if (mini) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER)
            val params = window.attributes
            params.dimAmount = if (mini) 0f else 0.35f
            window.attributes = params
        }
        playerWeb.addJavascriptInterface(object {
            @JavascriptInterface fun minimizePlayer() { runOnUiThread { resizePlayer(true) } }
            @JavascriptInterface fun expandPlayer() { runOnUiThread { resizePlayer(false) } }
        }, "StreamGurd")

        dialog.setOnDismissListener {
            playerWeb.stopLoading()
            playerWeb.loadUrl("about:blank")
            playerWeb.destroy()
            if (musicEmbedView === playerWeb) musicEmbedView = null
            currentMusic = null
        }
        dialog.show()
        resizePlayer(false)

        try {
            val html = assets.open("music_player.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            playerWeb.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
        } catch (e: Exception) {
            toast("Music player could not be loaded: ${e.message ?: "asset missing"}")
            dialog.dismiss()
        }
    }

    private fun youtubeApiKey(): String = prefs.getString("youtube_data_api_key","").orEmpty().trim()

    private fun configureYouTubeApiKey(info: TextView) {
        val field=EditText(this).apply { hint="Paste YouTube Data API v3 key"; setText(youtubeApiKey()); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); setSingleLine(true); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("YouTube Data API key")
            .setMessage("Search uses Google's official YouTube Data API. Enable YouTube Data API v3 in your Google Cloud project. Your key is stored only on this device; restrict it to this app where possible.")
            .setView(field).setNegativeButton("Cancel",null).setPositiveButton("Save") { _,_ ->
                val key=field.text.toString().trim()
                if(key.isBlank()) { prefs.edit().remove("youtube_data_api_key").apply(); info.text="API key cleared" }
                else { prefs.edit().putString("youtube_data_api_key",key).apply(); info.text="API key saved · Search YouTube" }
            }.show()
    }

    private fun searchYouTubeMusic(raw:String,tracks:MutableList<MusicTrack>,adapter:BaseAdapter,info:TextView) {
        val key=youtubeApiKey(); val query=raw.trim()
        if(key.isBlank()) { info.text="Tap API KEY and add your YouTube Data API key."; return }
        if(query.isEmpty()) return
        val requestId=++musicSearchRequest; info.text="Searching YouTube…"
        Thread {
            var connection:HttpURLConnection?=null
            try {
                val endpoint="https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=20&q="+java.net.URLEncoder.encode(query,"UTF-8")+"&key="+java.net.URLEncoder.encode(key,"UTF-8")
                connection=(URL(endpoint).openConnection() as HttpURLConnection).apply { connectTimeout=10000; readTimeout=15000; requestMethod="GET"; setRequestProperty("Accept","application/json") }
                val code=connection.responseCode
                val body=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if(code !in 200..299) {
                    val message=try { org.json.JSONObject(body).getJSONObject("error").optString("message") } catch(_:Exception) { "HTTP $code" }
                    throw IllegalStateException(message.ifBlank { "HTTP $code" })
                }
                val items=org.json.JSONObject(body).optJSONArray("items") ?: org.json.JSONArray()
                val found=mutableListOf<MusicTrack>(); val seen=HashSet<String>()
                for(i in 0 until items.length()) {
                    val item=items.optJSONObject(i) ?: continue
                    val id=item.optJSONObject("id")?.optString("videoId").orEmpty()
                    val snippet=item.optJSONObject("snippet") ?: continue
                    val title=snippet.optString("title").trim()
                    if(id.isBlank() || title.isBlank() || !seen.add(id)) continue
                    val thumbs=snippet.optJSONObject("thumbnails")
                    val image=thumbs?.optJSONObject("medium")?.optString("url")?.takeIf { !it.isNullOrBlank() } ?: thumbs?.optJSONObject("default")?.optString("url").orEmpty()
                    found.add(MusicTrack(id,title,snippet.optString("channelTitle"),"YouTube",0,image))
                }
                runOnUiThread { if(requestId!=musicSearchRequest) return@runOnUiThread; tracks.clear(); tracks.addAll(found); adapter.notifyDataSetChanged(); info.text=if(found.isEmpty()) "No videos found. Try another search." else "${found.size} official YouTube results · Tap a result to play" }
            } catch(e:Exception) {
                runOnUiThread { if(requestId!=musicSearchRequest) return@runOnUiThread; info.text="YouTube search failed · ${e.message ?: "Check API key and quota"}" }
            } finally { connection?.disconnect() }
        }.start()
    }

    private fun stopMusic(updateStatus:Boolean=true) {
        musicEmbedView?.stopLoading()
        musicEmbedView?.loadUrl("about:blank")
        currentMusic=null
    }

    private fun featureChip(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(215, 218, 221))
        background = GradientDrawable().apply {
            setColor(Color.rgb(48, 51, 54))
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun actionButton(label: String, prominent: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (prominent) Color.rgb(25, 29, 34) else Color.rgb(242, 244, 246))
        background = GradientDrawable().apply {
            setColor(if (prominent) Color.rgb(226, 229, 232) else 0x52FFFFFF)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), if (prominent) Color.rgb(245, 246, 247) else 0x88FFFFFF.toInt())
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
        web.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) lastUserTouchAt = SystemClock.elapsedRealtime()
            false
        }
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
        lastUserTouchAt = 0L
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
        val choices = arrayOf(if(blocking) "Pause redirect protection" else "Enable redirect protection","Refresh","Back","Forward","Save current website","History","Clear cookies and cache","About Strawhat Pirate","Privacy policy","Terms of use")
        AlertDialog.Builder(this).setTitle("Strawhat Pirate").setItems(choices) { _,i ->
            when(i) {
                0 -> { blocking = !blocking; prefs.edit().putBoolean("blocking",blocking).apply(); web.reload() }
                1 -> web.reload()
                2 -> if(web.canGoBack()) web.goBack()
                3 -> if(web.canGoForward()) web.goForward()
                4 -> web.url?.let { val list = (listOf(it)+saved()).distinct(); prefs.edit().putString("saved",list.joinToString("\n")).apply(); toast("Website saved") }
                5 -> { val h = prefs.getString("history","").orEmpty().split("\n").filter { it.isNotBlank() }; if(h.isNotEmpty()) AlertDialog.Builder(this).setTitle("History").setItems(h.toTypedArray()) { _,n -> open(h[n]) }.show() else toast("No history") }
                6 -> { CookieManager.getInstance().removeAllCookies(null); web.clearCache(true); web.clearHistory(); toast("Browser data cleared") }
                7 -> showAbout()
                8 -> showPolicy("Privacy policy", "Strawhat Pirate stores bookmarks, browsing history, catalogue cache and your last opened page locally on this device. Website requests go directly to the sites you visit. The app does not operate an account system or sell personal data. Websites may use their own cookies and tracking technologies. Clearing browser data removes local cookies, cache and history.")
                9 -> showPolicy("Terms of use", "Use Strawhat Pirate lawfully and respect each website's terms, copyright and access rules. You are responsible for the websites and content you choose to visit. Redirect protection prevents pop-up windows and links that try to open new tabs; it does not block advertisements or guarantee protection from every redirect. The app is provided as-is. Do not use it to bypass access controls or infringe anyone's rights.")
            }
        }.show()
    }
    // Dark translucent surfaces keep the feature drawer readable while retaining a glass-like finish.
    private fun darkGlassShape(color: Int, radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.argb(238, 30, 33, 40),
            color,
            Color.argb(220, 7, 9, 13)
        )
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0x77FFFFFF)
    }

    private fun glassShape(color: Int, radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.argb(220, 111, 148, 194),
            color,
            Color.argb(205, 18, 29, 48)
        )
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), 0x99FFFFFF.toInt())
    }

    private fun showAbout() {
        AlertDialog.Builder(this).setTitle("🏴‍☠️ Strawhat Pirate")
            .setMessage("A local-first Android browser for exploring the web with a built-in site catalogue, saved links, browsing history and a lightweight domain filter.\n\nMade for personal browsing. Anime and Manga catalogues are supplied by the linked public spreadsheet.\n\nAd filtering is domain-based and cannot guarantee removal of first-party or in-stream ads.")
            .setPositiveButton("Privacy policy") { _, _ -> showPolicy("Privacy policy", "Bookmarks, history, catalogue cache and the last page are stored locally on this device. Website requests are sent to the websites you open. The app has no account system and does not sell personal data. Sites may set their own cookies. Clear browser data from the menu to remove local cookies, cache and history.") }
            .setNeutralButton("Terms") { _, _ -> showPolicy("Terms of use", "Use this browser lawfully. Respect website terms, copyright and access restrictions. You are responsible for your browsing and the content you access. Filtering is best-effort only and does not guarantee privacy, security, complete ad removal or uninterrupted access. The app is provided as-is.") }
            .setNegativeButton("Close", null).show()
    }

    private fun showPolicy(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Got it", null).show()
    }

    private fun exitFullscreen() {
        fullView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullView = null; fullCallback?.onCustomViewHidden(); fullCallback = null
        root.visibility = View.VISIBLE
        applyBrowserSystemBars()
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
        stopMusic(updateStatus = false)
        musicEmbedView?.destroy()
        web.destroy()
        super.onDestroy()
    }
}