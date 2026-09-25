package com.ati.arena.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.ati.arena.R

/**
 * Hands links to other apps: browser, mail, dialer, store, intent: deep links, the
 * share sheet and the clipboard. Every launch is BROWSABLE-only with no explicit
 * component/selector (a page can't target an arbitrary, non-exported activity), and
 * a missing handler is a toast, never a crash.
 */
object ExternalLinks {

    /**
     * Open [uri] in whatever app handles it. For an intent: URI with no installed
     * handler, its http(s) `browser_fallback_url` is passed to [onFallback].
     */
    fun open(activity: Activity, uri: Uri, onFallback: (String) -> Unit = {}): Boolean {
        val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
            runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull()
                ?: return notHandled(activity)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        return try {
            activity.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (LinkPolicy.isWebUrl(fallback)) {
                onFallback(fallback!!)
                true
            } else {
                notHandled(activity)
            }
        } catch (_: SecurityException) {
            notHandled(activity)
        }
    }

    /** Open an http(s) URL in the user's browser. */
    fun openInBrowser(activity: Activity, url: String?): Boolean {
        if (!LinkPolicy.isWebUrl(url)) return notHandled(activity)
        return open(activity, Uri.parse(url))
    }

    fun copy(activity: Activity, url: String?) {
        if (!LinkPolicy.isWebUrl(url)) return
        val clipboard = activity.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.link_clip_label), url))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(activity, R.string.link_copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(activity: Activity, url: String?, title: String?) {
        if (!LinkPolicy.isWebUrl(url)) return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        if (!title.isNullOrBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title)
        try {
            activity.startActivity(Intent.createChooser(send, null))
        } catch (_: ActivityNotFoundException) {
            notHandled(activity)
        }
    }

    /** WebView download request: let the browser (download manager) take http(s) files. */
    fun download(activity: Activity, url: String?) {
        if (!LinkPolicy.isWebUrl(url)) {
            Toast.makeText(activity, R.string.download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (openInBrowser(activity, url)) {
            Toast.makeText(activity, R.string.download_handed_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun notHandled(activity: Activity): Boolean {
        Toast.makeText(activity, R.string.link_no_app, Toast.LENGTH_SHORT).show()
        return false
    }
}
