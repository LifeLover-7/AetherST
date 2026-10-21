package io.github.immaghzbad.aetherst.shared.ui.screens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.immaghzbad.aetherst.shared.ui.theme.AppPalette
import io.github.immaghzbad.aetherst.shared.ui.components.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.immaghzbad.aetherst.platform.PlatformContext
import io.github.immaghzbad.aetherst.platform.getSettings
import io.github.immaghzbad.aetherst.platform.getSystemUtils
import io.github.immaghzbad.aetherst.platform.isDesktop
import io.github.immaghzbad.aetherst.platform.isWindows
import io.github.immaghzbad.aetherst.shared.data.IpInfo
import io.github.immaghzbad.aetherst.shared.data.PingState
import io.github.immaghzbad.aetherst.shared.data.PsiphonEgressRegistry
import io.github.immaghzbad.aetherst.shared.model.AetherConfig
import io.github.immaghzbad.aetherst.shared.model.AetherProtocol
import io.github.immaghzbad.aetherst.shared.model.ChainProvider
import io.github.immaghzbad.aetherst.shared.model.TorMode
import io.github.immaghzbad.aetherst.shared.model.ConnectionMode
import io.github.immaghzbad.aetherst.shared.model.PsiphonChainMode
import io.github.immaghzbad.aetherst.shared.model.ConnectionStatus
import io.github.immaghzbad.aetherst.shared.model.RoutingMode
import io.github.immaghzbad.aetherst.shared.model.RoutingRule
import io.github.immaghzbad.aetherst.shared.model.SessionTraffic
import io.github.immaghzbad.aetherst.shared.ui.components.CountryFlag
import io.github.immaghzbad.aetherst.shared.i18n.LocalAppStrings
import io.github.immaghzbad.aetherst.shared.i18n.StringsFa
import io.github.immaghzbad.aetherst.shared.util.CountryNames
import kotlinx.coroutines.launch

private val IosCardBg = AppPalette.surfaceRaised
private val IosGroupBg = AppPalette.divider
private val IosSecondaryLabel = AppPalette.textSecondary
private val IosActiveGreen = AppPalette.statusConnected
private val IosActiveBlue = AppPalette.accent
private val IosScanningAmber = AppPalette.statusScanning
private val IosErrorRed = AppPalette.statusError

@Composable
fun DashboardScreen(
    config: AetherConfig,
    connectionStatus: ConnectionStatus,
    elapsedSeconds: Long,
    sessionTraffic: SessionTraffic,
    ipInfo: IpInfo = IpInfo(),
    pingState: PingState = PingState(),
    appVersion: String = "1.0.0",
    onToggleVpn: () -> Unit,
    onForceStop: () -> Unit = {},
    onUpdateConfig: (AetherConfig) -> Unit = {},
    onUpdateProtocol: (AetherProtocol) -> Unit,
    onTogglePsiphon: (Boolean) -> Unit = {},
    onToggleTor: (Boolean) -> Unit = {},
    onRefreshIpInfo: () -> Unit = {},
    onRefreshPing: () -> Unit = {},
    onCopy: (String) -> Unit = {},
    onOpenSettingsToZeroTrust: () -> Unit = {},
    bottomContentPadding: Dp = 0.dp,
    platformContext: PlatformContext? = null,
    onSwipeDragging: (Boolean) -> Unit = {}
) {
    var showProxyOverlay by remember { mutableStateOf(true) }
    var showAdminRequiredDialog by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var supportDialogAuto by remember { mutableStateOf(true) }
    var showPsiphonSheet by remember { mutableStateOf(false) }
    var showTorSheet by remember { mutableStateOf(false) }
    var showBypassSheet by remember { mutableStateOf(false) }
    val strings = LocalAppStrings.current
    val uriHandler = LocalUriHandler.current
    val settings = platformContext?.let { getSettings(it) }

    LaunchedEffect(Unit) {
        if (settings != null && !settings.getBoolean("support_dialog_dismissed", false)) {
            supportDialogAuto = true
            showSupportDialog = true
        }
    }
    val systemUtils = platformContext?.let { getSystemUtils(it) }

    LaunchedEffect(connectionStatus) {
        if (connectionStatus != ConnectionStatus.RUNNING) {
            showProxyOverlay = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val screenWidth = this.maxWidth
        val screenHeight = this.maxHeight
        val baseScale = screenWidth.value / 411f
        val scaleFactor = if (isDesktop) (baseScale * 0.82f).coerceIn(0.65f, 0.90f) else baseScale.coerceIn(0.7f, 1.1f)
        val isCompactHeight = screenHeight < 640.dp
        val isVeryCompactHeight = screenHeight < 580.dp
        val horizontalPadding = when {
            isDesktop -> 12.dp
            screenWidth < 360.dp -> 12.dp
            else -> 16.dp
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    bottom = bottomContentPadding + 10.dp
                ),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.padding(top = if (isDesktop) 8.dp else 36.dp),
                verticalArrangement = Arrangement.spacedBy(if (isDesktop) (10 * scaleFactor).dp else (14 * scaleFactor).dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column {
                        Text(
                            text = strings.APP_TITLE,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = (26 * scaleFactor).sp,
                            lineHeight = (30 * scaleFactor).sp
                        )
                        Text(
                            text = if (config.connectionMode == ConnectionMode.TUNNEL) strings.SUBTITLE_TUNNEL else strings.SUBTITLE_PROXY,
                            style = MaterialTheme.typography.bodySmall,
                            color = IosSecondaryLabel,
                            fontSize = (12 * scaleFactor).sp,
                            lineHeight = (16 * scaleFactor).sp
                        )
                        if (config.protocol == AetherProtocol.ZERO_TRUST && connectionStatus == ConnectionStatus.RUNNING && config.teamName.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VerifiedUser, null, tint = IosActiveGreen, modifier = Modifier.size((14 * scaleFactor).dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = buildString {
                                        append(config.teamName)
                                        val who = config.accessEmail.ifBlank { config.accessId.ifBlank { config.accessToken.takeIf { it.isNotBlank() }?.let { "token" } } }
                                        if (!who.isNullOrBlank()) append(" • $who")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = IosActiveGreen,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = (12 * scaleFactor).sp
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = IosGroupBg,
                            modifier = Modifier.clickable {
                                supportDialogAuto = false
                                showSupportDialog = true
                            }
                        ) {
                            Text(
                                text = "v$appVersion",
                                modifier = Modifier.padding(horizontal = (12 * scaleFactor).dp, vertical = (6 * scaleFactor).dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = IosActiveBlue,
                                fontSize = (10 * scaleFactor).sp
                            )
                        }
                    }
                }

                IosStatusHeroCard(
                    connectionStatus = connectionStatus,
                    elapsedSeconds = elapsedSeconds,
                    sessionTraffic = sessionTraffic,
                    config = config,
                    ipInfo = ipInfo,
                    pingState = pingState,
                    onRefreshIpInfo = onRefreshIpInfo,
                    onRefreshPing = onRefreshPing,
                    onCopy = onCopy,
                    hideConfigChips = isCompactHeight,
                    scaleFactor = scaleFactor
                )

                // ردیف دکمه‌های میانبر اسپلیت تونل سایت‌ها
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showBypassSheet = true },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = IosCardBg)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(IosActiveBlue.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Language, null, tint = IosActiveBlue, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("اسپلیت تونل سایت‌ها (Bypass)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = (12 * scaleFactor).sp)
                                val directCount = config.routingRules.count { it.mode == RoutingMode.DIRECT }
                                val hasIr = config.routingRules.any { it.mode == RoutingMode.DIRECT && it.pattern.contains(".ir") }
                                val desc = if (hasIr) "سایت‌های ایرانی فعال + $directCount آدرس" else "$directCount سایت بدون فیلترشکن"
                                Text(desc, color = IosSecondaryLabel, fontSize = (10 * scaleFactor).sp)
                            }
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = IosSecondaryLabel, modifier = Modifier.size(16.dp))
                    }
                }

                if (isWindows && (connectionStatus == ConnectionStatus.RUNNING || connectionStatus == ConnectionStatus.TUN_ACTIVE)) {
                    WindowsProxyPortsCard(
                        config = config,
                        onCopy = onCopy,
                        scaleFactor = scaleFactor
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy((12 * scaleFactor).dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val isWindows = remember { try { System.getProperty("os.name")?.lowercase()?.contains("win") == true } catch (_: Throwable) { false } }
                    val isAndroid = remember { !isDesktop }
                    val handleToggle: () -> Boolean = {
                        if (connectionStatus == ConnectionStatus.STOPPING) {
                            onForceStop()
                            true
                        } else if (config.protocol == AetherProtocol.ZERO_TRUST && connectionStatus == ConnectionStatus.STOPPED) {
                            if (config.zeroTrustError() != null) {
                                onOpenSettingsToZeroTrust()
                                false
                            } else {
                                onToggleVpn()
                                true
                            }
                        } else if (isWindows && config.connectionMode == ConnectionMode.TUNNEL && systemUtils?.isAdministrator() == false) {
                            showAdminRequiredDialog = true
                            false
                        } else {
                            onToggleVpn()
                            true
                        }
                    }
                    if (config.connectButtonStyle == "capsule") {
                        CapsuleConnectButton(
                            connectionStatus = connectionStatus,
                            onToggle = handleToggle,
                            onRecover = onForceStop,
                            modifier = Modifier.fillMaxWidth(),
                            scaleFactor = scaleFactor
                        )
                    } else if ((isDesktop && isWindows) || isAndroid) {
                        WindowsSwipeSwitch(
                            connectionStatus = connectionStatus,
                            onToggle = handleToggle,
                            onRecover = onForceStop,
                            onAdminCancelResetKey = if (showAdminRequiredDialog) 1 else 0,
                            modifier = Modifier.fillMaxWidth(),
                            scaleFactor = scaleFactor,
                            onDraggingChanged = onSwipeDragging,
                            isSwipeMode = config.connectButtonStyle != "capsule"
                        )
                    } else {
                        val minDim = if (screenWidth < screenHeight) screenWidth else screenHeight
                        val buttonSize = (minDim * 0.28f).coerceIn(90.dp, 140.dp)
                        IosPowerButton(
                            connectionStatus = connectionStatus,
                            onToggle = { handleToggle().let {} },
                            onRecover = onForceStop,
                            size = buttonSize
                        )
                    }
                }

                if (!isVeryCompactHeight) {
                    if (config.chainProvider == ChainProvider.TOR) {
                        val torAllowed = config.protocol != AetherProtocol.ZERO_TRUST
                        val torOn = config.torEnabled && torAllowed
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = if (torOn) RoundedCornerShape(20.dp) else RoundedCornerShape(50.dp),
                            colors = CardDefaults.cardColors(containerColor = IosCardBg)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(AppPalette.accentVariant), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(strings.TOR_CHAIN, fontWeight = FontWeight.Bold, color = Color.White, fontSize = (13 * scaleFactor).sp)
                                            Text(if (!torAllowed) strings.TOR_NOT_AVAILABLE_ZT else strings.TOR_ROUTE_VIA, color = IosSecondaryLabel, fontSize = (10 * scaleFactor).sp)
                                        }
                                    }
                                    Switch(
                                        checked = config.torEnabled && torAllowed,
                                        onCheckedChange = { onToggleTor(it) },
                                        enabled = torAllowed && (connectionStatus == ConnectionStatus.STOPPED || connectionStatus == ConnectionStatus.ERROR),
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = IosActiveGreen, checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppPalette.inactiveTrack, uncheckedBorderColor = Color.Transparent, disabledCheckedTrackColor = IosActiveGreen.copy(alpha = 0.4f), disabledCheckedThumbColor = Color.White.copy(alpha = 0.9f), disabledCheckedBorderColor = Color.Transparent, disabledUncheckedTrackColor = AppPalette.inactiveTrack.copy(alpha = 0.6f), disabledUncheckedThumbColor = Color.White.copy(alpha = 0.7f), disabledUncheckedBorderColor = Color.Transparent)
                                    )
                                }
                                if (torOn) {
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp, modifier = Modifier.padding(start = 50.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { showTorSheet = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(AppPalette.accentVariant.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(strings.SHOW_MORE_TOR, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = (12 * scaleFactor).sp)
                                            Text(strings.SHOW_MORE_SUBTITLE, color = IosSecondaryLabel, fontSize = (10 * scaleFactor).sp)
                                        }
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = IosSecondaryLabel, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        val psiphonAllowed = config.protocol != AetherProtocol.ZERO_TRUST
                        val psiphonOn = config.psiphonEnabled && psiphonAllowed
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = if (psiphonOn) RoundedCornerShape(20.dp) else RoundedCornerShape(50.dp),
                            colors = CardDefaults.cardColors(containerColor = IosCardBg)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(AppPalette.accentVariant), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Shield, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(strings.PSIPHON_CHAIN, fontWeight = FontWeight.Bold, color = Color.White, fontSize = (13 * scaleFactor).sp)
                                            Text(if (!psiphonAllowed) strings.PSIPHON_NOT_AVAILABLE_ZT else if (config.psiphonEnabled) when (config.protocol) { AetherProtocol.MASQUE -> strings.PSIPHON_OVER_MASQUE ; AetherProtocol.WG -> strings.PSIPHON_OVER_WG ; AetherProtocol.GOOL -> strings.PSIPHON_OVER_GOOL ; AetherProtocol.ZERO_TRUST -> strings.PSIPHON_ROUTE_VIA } else strings.PSIPHON_ROUTE_VIA, color = IosSecondaryLabel, fontSize = (10 * scaleFactor).sp)
                                        }
                                    }
                                    Switch(
                                        checked = config.psiphonEnabled && psiphonAllowed,
                                        onCheckedChange = { onTogglePsiphon(it) },
                                        enabled = psiphonAllowed && (connectionStatus == ConnectionStatus.STOPPED || connectionStatus == ConnectionStatus.ERROR),
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = IosActiveGreen, checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppPalette.inactiveTrack, uncheckedBorderColor = Color.Transparent, disabledCheckedTrackColor = IosActiveGreen.copy(alpha = 0.4f), disabledCheckedThumbColor = Color.White.copy(alpha = 0.9f), disabledCheckedBorderColor = Color.Transparent, disabledUncheckedTrackColor = AppPalette.inactiveTrack.copy(alpha = 0.6f), disabledUncheckedThumbColor = Color.White.copy(alpha = 0.7f), disabledUncheckedBorderColor = Color.Transparent)
                                    )
                                }
                                if (psiphonOn) {
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp, modifier = Modifier.padding(start = 50.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { showPsiphonSheet = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(AppPalette.accentVariant.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Settings, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(strings.SHOW_MORE_PSIPHON, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = (12 * scaleFactor).sp)
                                            Text(strings.SHOW_MORE_SUBTITLE, color = IosSecondaryLabel, fontSize = (10 * scaleFactor).sp)
                                        }
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = IosSecondaryLabel, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (isDesktop && isWindows) {
                        IosConnectionModeSegmentedControl(
                            selectedMode = config.connectionMode,
                            onModeSelected = { onUpdateConfig(config.copy(connectionMode = it)) },
                            enabled = connectionStatus == ConnectionStatus.STOPPED || connectionStatus == ConnectionStatus.ERROR,
                            scaleFactor = scaleFactor
                        )
                    }
                    IosProtocolSegmentedControl(
                        selectedProtocol = config.protocol,
                        onProtocolSelected = onUpdateProtocol,
                        enabled = connectionStatus == ConnectionStatus.STOPPED || connectionStatus == ConnectionStatus.ERROR,
                        allowedProtocols = if (config.isPsiphonActive() || config.isTorActive()) setOf(AetherProtocol.MASQUE, AetherProtocol.WG, AetherProtocol.GOOL) else null,
                        scaleFactor = scaleFactor
                    )
                }
            }
        }

        // پاپ‌آپ بسیار ساده و کاربرپسند اسپلیت تونل سایت‌ها
        if (showBypassSheet) {
            WebsiteBypassDialog(
                config = config,
                onUpdateConfig = onUpdateConfig,
                onDismiss = { showBypassSheet = false },
                scaleFactor = scaleFactor
            )
        }

        if (showAdminRequiredDialog) {
            AdminRequiredDialog(
                onRelaunch = {
                    showAdminRequiredDialog = false
                    systemUtils?.relaunchAsAdmin()
                },
                onDismiss = { showAdminRequiredDialog = false },
                scaleFactor = scaleFactor
            )
        }

        if (showSupportDialog) {
            SupportDialog(
                autoShow = supportDialogAuto,
                onJoin = {
                    settings?.putBoolean("support_dialog_dismissed", true)
                    showSupportDialog = false
                    uriHandler.openUri(TelegramChannelUrl)
                },
                onSkip = {
                    settings?.putBoolean("support_dialog_dismissed", true)
                    showSupportDialog = false
                },
                onCancel = { showSupportDialog = false },
                scaleFactor = scaleFactor
            )
        }
        if (showPsiphonSheet) {
            PsiphonOptionsSheet(
                config = config,
                onUpdateConfig = onUpdateConfig,
                onDismiss = { showPsiphonSheet = false },
                scaleFactor = scaleFactor
            )
        }
        if (showTorSheet) {
            TorOptionsSheet(
                config = config,
                onUpdateConfig = onUpdateConfig,
                onDismiss = { showTorSheet = false },
                scaleFactor = scaleFactor
            )
        }
    }
}

/**
 * کامپوننت پاپ‌آپ اسپلیت تونل سایت‌ها (ورود ساده آدرس سایت + دور زدن خودکار تمام سایت‌های ایرانی)
 */
@Composable
fun WebsiteBypassDialog(
    config: AetherConfig,
    onUpdateConfig: (AetherConfig) -> Unit,
    onDismiss: () -> Unit,
    scaleFactor: Float = 1f
) {
    var newSiteText by remember { mutableStateOf("") }
    val directRules = remember(config.routingRules) {
        config.routingRules.filter { it.mode == RoutingMode.DIRECT }
    }
    val hasIranBypass = remember(directRules) {
        directRules.any { it.pattern.contains(".ir") }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = IosCardBg),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("اسپلیت تونل سایت‌ها", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                            Text("سایت‌هایی که بدون فیلترشکن باز می‌شوند", color = IosSecondaryLabel, fontSize = 11.sp)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, null, tint = IosSecondaryLabel, modifier = Modifier.size(18.dp))
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

                    // ۱. کلید طلایی: دور زدن تمام سایت‌های ایرانی (.ir)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(IosGroupBg)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("دور زدن تمام سایت‌های ایرانی (.ir)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                            Text("بانک‌ها، اسنپ، دیجی‌کالا و سایت‌های داخلی", color = IosSecondaryLabel, fontSize = 10.sp)
                        }
                        Switch(
                            checked = hasIranBypass,
                            onCheckedChange = { enabled ->
                                val updated = if (enabled) {
                                    config.routingRules.filterNot { it.pattern.contains(".ir") } +
                                            RoutingRule(pattern = "keyword:.ir", mode = RoutingMode.DIRECT)
                                } else {
                                    config.routingRules.filterNot { it.pattern.contains(".ir") }
                                }
                                onUpdateConfig(config.copy(routingRules = updated))
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = IosActiveGreen
                            )
                        )
                    }

                    // ۲. کادر ساده برای وارد کردن دستی آدرس سایت
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BasicTextField(
                            value = newSiteText,
                            onValueChange = { newSiteText = it.trim().lowercase() },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                            singleLine = true,
                            cursorBrush = SolidColor(IosActiveBlue),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (newSiteText.isNotEmpty()) {
                                    val clean = newSiteText.removePrefix("https://").removePrefix("http://").substringBefore('/')
                                    val updated = config.routingRules + RoutingRule(pattern = clean, mode = RoutingMode.DIRECT)
                                    onUpdateConfig(config.copy(routingRules = updated))
                                    newSiteText = ""
                                }
                            }),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (newSiteText.isEmpty()) {
                                        Text("مثال: snapp.ir یا divar.ir", color = IosSecondaryLabel, fontSize = 12.sp)
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Button(
                            onClick = {
                                if (newSiteText.isNotEmpty()) {
                                    val clean = newSiteText.removePrefix("https://").removePrefix("http://").substringBefore('/')
                                    val updated = config.routingRules + RoutingRule(pattern = clean, mode = RoutingMode.DIRECT)
                                    onUpdateConfig(config.copy(routingRules = updated))
                                    newSiteText = ""
                                }
                            },
                            modifier = Modifier.height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = IosActiveBlue)
                        ) {
                            Text("افزودن", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    // ۳. لیست سایت‌های ثبت شده با امکان حذف با یک کلیک
                    Text("سایت‌های در حال حاضر مستقیم:", color = IosSecondaryLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    val customDirectList = directRules.filterNot { it.pattern.contains(".ir") }
                    if (customDirectList.isEmpty() && !hasIranBypass) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("هیچ سایتی اضافه نشده است (همه از فیلترشکن رد می‌شوند)", color = IosSecondaryLabel, fontSize = 11.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            customDirectList.forEach { rule ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(rule.pattern, color = Color.White, fontSize = 12.sp)
                                    IconButton(
                                        onClick = {
                                            val updated = config.routingRules.filterNot { it == rule }
                                            onUpdateConfig(config.copy(routingRules = updated))
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.Close, null, tint = IosErrorRed, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IosGroupBg)
                    ) {
                        Text("بستن و ذخیره", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private const val TelegramChannelUrl = "https://t.me/PowerSigma"
