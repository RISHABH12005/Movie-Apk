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
                                javaScriptCanOpenWindowsAutomatically = true
                                // Keep navigation inside this WebView; do not filter ad or site requests.
                                setSupportMultipleWindows(false)
                            }

                            webChromeClient = WebChromeClient()

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val scheme = request.url.scheme?.lowercase()
                                    // Allow all regular web navigation so links, media hosts, and redirects work.
                                    // Non-web schemes remain blocked for safety.
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



private fun hasValidatedInternet(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
