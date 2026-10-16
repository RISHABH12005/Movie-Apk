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

private const val HOME_URL = "https://bollyflix.vip/"

/**
 * WebView with conservative request-level ad filtering. It blocks known ad-network
 * hosts rather than hiding page elements, so normal site controls remain clickable.
 */
class MainActivity : ComponentActivity() {
    private var activeWebView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = activeWebView
                if (view != null && view.canGoBack()) view.goBack() else finish()
            }
        })

        setContent {
            MovieTheme {
                MovieBrowser(onWebViewReady = { activeWebView = it })
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
                                allowFileAccess = false
                                allowContentAccess = true
                                javaScriptCanOpenWindowsAutomatically = false
                                // Keep target=_blank navigation in this WebView instead of creating popups.
                                setSupportMultipleWindows(false)
                            }

                            webChromeClient = WebChromeClient()

                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): android.webkit.WebResourceResponse? {
                                    val host = request.url.host?.lowercase() ?: return null
                                    if (!isKnownAdHost(host)) return null
                                    return android.webkit.WebResourceResponse(
                                        "text/plain",
                                        "utf-8",
                                        204,
                                        "No Content",
                                        mapOf("Cache-Control" to "no-store"),
                                        java.io.ByteArrayInputStream(ByteArray(0))
                                    )
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val uri = request.url
                                    val scheme = uri.scheme?.lowercase()
                                    val host = uri.host?.lowercase()

                                    // The app always starts at bollyflix.vip. Allow HTTPS server
                                    // redirects so the page does not remain blank if the site
                                    // redirects its canonical host. Ordinary links to unrelated
                                    // hosts remain blocked; pop-up windows are separately rejected.
                                    if (scheme != "https" && scheme != "http") return true
                                    if (request.isRedirect) return false
                                    return host != "bollyflix.vip" && host != "www.bollyflix.vip"
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
                                    // No DOM overlays are hidden: broad overlay rules can cover real controls.
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

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    errorResponse: android.webkit.WebResourceResponse
                                ) {
                                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                                        pageLoading = false
                                        pageError = true
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView,
                                    handler: android.webkit.SslErrorHandler,
                                    error: android.net.http.SslError
                                ) {
                                    handler.cancel()
                                    pageLoading = false
                                    pageError = true
                                }
                            }

                            webViewRef = this
                            onWebViewReady(this)
                            loadUrl(HOME_URL)
                        }
                    },
                    update = { view -> webViewRef = view },
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
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Bollyflix VIP could not be loaded. The site may be offline or blocking access.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = {
                            pageError = false
                            pageLoading = true
                            webViewRef?.loadUrl(HOME_URL) ?: run { reloadKey++ }
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}


/** Match exact ad-network domains and their subdomains, never broad URL substrings. */
private val knownAdDomains = setOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "adservice.google.com",
    "popads.net",
    "popcash.net",
    "propellerads.com",
    "adsterra.com",
    "exoclick.com",
    "trafficjunky.net",
    "juicyads.com",
    "onclickads.net",
    "adcash.com",
    "hilltopads.net",
    "richads.com",
    "admaven.com",
    "monetag.com",
    "mgid.com",
    "adnxs.com",
    "taboola.com",
    "outbrain.com"
)

private fun isKnownAdHost(host: String): Boolean =
    knownAdDomains.any { domain -> host == domain || host.endsWith(".$domain") }

private fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
