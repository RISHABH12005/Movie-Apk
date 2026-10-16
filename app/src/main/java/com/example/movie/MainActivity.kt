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
 * Single-domain WebView. New windows/popups are rejected and common overlay
 * ad popups are hidden on a best-effort basis. Ads embedded directly into the
 * site's own content cannot be guaranteed to be removed by a WebView wrapper.
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
                                // Ask WebView to route new-window requests through onCreateWindow.
                                setSupportMultipleWindows(true)
                            }

                            webChromeClient = object : WebChromeClient() {
                                // Reject target=_blank, window.open, and pop-under windows.
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?
                                ): Boolean = false
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val uri = request.url
                                    val host = uri.host?.lowercase()
                                    // Keep top-level navigation on the requested domain only.
                                    return uri.scheme != "https" ||
                                        (host != "bollyflix.vip" && host != "www.bollyflix.vip")
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
                                    // Block common popup overlays and scripts that try to open windows.
                                    view.evaluateJavascript(
                                        """
                                        (function() {
                                          if (window.__moviePopupGuard) return;
                                          window.__moviePopupGuard = true;
                                          try {
                                            window.open = function() { return null; };
                                            window.alert = function() {};
                                            window.onbeforeunload = null;
                                          } catch (e) {}
                                          var hideAdOverlays = function(root) {
                                            var nodes = (root || document).querySelectorAll
                                              ? (root || document).querySelectorAll('[id],[class]')
                                              : [];
                                            for (var i = 0; i < nodes.length; i++) {
                                              var el = nodes[i];
                                              var label = ((el.id || '') + ' ' +
                                                (typeof el.className === 'string' ? el.className : ''));
                                              if (!/(popup[-_ ]?ad|ad[-_ ]?popup|popunder|interstitial[-_ ]?ad|ad[-_ ]?overlay|overlay[-_ ]?ad)/i.test(label)) continue;
                                              var style = window.getComputedStyle(el);
                                              if ((style.position === 'fixed' || style.position === 'absolute') &&
                                                  parseInt(style.zIndex || '0', 10) >= 100) {
                                                el.style.setProperty('display', 'none', 'important');
                                                el.style.setProperty('visibility', 'hidden', 'important');
                                              }
                                            }
                                          };
                                          hideAdOverlays(document);
                                          try {
                                            new MutationObserver(function() { hideAdOverlays(document); })
                                              .observe(document.documentElement, {childList:true, subtree:true, attributes:true});
                                          } catch (e) {}
                                          document.addEventListener('click', function(event) {
                                            var link = event.target && event.target.closest
                                              ? event.target.closest('a[target="_blank"]') : null;
                                            if (link) {
                                              event.preventDefault();
                                              event.stopImmediatePropagation();
                                            }
                                          }, true);
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
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
