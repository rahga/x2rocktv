package com.rahga.x2rock.ui.screens

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.rahga.x2rock.repository.SonosAuthRepository

@Composable
fun SonosAuthWebViewScreen(
    authUrl: String,
    onCodeReceived: (code: String, state: String) -> Unit,
    onCancel: () -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url
                        Log.d("SonosAuth", "shouldOverrideUrlLoading: $url")
                        val isCallback = url.toString().startsWith(SonosAuthRepository.REDIRECT_URI)
                            || (url.scheme == "x2rock" && url.host == "callback")
                        if (isCallback) {
                            val code = url.getQueryParameter("code")
                            val state = url.getQueryParameter("state")
                            if (code != null && state != null) {
                                onCodeReceived(code, state)
                            } else {
                                onCancel()
                            }
                            return true
                        }
                        return false
                    }
                }
                loadUrl(authUrl)
            }
        }
    )
}
