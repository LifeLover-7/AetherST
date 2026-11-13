package io.github.immaghzbad.aetherst.shared.ui.components

import androidx.compose.runtime.Composable

/**
 * Platform-aware Proton VPN WebView component.
 * On Android: Renders an embedded full-screen WebView intercepting blob: download URLs.
 * On Desktop: Launches system browser and provides clipboard / paste auto-detector.
 */
@Composable
expect fun ProtonVpnWebView(
    url: String = "https://account.protonvpn.com/downloads",
    onConfigExtracted: (String) -> Unit,
    onClose: () -> Unit
)
