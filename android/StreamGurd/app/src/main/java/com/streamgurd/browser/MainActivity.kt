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
import android.webkit.*
import android.widget.*
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity() {
    private data class SiteEntry(val name: String, val url: String)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val catalogueRefresh = object : Runnable {
        override fun run() {
            refreshCatalogue(false)
            mainHandler.postDelayed(this, 15 * 60 * 1000L)
        }
    }
    private val catalogueCsvUrl = "https://docs.google.com/spreadsheets/d/1VLCJE_YxSQlPbjQojaFTy1ZzrmpitetsAXDgTUpbHzs/export?format=csv&gid=0"
    private lateinit var root: LinearLayout
    private lateinit var web: WebView
    private lateinit var url: EditText
    private lateinit var status: TextView
    private lateinit var homePanel: LinearLayout
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
        val panel = Color.rgb(17, 25, 42)
        val surface = Color.rgb(23, 33, 53)
        val muted = Color.rgb(145, 161, 190)
        val accent = Color.rgb(171, 198, 255)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(bg)
        }

        // Brand header and live protection indicator
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(12))
        }
        val brandMark = TextView(this).apply {
            text = "S"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(9, 15, 29))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(190, 211, 255), Color.rgb(115, 151, 220))).apply {
                cornerRadius = dp(15).toFloat()
            }
        }
        header.addView(brandMark, LinearLayout.LayoutParams(dp(42), dp(42)))
        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        brandText.addView(text("StreamGurd", 20f, Color.WHITE, true))
        brandText.addView(text("PRIVATE BROWSING", 9f, muted, true))
        header.addView(brandText)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val shield = TextView(this).apply {
            text = if (blocking) "盾  PROTECTED" else "盾  PAUSED"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (blocking) accent else Color.rgb(255, 196, 140))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(if (blocking) Color.rgb(27, 43, 69) else Color.rgb(55, 39, 28))
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), if (blocking) Color.rgb(56, 79, 117) else Color.rgb(112, 76, 43))
            }
            setOnClickListener {
                blocking = !blocking
                prefs.edit().putBoolean("blocking", blocking).apply()
                text = if (blocking) "盾  PROTECTED" else "盾  PAUSED"
                setTextColor(if (blocking) accent else Color.rgb(255, 196, 140))
                web.reload()
            }
        }
        header.addView(shield)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        // Rounded address field
        val addressCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(3), dp(5), dp(3))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(42, 56, 82))
            }
        }
        addressCard.addView(text("⌕", 25f, accent, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(30), dp(46)))
        url = EditText(this).apply {
            hint = "Search or enter website address"
            setHintTextColor(Color.rgb(112, 128, 154))
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            setPadding(dp(5), 0, dp(5), 0)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, _, _ -> open(text.toString()); true }
        }
        addressCard.addView(url, LinearLayout.LayoutParams(0, dp(48), 1f))
        addressCard.addView(actionButton("GO", true) { open(url.text.toString()) },
            LinearLayout.LayoutParams(dp(52), dp(40)))
        root.addView(addressCard, LinearLayout.LayoutParams(-1, dp(56)))

        // Main shortcuts
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        actions.addView(actionButton("＋  Add") { addLink() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
        actions.addView(actionButton("☆  Saved") { showSaved() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        actions.addView(actionButton("☷  Sites") { showCatalogue() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        actions.addView(actionButton("⋯  Menu") { menu() },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        root.addView(actions)

        val statusRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(9))
        }
        val statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(104, 215, 163))
            }
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)))
        status = text("  READY  ·  Ad and tracker protection is on", 10f, muted)
        statusRow.addView(status)
        statusRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        root.addView(statusRow)

        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(24, 37, 61), Color.rgb(14, 21, 36))).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(42, 59, 88))
            }
        }
        val heroIcon = TextView(this).apply {
            text = "◈"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 53, 83))
                cornerRadius = dp(22).toFloat()
            }
        }
        homePanel.addView(heroIcon, LinearLayout.LayoutParams(dp(68), dp(68)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(16)
        })
        val headline = text("Browse with more control.", 22f, Color.WHITE, true)
        headline.gravity = Gravity.CENTER
        homePanel.addView(headline)
        val intro = text(
            "Your browser, your choice. StreamGurd blocks known ad and tracker domains and helps stop unwanted site redirects.",
            13f, Color.rgb(164, 180, 207)
        )
        intro.gravity = Gravity.CENTER
        intro.setLineSpacing(dp(3).toFloat(), 1f)
        intro.setPadding(dp(4), dp(10), dp(4), dp(18))
        homePanel.addView(intro)

        val featureRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        featureRow.addView(featureChip("◉  Tracker filter"), LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) })
        featureRow.addView(featureChip("↗  Redirect guard"), LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(4) })
        homePanel.addView(featureRow)
        homePanel.addView(actionButton("＋   OPEN A WEBSITE", true) { addLink() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(20) })
        root.addView(homePanel, LinearLayout.LayoutParams(-1, 0, 1f))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        val browserBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        browserBar.addView(actionButton("‹  Back") { if (web.canGoBack()) web.goBack() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        browserBar.addView(actionButton("Forward  ›") { if (web.canGoForward()) web.goForward() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        browserBar.addView(actionButton("↻  Reload") { web.reload() },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(5) })
        root.addView(browserBar)
        setContentView(root)
        web.visibility = View.GONE
        browserBar.visibility = View.GONE
        showHome()
        // Keep the compact browser controls attached to the root for visibility changes.
        web.tag = browserBar
    }

    private fun cachedCatalogue(): List<SiteEntry> {
        val raw = prefs.getString("catalogue_csv", "").orEmpty()
        return if (raw.isBlank()) emptyList() else parseCatalogue(raw)
    }

    private fun refreshCatalogue(showMessage: Boolean) {
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(catalogueCsvUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "StreamGurd Android")
                    setRequestProperty("Accept", "text/csv,text/plain,*/*")
                }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val csv = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val entries = parseCatalogue(csv)
                if (entries.isEmpty()) throw IllegalStateException("No valid Site Name / Site URL rows found")
                prefs.edit().putString("catalogue_csv", csv)
                    .putLong("catalogue_updated_at", System.currentTimeMillis()).apply()
                runOnUiThread {
                    if (showMessage) toast("Site catalogue updated · ${entries.size} sites")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (showMessage) toast("Could not refresh catalogue. Showing saved sites.")
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun parseCatalogue(csv: String): List<SiteEntry> {
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
        val header = rows.first().map { it.trim().lowercase(Locale.ROOT) }
        val nameIndex = header.indexOfFirst { it == "site name" || it == "sitename" || it == "name" }
        val urlIndex = header.indexOfFirst { it == "site url" || it == "siteurl" || it == "url" }
        if (nameIndex < 0 || urlIndex < 0) return emptyList()
        return rows.drop(1).mapNotNull { cells ->
            val name = cells.getOrNull(nameIndex)?.trim().orEmpty()
            val siteUrl = normalized(cells.getOrNull(urlIndex).orEmpty()) ?: return@mapNotNull null
            if (name.isBlank()) null else SiteEntry(name, siteUrl)
        }.distinctBy { it.url }
    }

    private fun showCatalogue() {
        val entries = cachedCatalogue()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val search = EditText(this).apply {
            hint = "Search sites"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = shape(Color.rgb(35, 45, 64), 12)
            setPadding(dp(12), 0, dp(12), 0)
        }
        val info = text(
            if (entries.isEmpty()) "No saved catalogue yet. Connect to the internet and refresh."
            else "${entries.size} sites · Updates automatically while the app is open",
            11f, Color.LTGRAY
        )
        info.setPadding(dp(2), dp(8), dp(2), dp(8))
        val listView = ListView(this)
        val filtered = entries.toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            filtered.map { it.name }.toMutableList())
        listView.adapter = adapter
        container.addView(search, LinearLayout.LayoutParams(-1, dp(48)))
        container.addView(info)
        container.addView(listView, LinearLayout.LayoutParams(-1, dp(360)))
        val dialog = AlertDialog.Builder(this).setTitle("Site Catalogue")
            .setView(container)
            .setNegativeButton("Close", null)
            .setPositiveButton("Refresh", null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = filtered.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            open(selected.url)
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                val matches = entries.filter { it.name.contains(query, true) || it.url.contains(query, true) }
                filtered.clear(); filtered.addAll(matches)
                adapter.clear(); adapter.addAll(matches.map { it.name })
                info.text = "${matches.size} of ${entries.size} sites"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                refreshCatalogue(true)
                toast("Refreshing site catalogue…")
            }
        }
        dialog.show()
        // Apply the button styling after show() creates the buttons.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.rgb(120, 160, 230))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.LTGRAY)
        if (entries.isEmpty()) refreshCatalogue(false)
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
        if(value == null) { toast("Enter a valid website URL"); return }
        trustedHost = canonicalHost(hostOf(value))
        host = hostOf(value)
        homePanel.visibility = View.GONE
        web.visibility = View.VISIBLE
        (web.tag as? View)?.visibility = View.VISIBLE
        status.text = "LOADING · $host"; url.setText(value); web.loadUrl(value)
    }
    private fun showHome() {
        web.visibility = View.GONE
        (web.tag as? View)?.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        status.text = "  READY  ·  Ad and tracker protection is on"
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
    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() {
        super.onResume()
        web.onResume()
        refreshCatalogue(false)
        mainHandler.removeCallbacks(catalogueRefresh)
        mainHandler.postDelayed(catalogueRefresh, 15 * 60 * 1000L)
    }
    override fun onPause() {
        mainHandler.removeCallbacks(catalogueRefresh)
        super.onPause()
    }
    override fun onDestroy() { mainHandler.removeCallbacks(catalogueRefresh); web.destroy(); super.onDestroy() }
}