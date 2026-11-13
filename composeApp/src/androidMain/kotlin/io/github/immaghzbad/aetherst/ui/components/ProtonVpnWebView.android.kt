package io.github.immaghzbad.aetherst.shared.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.util.Base64
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.immaghzbad.aetherst.shared.data.LogRepository
import io.github.immaghzbad.aetherst.shared.i18n.LocalAppStrings
import io.github.immaghzbad.aetherst.shared.ui.theme.AppPalette
import java.net.URLDecoder

private class ProtonJavascriptBridge(private val onExtracted: (String) -> Unit) {
    @JavascriptInterface
    fun onConfigDownloaded(content: String) {
        if (content.contains("[Interface]") && content.contains("[Peer]")) {
            onExtracted(content)
        }
    }

    @JavascriptInterface
    fun onLog(msg: String) {
        LogRepository.i("[ProtonWebView] JS: $msg")
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ProtonVpnWebView(
    url: String,
    onConfigExtracted: (String) -> Unit,
    onClose: () -> Unit
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageProgress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    PlatformBackHandler {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            onClose()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppPalette.surfaceRaised)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top App Bar
                Surface(
                    color = AppPalette.surfaceRaised,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF6D4AFF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = null,
                                            tint = AppPalette.statusConnected,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = strings.PROTON_WEBVIEW_TITLE,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    Text(
                                        text = strings.PROTON_WEBVIEW_SUBTITLE,
                                        color = AppPalette.textSecondary,
                                        fontSize = 10.5.sp,
                                        maxLines = 1
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { webViewRef?.reload() }) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Reload",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                IconButton(onClick = onClose) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        // Progress Indicator
                        AnimatedVisibility(visible = isLoading && pageProgress < 100) {
                            LinearProgressIndicator(
                                progress = { pageProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(2.5.dp),
                                color = Color(0xFF6D4AFF),
                                trackColor = Color.Transparent
                            )
                        }
                    }
                }

                // WebView Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewRef = this
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                }

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                val bridge = ProtonJavascriptBridge { extractedConf ->
                                    (ctx as? Activity)?.runOnUiThread {
                                        onConfigExtracted(extractedConf)
                                    } ?: onConfigExtracted(extractedConf)
                                }
                                addJavascriptInterface(bridge, "ProtonBridge")

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        pageProgress = newProgress
                                        isLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        isLoading = true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isLoading = false
                                        // Inject hooks to intercept blob creation and downloads
                                        val injectScript = """
                                            (function() {
                                                if (window._protonBlobHooked) return;
                                                window._protonBlobHooked = true;

                                                function checkAndSend(txt) {
                                                    if (typeof txt === 'string' && txt.includes('[Interface]') && txt.includes('[Peer]')) {
                                                        if (window.ProtonBridge) window.ProtonBridge.onConfigDownloaded(txt);
                                                    }
                                                }

                                                // Intercept clicks on anchor tags
                                                document.addEventListener('click', function(e) {
                                                    var a = e.target.closest('a');
                                                    if (a && a.href) {
                                                        if (a.href.startsWith('blob:')) {
                                                            fetch(a.href).then(function(r){ return r.text(); }).then(checkAndSend).catch(function(){});
                                                        } else if (a.href.startsWith('data:')) {
                                                            var commaIdx = a.href.indexOf(',');
                                                            if (commaIdx !== -1) {
                                                                checkAndSend(decodeURIComponent(a.href.substring(commaIdx + 1)));
                                                            }
                                                        }
                                                    }
                                                }, true);

                                                // Intercept URL.createObjectURL for client-side generated .conf blobs
                                                var origCreate = URL.createObjectURL;
                                                URL.createObjectURL = function(blob) {
                                                    var bUrl = origCreate.apply(URL, arguments);
                                                    try {
                                                        if (blob && (blob.size < 65536)) {
                                                            var reader = new FileReader();
                                                            reader.onload = function() {
                                                                checkAndSend(reader.result);
                                                            };
                                                            reader.readAsText(blob);
                                                        }
                                                    } catch(e) {}
                                                    return bUrl;
                                                };
                                            })();
                                        """.trimIndent()
                                        view?.evaluateJavascript(injectScript, null)
                                    }

                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        return false
                                    }
                                }

                                setDownloadListener { downloadUrl, _, _, _, _ ->
                                    LogRepository.i("[ProtonWebView] DownloadListener triggered: $downloadUrl")
                                    if (downloadUrl.startsWith("blob:")) {
                                        val js = """
                                            (function() {
                                                fetch('$downloadUrl')
                                                    .then(function(r) { return r.text(); })
                                                    .then(function(txt) {
                                                        if (window.ProtonBridge) window.ProtonBridge.onConfigDownloaded(txt);
                                                    })
                                                    .catch(function(err) {
                                                        var xhr = new XMLHttpRequest();
                                                        xhr.open('GET', '$downloadUrl', true);
                                                        xhr.onload = function() {
                                                            if (window.ProtonBridge) window.ProtonBridge.onConfigDownloaded(xhr.responseText);
                                                        };
                                                        xhr.send();
                                                    });
                                            })();
                                        """.trimIndent()
                                        evaluateJavascript(js, null)
                                    } else if (downloadUrl.startsWith("data:")) {
                                        runCatching {
                                            val raw = if (downloadUrl.contains("base64,")) {
                                                val b64 = downloadUrl.substringAfter("base64,")
                                                Base64.decode(b64, Base64.DEFAULT).decodeToString()
                                            } else {
                                                URLDecoder.decode(downloadUrl.substringAfter(","), "UTF-8")
                                            }
                                            if (raw.contains("[Interface]") && raw.contains("[Peer]")) {
                                                (ctx as? Activity)?.runOnUiThread {
                                                    onConfigExtracted(raw)
                                                } ?: onConfigExtracted(raw)
                                            }
                                        }
                                    }
                                }

                                loadUrl(url)
                            }
                        }
                    )
                }
            }
        }
    }
}
