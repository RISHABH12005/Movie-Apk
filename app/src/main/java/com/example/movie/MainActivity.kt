package com.example.movie

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.movie.ui.theme.MovieTheme

private const val PREFS_NAME = "movie_domain_settings"
private const val KEY_LAST_WORKING_DOMAIN = "last_working_domain"

/**
 * Domain names change independently of the Android app. Keep the candidates in
 * one place and let the user retry/select another candidate if one goes offline.
 * Only load HTTPS URLs; do not bypass browser security checks.
 */
private val DOMAIN_CANDIDATES = listOf(
    "https://new.bollyflix.vote/",
    "https://bollyflix.af/",
    "https://bollyflix.photo/"
)

class MainActivity : ComponentActivity() {
    private var activeWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = activeWebView
                if (view != null && view.canGoBack()) {
                    view.goBack()
                } else {
                    finish()
                }
            }
        })

        setContent {
            MovieTheme {
                MovieBrowser(
                    onWebViewReady = { activeWebView = it }
                )
            }
        }
    }

    override fun onDestroy() {
        activeWebView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        activeWebView = null
        super.onDestroy()
    }
}

@Composable
private fun MovieBrowser(onWebViewReady: (WebView) -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    val startUrl = remember {
        prefs.getString(KEY_LAST_WORKING_DOMAIN, null)
            ?.takeIf { candidate -> DOMAIN_CANDIDATES.contains(candidate) }
            ?: DOMAIN_CANDIDATES.first()
    }

    var currentUrl by remember { mutableStateOf(startUrl) }
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(reloadKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                loadsImagesAutomatically = true
                                useWideViewPort = true
                                loadWithOverviewMode = false
                                cacheMode = if (hasValidatedInternet(viewContext)) {
                                    WebSettings.LOAD_DEFAULT
                                } else {
                                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                                }
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                javaScriptCanOpenWindowsAutomatically = false
                                setSupportMultipleWindows(false)
                            }
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val scheme = request.url.scheme?.lowercase()
                                    // Keep web links in this app; block custom schemes.
                                    return scheme != "https" && scheme != "http"
                                }

                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    pageLoading = true
                                    pageError = false
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    pageLoading = false
                                    pageError = false
                                    if (url != null && DOMAIN_CANDIDATES.any { url.startsWith(it) }) {
                                        currentUrl = DOMAIN_CANDIDATES.firstOrNull {
                                            url.startsWith(it)
                                        } ?: currentUrl
                                        prefs.edit().putString(KEY_LAST_WORKING_DOMAIN, currentUrl).apply()
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError
                                ) {
                                    if (request.isForMainFrame) {
                                        pageLoading = false
                                        pageError = true
                                    }
                                }
                            }
                            webViewRef = this
                            onWebViewReady(this)
                            loadUrl(currentUrl)
                        }
                    },
                    update = { view ->
                        webViewRef = view
                    },
                    onRelease = { view ->
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.stopLoading()
                        view.destroy()
                    }
                )
            }

            if (pageLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            if (pageError) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "This domain may be unavailable, blocked, or offline.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Try another listed domain, or retry when your connection is available.",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            pageError = false
                            pageLoading = true
                            webViewRef?.loadUrl(currentUrl)
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("If the site changes domain, select a candidate below:", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
            ) {
                DOMAIN_CANDIDATES.forEach { domain ->
                    Button(
                        onClick = {
                            currentUrl = domain
                            prefs.edit().putString(KEY_LAST_WORKING_DOMAIN, domain).apply()
                            pageError = false
                            pageLoading = true
                            if (webViewRef != null) {
                                webViewRef?.loadUrl(domain)
                            } else {
                                reloadKey++
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(domain.removePrefix("https://").removeSuffix("/"), maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
