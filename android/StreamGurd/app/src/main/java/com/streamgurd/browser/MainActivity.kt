package com.streamgurd.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import java.net.URI
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var web: WebView
    private lateinit var url: EditText
    private lateinit var status: TextView
    private lateinit var homePanel: LinearLayout
    private var blocking = true
    private var host = ""
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
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(8))
            setBackgroundColor(Color.rgb(9,13,24))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("StreamGurd",23f,Color.WHITE,true))
        val space = View(this)
        header.addView(space, LinearLayout.LayoutParams(0,1,1f))
        val shield = text(if(blocking) "🛡 BLOCKING" else "🛡 PAUSED",12f,Color.rgb(169,196,255),true)
        shield.setPadding(dp(12),dp(9),dp(12),dp(9)); shield.background = shape(Color.rgb(25,34,54),24)
        shield.setOnClickListener { blocking = !blocking; prefs.edit().putBoolean("blocking",blocking).apply(); shield.text = if(blocking) "🛡 BLOCKING" else "🛡 PAUSED"; web.reload() }
        header.addView(shield)
        root.addView(header,LinearLayout.LayoutParams(-1,dp(48)))

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10),dp(3),dp(4),dp(3))
            background = shape(Color.rgb(22,29,45),18)
        }
        url = EditText(this).apply {
            hint = "Paste website link"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE)
            textSize = 14f; setSingleLine(true); setBackgroundColor(Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _,_,_ -> open(text.toString()); true }
        }
        val go = actionButton("GO") { open(url.text.toString()) }
        bar.addView(url,LinearLayout.LayoutParams(0,dp(46),1f))
        bar.addView(go,LinearLayout.LayoutParams(dp(54),dp(40)))
        root.addView(bar,LinearLayout.LayoutParams(-1,dp(52)))

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0,dp(7),0,dp(7)) }
        actions.addView(actionButton("+ Add link") { addLink() },LinearLayout.LayoutParams(0,dp(42),1f))
        actions.addView(actionButton("☆ Saved") { showSaved() },LinearLayout.LayoutParams(0,dp(42),1f))
        actions.addView(actionButton("⋮ Menu") { menu() },LinearLayout.LayoutParams(0,dp(42),1f))
        root.addView(actions)
        status = text("READY · Aggressive protection enabled",11f,Color.rgb(143,160,190))
        root.addView(status)
        homePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22),dp(24),dp(22),dp(24))
            background = shape(Color.rgb(16,23,38),24)
        }
        homePanel.addView(text("Your quieter corner of the web",22f,Color.WHITE,true))
        val intro = text("Add a website link to browse with aggressive protection against known ad and tracker domains.",14f,Color.rgb(157,173,201))
        intro.gravity = Gravity.CENTER
        intro.setPadding(0,dp(12),0,dp(20))
        homePanel.addView(intro)
        homePanel.addView(actionButton("+ ADD WEBSITE LINK") { addLink() },LinearLayout.LayoutParams(-1,dp(48)))
        root.addView(homePanel,LinearLayout.LayoutParams(-1,0,1f))
        web = WebView(this)
        root.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        web.visibility = View.GONE
        showHome()
    }

    private fun actionButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(221,230,247)); background = shape(Color.rgb(23,32,50),14)
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
                val sameSite = h == host || h.endsWith(".$host")
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
                val scheme = request?.url?.scheme ?: return true
                return scheme !in listOf("http","https")
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
    private fun normalized(raw: String): String? {
        var value = raw.trim()
        if(value.isEmpty()) return null
        if(!value.contains("://")) value = "https://$value"
        return try { val u = URI(value); if(u.scheme in listOf("http","https") && !u.host.isNullOrBlank()) value else null } catch (_: Exception) { null }
    }
    private fun open(raw: String) {
        val value = normalized(raw)
        if(value == null) { toast("Enter a valid website URL"); return }
        homePanel.visibility = View.GONE
        web.visibility = View.VISIBLE
        status.text = "LOADING"; url.setText(value); web.loadUrl(value)
    }
    private fun showHome() {
        web.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        status.text = "READY · Blocking known ad and tracker domains"
    }
    private fun addLink() {
        val field = EditText(this).apply { hint = "https://example.com"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); singleLine = true }
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
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}