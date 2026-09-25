package com.ati.arena.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.ati.arena.R
import com.ati.arena.web.ExternalLinks
import com.ati.arena.web.LinkPolicy
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * In-app "new tab" for links opened from the Arena page (target=_blank,
 * window.open, or a tap on a link to another site).
 *
 * The tab is a separate WebView layered over the conversation, which keeps running
 * underneath. It shares cookies with Arena (links to Arena and sign-in pop-ups
 * work), but gets none of the page bridges or injected scripts. Back walks the
 * tab's own history, then closes it; ✕ closes it at once. Closing destroys the
 * WebView.
 */
class LinkTab(
    private val activity: AppCompatActivity,
    private val onOpenChanged: (open: Boolean) -> Unit,
) {
    private val layer: View = activity.findViewById(R.id.tab_layer)
    private val holder: FrameLayout = activity.findViewById(R.id.tab_web_holder)
    private val titleView: TextView = activity.findViewById(R.id.tab_title)
    private val hostView: TextView = activity.findViewById(R.id.tab_host)
    private val progress: LinearProgressIndicator = activity.findViewById(R.id.tab_progress)

    private var web: WebView? = null

    var isOpen: Boolean = false
        private set

    init {
        activity.findViewById<View>(R.id.tab_close).setOnClickListener { close() }
        activity.findViewById<View>(R.id.tab_reload).setOnClickListener { web?.reload() }
        activity.findViewById<View>(R.id.tab_more).setOnClickListener { showMenu(it) }
    }

    /**
     * The Arena page asked for a new window (WebChromeClient.onCreateWindow): give it a
     * fresh WebView through the transport, so window.opener and pop-up flows work.
     */
    fun acceptWindow(resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val view = newWebView() ?: return false
        transport.webView = view
        resultMsg.sendToTarget()
        show()
        return true
    }

    /** Open [url] in the tab (a same-window link to another site). */
    fun open(url: String) {
        val view = newWebView() ?: return
        showAddress(url)
        view.loadUrl(url)
        show()
    }

    /** Back press: tab history first, then close. Returns true when consumed. */
    fun handleBack(): Boolean {
        if (!isOpen) return false
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else close()
        return true
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        layer.animate().cancel()
        layer.animate()
            .alpha(0f)
            .translationY(slideDistance())
            .setDuration(CLOSE_MS)
            .withEndAction {
                if (!isOpen) {
                    layer.visibility = View.GONE
                    destroyWebView()
                }
            }
            .start()
        onOpenChanged(false)
    }

    fun onResume() {
        web?.onResume()
    }

    fun onPause() {
        web?.onPause()
    }

    fun release() {
        layer.animate().cancel()
        destroyWebView()
    }

    // ---------------------------------------------------------------- internals

    private fun show() {
        hideKeyboard()
        layer.animate().cancel()
        if (!isOpen) {
            layer.alpha = 0f
            layer.translationY = slideDistance()
        }
        layer.visibility = View.VISIBLE
        layer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(OPEN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        web?.requestFocus()
        if (!isOpen) {
            isOpen = true
            onOpenChanged(true)
        }
    }

    /** A fresh WebView replacing the current one (one tab at a time). */
    private fun newWebView(): WebView? {
        destroyWebView()
        val view = try {
            WebView(activity)
        } catch (e: RuntimeException) {
            // WebView provider missing or being updated.
            Toast.makeText(activity, R.string.tab_unavailable, Toast.LENGTH_SHORT).show()
            return null
        }
        configure(view)
        holder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = view
        titleView.text = activity.getString(R.string.tab_loading)
        hostView.text = ""
        setProgress(0)
        return view
    }

    private fun configure(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            // Links inside the tab stay in the tab.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                return when (LinkPolicy.routeTab(uri.scheme, request.hasGesture())) {
                    LinkPolicy.Route.IN_PLACE, LinkPolicy.Route.NEW_TAB -> false
                    LinkPolicy.Route.EXTERNAL_APP -> {
                        ExternalLinks.open(activity, uri) { fallback -> v.loadUrl(fallback) }
                        true
                    }
                    LinkPolicy.Route.BLOCK -> true
                }
            }

            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                if (v === web) showAddress(url)
            }

            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                if (v === web) showAddress(url)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, title: String?) {
                if (v === web && !title.isNullOrBlank()) titleView.text = title
            }

            override fun onProgressChanged(v: WebView, newProgress: Int) {
                if (v === web) setProgress(newProgress)
            }

            // window.close() from the page (e.g. a sign-in pop-up that is done).
            override fun onCloseWindow(window: WebView) {
                if (window === web) close()
            }
        }
        view.setDownloadListener { url, _, _, _, _ -> ExternalLinks.download(activity, url) }
    }

    private fun showAddress(url: String?) {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        hostView.text = host?.removePrefix("www.").orEmpty().ifEmpty { url.orEmpty() }
    }

    private fun setProgress(value: Int) {
        if (value in 0..99) {
            if (progress.visibility != View.VISIBLE) progress.visibility = View.VISIBLE
            progress.setProgressCompat(maxOf(value, MIN_VISIBLE_PROGRESS), true)
        } else {
            progress.visibility = View.INVISIBLE
        }
    }

    private fun showMenu(anchor: View) {
        val url = web?.url
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_BROWSER, 0, R.string.tab_open_browser)
        popup.menu.add(0, MENU_COPY, 1, R.string.tab_copy_link)
        popup.menu.add(0, MENU_SHARE, 2, R.string.tab_share)
        val isWeb = LinkPolicy.isWebUrl(url)
        for (i in 0 until popup.menu.size()) popup.menu.getItem(i).isEnabled = isWeb
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_BROWSER -> ExternalLinks.openInBrowser(activity, url)
                MENU_COPY -> ExternalLinks.copy(activity, url)
                MENU_SHARE -> ExternalLinks.share(activity, url, titleView.text?.toString())
            }
            true
        }
        popup.show()
    }

    private fun destroyWebView() {
        val view = web ?: return
        web = null
        view.stopLoading()
        view.webChromeClient = null
        holder.removeView(view)
        view.destroy()
    }

    private fun hideKeyboard() {
        val focused = activity.currentFocus ?: return
        activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(focused.windowToken, 0)
    }

    private fun slideDistance(): Float = SLIDE_DP * activity.resources.displayMetrics.density

    private companion object {
        const val OPEN_MS = 200L
        const val CLOSE_MS = 160L
        const val SLIDE_DP = 32f
        const val MIN_VISIBLE_PROGRESS = 8
        const val MENU_BROWSER = 1
        const val MENU_COPY = 2
        const val MENU_SHARE = 3
    }
}
