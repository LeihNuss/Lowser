package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.UnsupportedEncodingException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearchUrl: EditText
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnClearUrl: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var webStatusIcon: ImageView
    private lateinit var queueAccentLine: View
    private lateinit var queueBadgeContainer: View
    private lateinit var queueBadgeText: TextView
    private lateinit var queueDockButton: View

    // Multi-Tab management
    private lateinit var btnTabs: View
    private lateinit var tabsBadgeText: TextView
    private lateinit var tabOverviewContainer: View
    private lateinit var tvTabOverviewSubtitle: TextView
    private lateinit var btnNewTab: View
    private lateinit var btnTabOverviewClose: View
    private lateinit var rvTabs: RecyclerView

    private val tabsList = mutableListOf<BrowserTab>()
    private var activeTabId: String = ""
    private lateinit var tabsAdapter: TabsAdapter

    // Active WebView Getter
    private val webView: WebView?
        get() = tabsList.find { it.id == activeTabId }?.webView

    // Sequential Queue reading dock list (preserves sequential link flow)
    private val queueList = mutableListOf<String>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up immersive edge-to-edge full-screen layout (auto-adjusting status bar & keyboard)
        applyImmersiveLayout()

        // Bind layouts
        webViewContainer = findViewById(R.id.webViewContainer)
        progressBar = findViewById(R.id.progressBar)
        etSearchUrl = findViewById(R.id.etSearchUrl)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnClearUrl = findViewById(R.id.btnClearUrl)
        btnRefresh = findViewById(R.id.btnRefresh)
        webStatusIcon = findViewById(R.id.webStatusIcon)
        queueAccentLine = findViewById(R.id.queueAccentLine)
        queueBadgeContainer = findViewById(R.id.queueBadgeContainer)
        queueBadgeText = findViewById(R.id.queueBadgeText)
        queueDockButton = findViewById(R.id.queueDockButton)

        // Bind multi-tab elements
        btnTabs = findViewById(R.id.btnTabs)
        tabsBadgeText = findViewById(R.id.tabsBadgeText)
        tabOverviewContainer = findViewById(R.id.tabOverviewContainer)
        tvTabOverviewSubtitle = findViewById(R.id.tvTabOverviewSubtitle)
        btnNewTab = findViewById(R.id.btnNewTab)
        btnTabOverviewClose = findViewById(R.id.btnTabOverviewClose)
        rvTabs = findViewById(R.id.rvTabs)

        // Bind Custom Adapter
        tabsAdapter = TabsAdapter(tabsList, activeTabId,
            onTabClick = { tab ->
                switchToTab(tab.id)
                hideTabOverview()
            },
            onTabClose = { tab ->
                closeTab(tab)
            }
        )
        rvTabs.layoutManager = LinearLayoutManager(this)
        rvTabs.adapter = tabsAdapter

        // Setup custom back actions using Predictive Back callback API
        setupBackPressedDispatcher()

        // Configure user inputs and key events
        setupControllerListeners()

        // Set up custom swipe detection from the bottom panel
        setupSwipeGesture()

        // Restore queue and tabs from persistent SharedPreferences
        loadQueueState()
        updateQueueUi()

        val tabsRestored = loadTabsState()
        if (!tabsRestored) {
            // Standard startup homepage with a default tab if no saved tabs exist
            createNewTab("https://www.google.com")
        }
    }

    private fun applyImmersiveLayout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        // Raise floating menu automatically over soft keyboard toggles elegantly
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val layoutParams = findViewById<View>(R.id.bottomBarCard).layoutParams as ViewGroup.MarginLayoutParams
            
            if (imeInsets.bottom > 0) {
                // Raise exactly above soft keyboard with comfortable spacing
                layoutParams.bottomMargin = imeInsets.bottom + dpToPx(8)
            } else {
                layoutParams.bottomMargin = navInsets.bottom + dpToPx(16)
            }
            findViewById<View>(R.id.bottomBarCard).layoutParams = layoutParams
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(url: String, focus: Boolean = true): BrowserTab {
        val id = java.util.UUID.randomUUID().toString()
        val context = this
        val tagWebView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Configure system settings for newly spawned WebView instances
        configureWebViewSettings(tagWebView)

        val tab = BrowserTab(id, tagWebView, "New Tab", url)
        tabsList.add(tab)

        if (focus) {
            switchToTab(id)
        } else {
            updateTabsUi()
            saveTabsState()
        }

        tagWebView.loadUrl(url)
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings(wv: WebView) {
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        
        // Enforce hardware acceleration
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (view == webView) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 5
                    if (!etSearchUrl.hasFocus()) {
                        etSearchUrl.setText(cleanUrlForDisplay(url))
                    }
                    updateNavigationButtons()
                }
                tabsList.find { it.webView == view }?.let {
                    it.url = url ?: "about:blank"
                }
                tabsAdapter.notifyDataSetChanged()
                saveTabsState()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == webView) {
                    progressBar.visibility = View.GONE
                    if (!etSearchUrl.hasFocus()) {
                        etSearchUrl.setText(cleanUrlForDisplay(url))
                    }
                    updateNavigationButtons()
                }
                tabsList.find { it.webView == view }?.let {
                    it.url = url ?: "about:blank"
                }
                tabsAdapter.notifyDataSetChanged()
                saveTabsState()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                view?.loadUrl(url)
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (view == webView) {
                    progressBar.progress = newProgress
                    if (newProgress >= 100) {
                        progressBar.visibility = View.GONE
                    } else {
                        progressBar.visibility = View.VISIBLE
                    }
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                tabsList.find { it.webView == view }?.let {
                    it.title = title ?: "New Tab"
                }
                tabsAdapter.notifyDataSetChanged()
                if (view == webView && !title.isNullOrEmpty() && etSearchUrl.text.isEmpty()) {
                    etSearchUrl.hint = title
                }
                saveTabsState()
            }
        }

        // Long press handling to add link elements directly into Queue list
        wv.setOnLongClickListener {
            val result = wv.hitTestResult
            val type = result.type
            if (type == HitTestResult.SRC_ANCHOR_TYPE || type == HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val linkUrl = result.extra
                if (!linkUrl.isNullOrEmpty()) {
                    wv.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    addToQueue(linkUrl)
                    return@setOnLongClickListener true
                }
            }
            false
        }
    }

    private fun switchToTab(tabId: String) {
        val tab = tabsList.find { it.id == tabId } ?: return
        activeTabId = tabId

        val tabWeb = tab.webView

        // Safe dynamic View parenting swap to prevent IllegalStateException crashes
        val parent = tabWeb.parent as? ViewGroup
        if (parent != webViewContainer) {
            parent?.removeView(tabWeb)
            webViewContainer.removeAllViews()
            webViewContainer.addView(tabWeb)
        } else {
            if (webViewContainer.childCount != 1 || webViewContainer.getChildAt(0) != tabWeb) {
                webViewContainer.removeAllViews()
                webViewContainer.addView(tabWeb)
            }
        }

        // Sync header hints & controls info
        etSearchUrl.setText(cleanUrlForDisplay(tabWeb.url))
        etSearchUrl.hint = tab.title
        updateNavigationButtons()
        updateTabsUi()
        saveTabsState()
    }

    private fun closeTab(tab: BrowserTab) {
        if (tabsList.size == 1) {
            val oldWeb = tab.webView
            (oldWeb.parent as? ViewGroup)?.removeView(oldWeb)
            tabsList.remove(tab)
            oldWeb.destroy()

            createNewTab("https://www.google.com")
        } else {
            val index = tabsList.indexOf(tab)
            tabsList.remove(tab)

            val oldWeb = tab.webView
            (oldWeb.parent as? ViewGroup)?.removeView(oldWeb)
            oldWeb.destroy()

            if (activeTabId == tab.id) {
                val nextIndex = if (index >= tabsList.size) tabsList.size - 1 else index
                switchToTab(tabsList[nextIndex].id)
            } else {
                updateTabsUi()
                saveTabsState()
            }
        }
    }

    private fun setupBackPressedDispatcher() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (tabOverviewContainer.visibility == View.VISIBLE) {
                    hideTabOverview()
                } else if (webView?.canGoBack() == true) {
                    webView?.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControllerListeners() {
        btnBack.setOnClickListener {
            webView?.let { if (it.canGoBack()) it.goBack() }
        }

        btnForward.setOnClickListener {
            webView?.let { if (it.canGoForward()) it.goForward() }
        }

        btnRefresh.setOnClickListener {
            webView?.reload()
        }

        btnClearUrl.setOnClickListener {
            etSearchUrl.text.clear()
        }

        btnTabs.setOnClickListener {
            if (tabOverviewContainer.visibility == View.VISIBLE) {
                hideTabOverview()
            } else {
                showTabOverview()
            }
        }

        btnNewTab.setOnClickListener {
            createNewTab("https://www.google.com")
            hideTabOverview()
        }

        btnTabOverviewClose.setOnClickListener {
            hideTabOverview()
        }

        // Toggle clear button depending on input focus/presence
        etSearchUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnClearUrl.visibility = if (etSearchUrl.text.isNotEmpty()) View.VISIBLE else View.GONE
                webView?.let { etSearchUrl.setText(it.url) }
                etSearchUrl.selectAll()
            } else {
                btnClearUrl.visibility = View.GONE
                webView?.let { etSearchUrl.setText(cleanUrlForDisplay(it.url)) }
            }
        }

        etSearchUrl.setOnEditorActionListener { textView, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (keyEvent != null && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER && keyEvent.action == KeyEvent.ACTION_DOWN)) {
                
                val source = textView.text.toString().trim()
                if (source.isNotEmpty() && webView != null) {
                    val formatted = formatUrl(source)
                    webView?.loadUrl(formatted)
                    etSearchUrl.clearFocus()
                    
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(etSearchUrl.windowToken, 0)
                }
                true
            } else {
                false
            }
        }

        queueDockButton.setOnClickListener {
            if (queueList.isEmpty()) {
                Toast.makeText(this, "The Sequential Queue is empty. Long-press link targets to stack them.", Toast.LENGTH_LONG).show()
            } else {
                showManageQueueDialog()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeGesture() {
        val bottomBarCard = findViewById<View>(R.id.bottomBarCard)
        bottomBarCard.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private val swipeMinDistance = 140
            private val swipeMaxOffPath = 150

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        return false // Allow clicking nested buttons
                    }
                    MotionEvent.ACTION_UP -> {
                        val endX = event.rawX
                        val endY = event.rawY
                        val deltaX = startX - endX // Positive represents right-to-left swipe
                        val deltaY = Math.abs(startY - endY)

                        if (deltaX > swipeMinDistance && deltaY < swipeMaxOffPath) {
                            if (queueList.isNotEmpty()) {
                                performTransitionToNextQueue()
                                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                return true
                            } else {
                                Toast.makeText(this@MainActivity, "Empty Queue. Long-press link to load first.", Toast.LENGTH_SHORT).show()
                                return true
                            }
                        }
                    }
                }
                return false
            }
        })
    }

    private fun addToQueue(url: String) {
        queueList.add(url)
        updateQueueUi()
        
        pulseView(queueDockButton)
        
        val host = getDomainName(url)
        showNotificationToast("Added to reading queue: $host")
        saveQueueState()
    }

    private fun performTransitionToNextQueue() {
        if (queueList.isEmpty() || webView == null) return

        val targetUrl = queueList.removeAt(0)
        updateQueueUi()

        showSwipeIndicatorFeedback(targetUrl)
        saveQueueState()

        val activeWeb = webView ?: return
        activeWeb.animate()
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                activeWeb.loadUrl(targetUrl)
                activeWeb.animate()
                    .alpha(1f)
                    .setDuration(240)
                    .start()
            }
            .start()
    }

    private fun updateQueueUi() {
        val size = queueList.size
        if (size > 0) {
            queueBadgeContainer.visibility = View.VISIBLE
            queueBadgeText.text = size.toString()
            queueAccentLine.visibility = View.VISIBLE
        } else {
            queueBadgeContainer.visibility = View.INVISIBLE
            queueAccentLine.visibility = View.GONE
        }
    }

    private fun updateTabsUi() {
        val count = tabsList.size
        tabsBadgeText.text = count.toString()
        tvTabOverviewSubtitle.text = if (count == 1) "1 active tab" else "$count active tabs"
        tabsAdapter.updateData(tabsList, activeTabId)
    }

    private fun updateNavigationButtons() {
        val wv = webView
        if (wv != null) {
            btnBack.isEnabled = wv.canGoBack()
            btnBack.alpha = if (wv.canGoBack()) 1.0f else 0.4f
            btnForward.isEnabled = wv.canGoForward()
            btnForward.alpha = if (wv.canGoForward()) 1.0f else 0.4f
        } else {
            btnBack.isEnabled = false
            btnBack.alpha = 0.4f
            btnForward.isEnabled = false
            btnForward.alpha = 0.4f
        }
    }

    private fun showSwipeIndicatorFeedback(url: String) {
        val sweepHint = findViewById<TextView>(R.id.queueSwipeIndicator)
        sweepHint.text = "Loading Next URL: " + getDomainName(url)
        sweepHint.visibility = View.VISIBLE
        sweepHint.alpha = 1f
        sweepHint.animate()
            .alpha(0f)
            .setDuration(1200)
            .withEndAction { sweepHint.visibility = View.GONE }
            .start()
    }

    private fun showTabOverview() {
        tabOverviewContainer.visibility = View.VISIBLE
        tabOverviewContainer.alpha = 0f
        tabOverviewContainer.animate()
            .alpha(1f)
            .setDuration(200)
            .start()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearchUrl.windowToken, 0)
        etSearchUrl.clearFocus()
    }

    private fun hideTabOverview() {
        tabOverviewContainer.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                tabOverviewContainer.visibility = View.GONE
            }
            .start()
    }

    private fun showManageQueueDialog() {
        val itemsArray = queueList.map { cleanUrlForDisplay(it) }.toTypedArray()
        
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Reading Queue Dock")
            .setItems(itemsArray) { dialog, which ->
                val selectedUrl = queueList.removeAt(which)
                updateQueueUi()
                webView?.loadUrl(selectedUrl)
                saveQueueState()
                dialog.dismiss()
            }
            .setPositiveButton("Clear All") { dialog, _ ->
                queueList.clear()
                updateQueueUi()
                saveQueueState()
                dialog.dismiss()
                Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun pulseView(view: View) {
        view.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(120)
                    .start()
            }
            .start()
    }

    private fun showNotificationToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun cleanUrlForDisplay(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        var cleaned = url
        if (cleaned.startsWith("https://")) cleaned = cleaned.substring(8)
        else if (cleaned.startsWith("http://")) cleaned = cleaned.substring(7)
        if (cleaned.startsWith("www.")) cleaned = cleaned.substring(4)
        if (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length - 1)
        return cleaned
    }

    private fun getDomainName(url: String): String {
        return try {
            val uri = URI(url)
            val domain = uri.host ?: return url
            if (domain.startsWith("www.")) domain.substring(4) else domain
        } catch (e: Exception) {
            url
        }
    }

    private fun formatUrl(input: String): String {
        val trimmed = input.trim()
        if (URLUtil.isValidUrl(trimmed)) {
            return trimmed
        }
        val isDomain = trimmed.contains(".") && !trimmed.contains(" ")
        if (isDomain) {
            return "https://$trimmed"
        }
        return try {
            "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
        } catch (e: UnsupportedEncodingException) {
            "https://www.google.com/search?q=$trimmed"
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return Math.round(dp.toFloat() * density)
    }

    private fun saveTabsState() {
        try {
            val sharedPref = getSharedPreferences("browser_tabs_ref", Context.MODE_PRIVATE)
            val jsonArray = org.json.JSONArray()
            for (tab in tabsList) {
                val jsonObj = org.json.JSONObject()
                jsonObj.put("id", tab.id)
                jsonObj.put("title", tab.title)
                jsonObj.put("url", tab.webView.url ?: tab.url)
                jsonArray.put(jsonObj)
            }
            sharedPref.edit()
                .putString("tabs_data", jsonArray.toString())
                .putString("active_tab_id", activeTabId)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadTabsState(): Boolean {
        try {
            val sharedPref = getSharedPreferences("browser_tabs_ref", Context.MODE_PRIVATE)
            val tabsJson = sharedPref.getString("tabs_data", null)
            val savedActiveId = sharedPref.getString("active_tab_id", "")
            if (!tabsJson.isNullOrEmpty()) {
                val jsonArray = org.json.JSONArray(tabsJson)
                if (jsonArray.length() > 0) {
                    tabsList.clear()
                    var lastActiveId = savedActiveId ?: ""
                    var matchedActiveTab: BrowserTab? = null

                    for (i in 0 until jsonArray.length()) {
                        val jsonObj = jsonArray.getJSONObject(i)
                        val id = jsonObj.getString("id")
                        val title = jsonObj.getString("title")
                        val url = jsonObj.getString("url")

                        val context = this
                        val wv = WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                        configureWebViewSettings(wv)
                        val tab = BrowserTab(id, wv, title, url)
                        tabsList.add(tab)
                        wv.loadUrl(url)

                        if (id == lastActiveId) {
                            matchedActiveTab = tab
                        }
                    }

                    val focusTab = matchedActiveTab ?: tabsList.first()
                    switchToTab(focusTab.id)
                    updateTabsUi()
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun saveQueueState() {
        try {
            val sharedPref = getSharedPreferences("browser_tabs_ref", Context.MODE_PRIVATE)
            val jsonArray = org.json.JSONArray()
            for (item in queueList) {
                jsonArray.put(item)
            }
            sharedPref.edit()
                .putString("queue_data", jsonArray.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadQueueState() {
        try {
            val sharedPref = getSharedPreferences("browser_tabs_ref", Context.MODE_PRIVATE)
            val queueJson = sharedPref.getString("queue_data", null)
            if (!queueJson.isNullOrEmpty()) {
                queueList.clear()
                val jsonArray = org.json.JSONArray(queueJson)
                for (i in 0 until jsonArray.length()) {
                    queueList.add(jsonArray.getString(i))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("KEY_QUEUE_LIST", ArrayList(queueList))
        
        val tabUrls = ArrayList(tabsList.map { it.url })
        val tabTitles = ArrayList(tabsList.map { it.title })
        outState.putStringArrayList("KEY_TAB_URLS", tabUrls)
        outState.putStringArrayList("KEY_TAB_TITLES", tabTitles)
        
        val activeIndex = tabsList.indexOfFirst { it.id == activeTabId }
        outState.putInt("KEY_ACTIVE_INDEX", activeIndex)
    }
}
