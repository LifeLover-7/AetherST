package io.github.immaghzbad.aetherst.shared.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.immaghzbad.aetherst.shared.ui.theme.AppPalette
import io.github.immaghzbad.aetherst.shared.ui.components.*

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.immaghzbad.aetherst.platform.*
import io.github.immaghzbad.aetherst.shared.data.IpInfo
import io.github.immaghzbad.aetherst.shared.data.PingState
import io.github.immaghzbad.aetherst.shared.data.PsiphonEgressRegistry
import io.github.immaghzbad.aetherst.shared.i18n.LocalAppStrings
import io.github.immaghzbad.aetherst.shared.i18n.StringsFa
import io.github.immaghzbad.aetherst.shared.model.*
import io.github.immaghzbad.aetherst.shared.ui.components.CountryFlag
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

                // دکمه میانبر برای اسپلیت تونل سایت‌ها
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
                                val desc = if (hasIr) "سایت‌های ایرانی فعال + $directCount آدرس مستقیم" else "$directCount سایت بدون فیلترشکن"
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
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = IosActiveGreen, checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppPalette.inactiveTrack, uncheckedBorderColor = Color.Transparent)
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
                                            Text(if (!psiphonAllowed) strings.PSIPHON_NOT_AVAILABLE_ZT else strings.PSIPHON_ROUTE_VIA, color = IosSecondaryLabel, fontSize = (10 * scaleFactor).sp)
                                        }
                                    }
                                    Switch(
                                        checked = config.psiphonEnabled && psiphonAllowed,
                                        onCheckedChange = { enabled ->
                                            // پیش‌فرض وایرگارد به عنوان پروتکل بیرونی اتصال سایفون
                                            val targetOuter = if (config.psiphonChainOuter.isBlank() || config.psiphonChainOuter == "masque") "wg" else config.psiphonChainOuter
                                            val targetProto = if (config.protocol == AetherProtocol.MASQUE) AetherProtocol.WG else config.protocol
                                            onUpdateConfig(
                                                config.copy(
                                                    psiphonEnabled = enabled,
                                                    chainProvider = ChainProvider.PSIPHON,
                                                    psiphonChainOuter = targetOuter,
                                                    protocol = targetProto
                                                )
                                            )
                                        },
                                        enabled = psiphonAllowed && (connectionStatus == ConnectionStatus.STOPPED || connectionStatus == ConnectionStatus.ERROR),
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = IosActiveGreen, checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White, uncheckedTrackColor = AppPalette.inactiveTrack, uncheckedBorderColor = Color.Transparent)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IosStatusHeroCard(
    connectionStatus: ConnectionStatus,
    elapsedSeconds: Long,
    sessionTraffic: SessionTraffic,
    config: AetherConfig,
    ipInfo: IpInfo = IpInfo(),
    pingState: PingState = PingState(),
    onRefreshIpInfo: () -> Unit = {},
    onRefreshPing: () -> Unit = {},
    onCopy: (String) -> Unit = {},
    hideConfigChips: Boolean = false,
    scaleFactor: Float = 1f
) {
    val strings = LocalAppStrings.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val statusColor by animateColorAsState(
        targetValue = when (connectionStatus) {
            ConnectionStatus.RUNNING, ConnectionStatus.TUN_ACTIVE -> IosActiveGreen
            ConnectionStatus.STARTING, ConnectionStatus.VALIDATING, ConnectionStatus.DATAPLANE_VALIDATED, ConnectionStatus.SOCKS_READY, ConnectionStatus.RECONNECTING, ConnectionStatus.STOPPING -> IosScanningAmber
            ConnectionStatus.ERROR, ConnectionStatus.FAILED -> IosErrorRed
            ConnectionStatus.STOPPED -> IosSecondaryLabel
        },
        label = "statusColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_hero_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = IosCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            statusColor.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
                .padding((14 * scaleFactor).dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size((7 * scaleFactor).dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width((5 * scaleFactor).dp))
                        Text(
                            text = when (connectionStatus) {
                                ConnectionStatus.RUNNING, ConnectionStatus.TUN_ACTIVE -> if (config.connectionMode == ConnectionMode.TUNNEL) strings.STATUS_PROTECTED_CONNECTED else strings.STATUS_PROXY_ACTIVE
                                ConnectionStatus.STARTING -> strings.STATUS_FINDING_SERVERS
                                ConnectionStatus.VALIDATING, ConnectionStatus.DATAPLANE_VALIDATED -> strings.STATUS_ESTABLISHING_LINK
                                ConnectionStatus.SOCKS_READY -> strings.STATUS_CONNECTING
                                ConnectionStatus.RECONNECTING -> strings.STATUS_RECONNECTING
                                ConnectionStatus.STOPPING -> strings.STATUS_SWIPE_FORCE_STOP
                                ConnectionStatus.ERROR, ConnectionStatus.FAILED -> strings.STATUS_CONNECTION_ERROR
                                ConnectionStatus.STOPPED -> strings.STATUS_READY_TO_CONNECT
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = statusColor,
                            fontSize = (8.5 * scaleFactor).sp
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = IosGroupBg
                    ) {
                        val protocolText = if (config.protocol == AetherProtocol.MASQUE) {
                            if (config.h2Mode) "MASQUE (H2)" else "MASQUE (H3)"
                        } else {
                            config.protocol.displayName
                        }
                        Text(
                            text = protocolText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = IosActiveBlue,
                            fontSize = (8.5 * scaleFactor).sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height((10 * scaleFactor).dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = formatTime(elapsedSeconds),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = (28 * scaleFactor).sp
                        )
                    }

                    if (connectionStatus == ConnectionStatus.RUNNING) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onRefreshPing() }
                                .padding(2.dp)
                        ) {
                            if (pingState.isPinging) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size((11 * scaleFactor).dp),
                                    color = IosActiveBlue,
                                    strokeWidth = 1.5.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Ping",
                                    tint = if (pingState.error != null) IosErrorRed else IosActiveBlue,
                                    modifier = Modifier.size((15 * scaleFactor).dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = when {
                                    pingState.isPinging -> "..."
                                    pingState.error != null -> "TIMEOUT"
                                    pingState.ms >= 0 -> "${pingState.ms}ms"
                                    else -> "PING"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (pingState.error != null) IosErrorRed else IosActiveBlue,
                                fontSize = (12 * scaleFactor).sp
                            )
                        }
                    } else {
                        Text(
                            text = if (connectionStatus == ConnectionStatus.RECONNECTING) strings.DASHBOARD_RETRY else strings.DASHBOARD_NO_UPLINK,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (connectionStatus == ConnectionStatus.RECONNECTING) IosScanningAmber else IosSecondaryLabel,
                            modifier = Modifier.clickable { onRefreshPing() },
                            fontSize = (10 * scaleFactor).sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height((8 * scaleFactor).dp))

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    if (connectionStatus == ConnectionStatus.RUNNING) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(IosGroupBg)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TrafficValue(
                                label = "UPLOAD",
                                value = formatTrafficBytes(sessionTraffic.uploadedBytes),
                                speed = sessionTraffic.uploadSpeedBps,
                                color = IosActiveBlue,
                                alignment = Alignment.Start,
                                modifier = Modifier.weight(1f),
                                scaleFactor = scaleFactor
                            )
                            TrafficValue(
                                label = "DOWNLOAD",
                                value = formatTrafficBytes(sessionTraffic.downloadedBytes),
                                speed = sessionTraffic.downloadSpeedBps,
                                color = IosActiveGreen,
                                alignment = Alignment.End,
                                modifier = Modifier.weight(1f),
                                scaleFactor = scaleFactor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height((8 * scaleFactor).dp))

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { onRefreshIpInfo() },
                                onLongClick = {
                                    if (ipInfo.ip.isNotEmpty()) {
                                        onCopy(ipInfo.ip)
                                    }
                                }
                            ),
                        color = IosGroupBg
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (ipInfo.countryCode.isNotEmpty()) {
                                    CountryFlag(
                                        countryCode = ipInfo.countryCode,
                                        size = (20 * scaleFactor).dp
                                    )
                                } else {
                                    Text(
                                        text = "🌐",
                                        fontSize = (16 * scaleFactor).sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = when {
                                            ipInfo.country.isNotEmpty() -> if (ipInfo.countryCode.isNotEmpty()) "${ipInfo.country} (${ipInfo.countryCode})" else ipInfo.country
                                            ipInfo.isLoading -> strings.DASHBOARD_IP_WAIT
                                            ipInfo.error != null -> strings.DASHBOARD_IP_ERROR
                                            else -> strings.DASHBOARD_IP_UNKNOWN
                                        },
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            textDirection = if (ipInfo.country.isNotEmpty()) TextDirection.Ltr else if (isRtl) TextDirection.Rtl else TextDirection.Ltr
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = (11 * scaleFactor).sp
                                    )
                                    Text(
                                        text = when {
                                            ipInfo.ip.isNotEmpty() -> ipInfo.ip
                                            ipInfo.isLoading -> strings.DASHBOARD_IP_LOCATING
                                            ipInfo.error != null -> strings.DASHBOARD_IP_NOT_FOUND
                                            else -> strings.DASHBOARD_IP_SHOW
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            textDirection = if (ipInfo.ip.isNotEmpty()) TextDirection.Ltr else if (isRtl) TextDirection.Rtl else TextDirection.Ltr
                                        ),
                                        color = when {
                                            ipInfo.error != null -> IosErrorRed
                                            ipInfo.isLoading -> IosScanningAmber
                                            else -> IosSecondaryLabel
                                        },
                                        fontSize = (9 * scaleFactor).sp
                                    )
                                }
                            }

                            if (ipInfo.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size((12 * scaleFactor).dp),
                                    color = IosActiveBlue,
                                    strokeWidth = 1.5.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = IosSecondaryLabel,
                                    modifier = Modifier.size((12 * scaleFactor).dp)
                                )
                            }
                        }
                    }
                }

                if (!hideConfigChips) {
                    Spacer(modifier = Modifier.height((10 * scaleFactor).dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(IosGroupBg)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        IosConfigChip(label = "BYPASS", value = config.noise.displayName.split(" ")[0], scaleFactor = scaleFactor)
                        IosConfigChip(label = "SPEED", value = config.scanMode.name.take(6), scaleFactor = scaleFactor)
                        IosConfigChip(label = "NETWORK", value = config.ipMode.rawValue, scaleFactor = scaleFactor)
                    }
                }
            }
        }
    }
}

@Composable
fun WindowsProxyPortsCard(
    config: AetherConfig,
    onCopy: (String) -> Unit,
    scaleFactor: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = IosCardBg)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = (12 * scaleFactor).dp, vertical = (10 * scaleFactor).dp),
            verticalArrangement = Arrangement.spacedBy((6 * scaleFactor).dp)
        ) {
            Text(
                text = "PROXY PORTS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = IosSecondaryLabel,
                fontSize = (8.5 * scaleFactor).sp
            )
            ProxyCopyRow(
                label = "Core Socks",
                address = "${config.socksHost}:${config.socksPort}",
                onCopy = { onCopy("${config.socksHost}:${config.socksPort}") },
                scaleFactor = scaleFactor
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
            ProxyCopyRow(
                label = "Core HTTP",
                address = "${config.socksHost}:${config.httpPort}",
                onCopy = { onCopy("${config.socksHost}:${config.httpPort}") },
                scaleFactor = scaleFactor
            )
        }
    }
}

@Composable
private fun ProxyCopyRow(
    label: String,
    address: String,
    onCopy: () -> Unit,
    scaleFactor: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCopy() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = "$label:",
                style = MaterialTheme.typography.labelSmall,
                color = IosActiveBlue,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (9 * scaleFactor).sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = address,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (12 * scaleFactor).sp,
                maxLines = 1
            )
        }
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = "Copy",
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size((14 * scaleFactor).dp)
        )
    }
}

@Composable
fun CapsuleConnectButton(
    connectionStatus: ConnectionStatus,
    onToggle: () -> Boolean,
    modifier: Modifier = Modifier,
    scaleFactor: Float = 1f,
    onRecover: () -> Unit = {}
) {
    val strings = LocalAppStrings.current
    val sf = scaleFactor.coerceIn(0.7f, 1.1f)
    val isConnected = connectionStatus == ConnectionStatus.RUNNING
    val isWorking = connectionStatus in setOf(
        ConnectionStatus.STARTING, ConnectionStatus.VALIDATING, ConnectionStatus.DATAPLANE_VALIDATED,
        ConnectionStatus.SOCKS_READY, ConnectionStatus.TUN_ACTIVE, ConnectionStatus.RECONNECTING, ConnectionStatus.STOPPING
    )
    val isError = connectionStatus == ConnectionStatus.ERROR
    val trackColor = when {
        isConnected -> IosActiveGreen
        isWorking -> IosScanningAmber
        isError -> IosErrorRed
        else -> IosGroupBg
    }
    val label = when {
        connectionStatus == ConnectionStatus.STOPPING -> strings.FORCE_STOP
        isWorking -> strings.CONNECTING_DOTS
        isConnected -> strings.DISCONNECT
        isError -> strings.RECONNECT
        else -> strings.CONNECT
    }
    Box(
        modifier = modifier
            .height((56 * sf).dp.coerceIn(48.dp, 64.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(trackColor)
            .clickable { if (connectionStatus == ConnectionStatus.STOPPING) onRecover() else onToggle() },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = (16 * sf).dp)
        ) {
            if (isWorking) {
                CircularProgressIndicator(modifier = Modifier.size((18 * sf).dp), color = Color.White, strokeWidth = 2.5.dp)
                Spacer(modifier = Modifier.width((8 * sf).dp))
            }
            CompositionLocalProvider(LocalLayoutDirection provides if (strings is StringsFa) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = ((if (strings is StringsFa) 13 else 14) * sf).coerceIn(11f, 15f).sp,
                    letterSpacing = (if (strings is StringsFa) 0.1f else 0.6f * sf).sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun WindowsSwipeSwitch(
    connectionStatus: ConnectionStatus,
    onToggle: () -> Boolean,
    modifier: Modifier = Modifier,
    scaleFactor: Float = 1f,
    onAdminCancelResetKey: Int = 0,
    onRecover: () -> Unit = {},
    onDraggingChanged: (Boolean) -> Unit = {},
    isSwipeMode: Boolean = true
) {
    val strings = LocalAppStrings.current
    val isConnected = connectionStatus == ConnectionStatus.RUNNING
    val isWorking = connectionStatus == ConnectionStatus.STARTING ||
            connectionStatus == ConnectionStatus.VALIDATING ||
            connectionStatus == ConnectionStatus.DATAPLANE_VALIDATED ||
            connectionStatus == ConnectionStatus.SOCKS_READY ||
            connectionStatus == ConnectionStatus.TUN_ACTIVE ||
            connectionStatus == ConnectionStatus.RECONNECTING ||
            connectionStatus == ConnectionStatus.STOPPING
    val isError = connectionStatus == ConnectionStatus.ERROR
    val canSwipe = true
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val latestOnToggle by rememberUpdatedState(onToggle)
    val latestOnRecover by rememberUpdatedState(onRecover)
    val latestConnectionStatus by rememberUpdatedState(connectionStatus)
    val latestIsConnected by rememberUpdatedState(isConnected)
    val latestIsWorking by rememberUpdatedState(isWorking)
    val trackColor by animateColorAsState(
        targetValue = when {
            isConnected -> IosActiveGreen
            isWorking -> IosScanningAmber
            isError -> IosErrorRed
            else -> IosGroupBg
        }, label = "trackColor"
    )
    val text = when (connectionStatus) {
        ConnectionStatus.STARTING -> strings.FINDING_SERVERS
        ConnectionStatus.VALIDATING -> strings.VALIDATING
        ConnectionStatus.DATAPLANE_VALIDATED, ConnectionStatus.SOCKS_READY, ConnectionStatus.TUN_ACTIVE -> strings.CONNECTING_DOTS
        ConnectionStatus.RECONNECTING -> strings.STATUS_RECONNECTING
        ConnectionStatus.STOPPING -> strings.STATUS_SWIPE_FORCE_STOP
        ConnectionStatus.RUNNING -> if (isSwipeMode) strings.SWIPE_TO_DISCONNECT else strings.TAP_TO_DISCONNECT
        ConnectionStatus.ERROR, ConnectionStatus.FAILED -> if (isSwipeMode) strings.SWIPE_TO_RECONNECT else strings.TAP_TO_RECONNECT
        ConnectionStatus.STOPPED -> if (isSwipeMode) strings.SWIPE_TO_CONNECT else strings.TAP_TO_CONNECT
    }
    val hintTransition = rememberInfiniteTransition(label = "hint")
    val hintShift by hintTransition.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "hintShift"
    )
    val dotTransition = rememberInfiniteTransition(label = "dots")
    val dotPhase by dotTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "dotPhase"
    )
    val sf = scaleFactor.coerceIn(0.7f, 1.1f)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = modifier
                .widthIn(min = (280 * sf).dp, max = (360 * sf).dp)
                .height((64 * sf).dp.coerceIn(52.dp, 72.dp))
                .shadow(12.dp, RoundedCornerShape(36.dp), spotColor = trackColor.copy(alpha = 0.3f))
                .clip(RoundedCornerShape(36.dp))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val thumbSize = (48 * sf).dp.coerceIn(42.dp, 56.dp)
            val thumbPx = with(LocalDensity.current) { thumbSize.toPx() }
            val horizontalPadding = (8 * sf).dp
            val paddingPx = with(LocalDensity.current) { horizontalPadding.toPx() }
            val maxDrag = (maxWidthPx - thumbPx - paddingPx * 2).coerceAtLeast(0f)
            val dragFraction = when {
                !canSwipe || maxDrag == 0f -> 0f
                isConnected -> (1f - offsetX.value / maxDrag).coerceIn(0f, 1f)
                isWorking -> (offsetX.value / maxDrag).coerceIn(0f, 1f)
                else -> 0f
            }
            val isDisconnectDrag = (isConnected || isWorking) && isDragging && dragFraction > 0.05f
            val effectiveTrackColor = if (isDisconnectDrag) lerp(trackColor, IosErrorRed, dragFraction) else trackColor

            LaunchedEffect(isConnected, isWorking, maxDrag) {
                if (isDragging) return@LaunchedEffect
                if (isWorking) {
                    offsetX.snapTo(if (isConnected) maxDrag else 0f)
                } else {
                    offsetX.animateTo(
                        targetValue = if (isConnected) maxDrag else 0f,
                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                    )
                }
            }
            LaunchedEffect(onAdminCancelResetKey) {
                if (!isConnected && !isWorking && offsetX.value != 0f && !isDragging) {
                    offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(36.dp))
                    .background(effectiveTrackColor.copy(alpha = if (isConnected || isDisconnectDrag) 1f else 0.95f)),
                contentAlignment = Alignment.Center
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides if (strings is StringsFa) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Text(
                        text = if (isDisconnectDrag) strings.RELEASE_TO_DISCONNECT else text,
                        color = Color.White.copy(alpha = 0.95f),
                        fontWeight = FontWeight.Bold,
                        fontSize = ((if (strings is StringsFa) 10 else 11) * sf).coerceIn(9f, 12f).sp,
                        letterSpacing = (if (strings is StringsFa) 0.15f else 0.6f * sf).sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = ((if (strings is StringsFa) 50 else 56) * sf).dp)
                    )
                }
            }

            val hintOffset = if (!isDragging && !isWorking) {
                if (!isConnected) hintShift else -hintShift
            } else 0f
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = horizontalPadding)
                    .offset { IntOffset((offsetX.value + hintOffset).toInt(), 0) }
                    .size(thumbSize)
                    .shadow(8.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Color.White)
                    .pointerInput(canSwipe, maxDrag) {
                        if (!canSwipe) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { isDragging = true; onDraggingChanged(true) },
                            onDragEnd = {
                                isDragging = false
                                onDraggingChanged(false)
                                scope.launch {
                                    val threshold = if (latestIsWorking) maxDrag * 0.25f else maxDrag * 0.5f
                                    val shouldTrigger = if (latestIsWorking) {
                                        if (!latestIsConnected) offsetX.value > threshold else offsetX.value < maxDrag - threshold
                                    } else {
                                        if (!latestIsConnected) offsetX.value > threshold else offsetX.value < threshold
                                    }
                                    if (shouldTrigger) {
                                        val success = if (latestConnectionStatus == ConnectionStatus.STOPPING) {
                                            latestOnRecover()
                                            true
                                        } else latestOnToggle()
                                        if (success) {
                                            if (latestIsWorking) {
                                                offsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                                            } else {
                                                offsetX.animateTo(
                                                    if (!latestIsConnected) maxDrag else 0f,
                                                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                                )
                                            }
                                        } else {
                                            offsetX.animateTo(
                                                if (latestIsConnected) maxDrag else 0f,
                                                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                            )
                                        }
                                    } else {
                                        offsetX.animateTo(
                                            if (latestIsConnected) maxDrag else 0f,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                onDraggingChanged(false)
                                scope.launch {
                                    offsetX.animateTo(
                                        if (latestIsConnected) maxDrag else 0f,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                    )
                                }
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    val next = (offsetX.value + dragAmount).coerceIn(0f, maxDrag)
                                    offsetX.snapTo(next)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isWorking && !isDragging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = effectiveTrackColor,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isConnected || isWorking) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = IosActiveBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IosPowerButton(
    connectionStatus: ConnectionStatus,
    onToggle: () -> Unit,
    onRecover: () -> Unit = {},
    size: Dp = 140.dp
) {
    val isConnected = connectionStatus == ConnectionStatus.RUNNING
    val isWorking = connectionStatus == ConnectionStatus.STARTING ||
            connectionStatus == ConnectionStatus.VALIDATING ||
            connectionStatus == ConnectionStatus.RECONNECTING ||
            connectionStatus == ConnectionStatus.STOPPING
    val isError = connectionStatus == ConnectionStatus.ERROR
    val canToggle = true

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scope = rememberCoroutineScope()

    val infiniteTransition = rememberInfiniteTransition(label = "refinedGlow")

    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isWorking) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathingScale"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else if (isWorking) breathingScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "buttonScale"
    )

    val cornerRadiusPercent by animateFloatAsState(
        targetValue = if (isConnected || isWorking) 0.28f else 0.5f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cornerRadius"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isConnected -> IosActiveGreen
            isWorking -> IosScanningAmber
            isError -> IosErrorRed
            else -> IosActiveBlue
        },
        animationSpec = tween(durationMillis = 600),
        label = "buttonColor"
    )

    Box(
        modifier = Modifier.size(size * 2.5f),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                }
                .shadow(
                    elevation = if (isPressed) 6.dp else 24.dp,
                    shape = RoundedCornerShape(size * cornerRadiusPercent),
                    ambientColor = buttonColor.copy(alpha = 0.6f),
                    spotColor = buttonColor
                )
                .clip(RoundedCornerShape(size * cornerRadiusPercent))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = canToggle,
                    onClick = {
                        scope.launch {
                            if (connectionStatus == ConnectionStatus.STOPPING) onRecover() else onToggle()
                        }
                    }
                ),
            color = buttonColor,
            tonalElevation = 14.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.45f)
                )
            }
        }
    }
}

@Composable
fun IosConnectionModeSegmentedControl(
    selectedMode: ConnectionMode,
    onModeSelected: (ConnectionMode) -> Unit,
    enabled: Boolean = true,
    scaleFactor: Float = 1f
) {
    val strings = LocalAppStrings.current
    val modes = listOf(
        ConnectionMode.TUNNEL to strings.TUN_MODE,
        ConnectionMode.SYSTEM_PROXY to strings.SYSTEM_PROXY,
        ConnectionMode.PROXY_ONLY to strings.PROXY_ONLY
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = IosCardBg,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCardBg)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            modes.forEach { (mode, label) ->
                val selected = mode == selectedMode
                val bg by animateColorAsState(
                    targetValue = if (selected) IosActiveBlue else Color.Transparent,
                    animationSpec = tween(250), label = "modeBg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) Color.White else IosSecondaryLabel,
                    animationSpec = tween(250), label = "modeText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((36 * scaleFactor).dp)
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .clickable(enabled = enabled) { onModeSelected(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = textColor,
                        fontSize = (10 * scaleFactor).sp,
                        letterSpacing = 0.3.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun IosProtocolSegmentedControl(
    selectedProtocol: AetherProtocol,
    onProtocolSelected: (AetherProtocol) -> Unit,
    enabled: Boolean = true,
    allowedProtocols: Set<AetherProtocol>? = null,
    scaleFactor: Float = 1f
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = IosCardBg,
        shadowElevation = 8.dp,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(IosCardBg)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AetherProtocol.entries.forEach { proto ->
                val selected = proto == selectedProtocol
                val itemEnabled = enabled && (allowedProtocols == null || proto in allowedProtocols)
                val bg by animateColorAsState(
                    targetValue = if (selected) IosActiveBlue else Color.Transparent,
                    animationSpec = tween(250), label = "protoBg"
                )
                val textColor by animateColorAsState(
                    targetValue = if (selected) Color.White else IosSecondaryLabel,
                    animationSpec = tween(250), label = "protoText"
                )
                val label = if (proto == AetherProtocol.ZERO_TRUST) "Z-TRUST" else proto.displayName.split(" ")[0].uppercase()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((36 * scaleFactor).dp)
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .clickable(enabled = itemEnabled) { onProtocolSelected(proto) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = textColor,
                        fontSize = (10 * scaleFactor).sp,
                        letterSpacing = 0.3.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun AdminRequiredDialog(
    onRelaunch: () -> Unit,
    onDismiss: () -> Unit,
    scaleFactor: Float = 1f
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val strings = LocalAppStrings.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = (24 * scaleFactor).dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = (340 * scaleFactor).dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AppPalette.surfaceRaised),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f))
            ) {
                Column(
                    modifier = Modifier.padding((24 * scaleFactor).dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = strings.ADMIN_REQUIRED,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = (20 * scaleFactor).sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height((12 * scaleFactor).dp))
                    Text(
                        text = strings.ADMIN_REQUIRED_DESC,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = (14 * scaleFactor).sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height((24 * scaleFactor).dp))
                    Button(
                        onClick = onRelaunch,
                        modifier = Modifier.fillMaxWidth().height((50 * scaleFactor).dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppPalette.accent)
                    ) {
                        Text(text = strings.RELAUNCH_AS_ADMIN, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun SupportDialog(
    autoShow: Boolean,
    onJoin: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
    scaleFactor: Float
) {
    Dialog(
        onDismissRequest = { if (autoShow) onSkip() else onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val strings = LocalAppStrings.current
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = IosCardBg),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(strings.SUPPORT_AETHERST, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(strings.SUPPORT_DIALOG_DESC, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = onJoin,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = IosActiveBlue)
                    ) {
                        Text(strings.JOIN_TELEGRAM, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PsiphonOptionsSheet(
    config: AetherConfig,
    onUpdateConfig: (AetherConfig) -> Unit,
    onDismiss: () -> Unit,
    scaleFactor: Float
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val strings = LocalAppStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = IosCardBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(strings.PSIPHON_OPTIONS_TITLE, fontWeight = FontWeight.Bold, fontSize = (18 * scaleFactor).sp, color = Color.White)

            // انتخاب پروتکل لایه بیرونی (پیش‌فرض WireGuard)
            val outerOptions = listOf("WireGuard", "MASQUE", "Gool")
            val outerValues = listOf("wg", "masque", "gool")
            val currentOuter = when (config.psiphonChainOuter) {
                "masque" -> "MASQUE"
                "gool" -> "Gool"
                else -> "WireGuard"
            }
            IosPickerRow(
                icon = Icons.Default.VpnLock,
                iconBg = AppPalette.statusConnected,
                title = strings.OUTER_PROTOCOL,
                value = currentOuter,
                options = outerOptions,
                onOptionSelected = { idx ->
                    val outer = outerValues[idx]
                    val proto = when (outer) {
                        "masque" -> AetherProtocol.MASQUE
                        "gool" -> AetherProtocol.GOOL
                        else -> AetherProtocol.WG
                    }
                    onUpdateConfig(config.copy(psiphonChainOuter = outer, protocol = proto))
                }
            )

            // انتخاب کشور خروجی سایفون
            val availableRegions by PsiphonEgressRegistry.availableRegions.collectAsStateWithLifecycle()
            val selectedRegion = config.psiphonEgressRegion.trim().uppercase()
            val regionCodes = buildList {
                add("")
                addAll(availableRegions)
                if (selectedRegion.isNotEmpty() && selectedRegion !in availableRegions) add(selectedRegion)
            }
            val regionOptions = regionCodes.map { CountryNames.label(it) }
            IosPickerRow(
                icon = Icons.Default.Public,
                iconBg = Color(0xFF30B0C7),
                title = strings.EXIT_COUNTRY,
                value = CountryNames.label(selectedRegion),
                options = regionOptions,
                onOptionSelected = { idx -> onUpdateConfig(config.copy(psiphonEgressRegion = regionCodes[idx])) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorOptionsSheet(
    config: AetherConfig,
    onUpdateConfig: (AetherConfig) -> Unit,
    onDismiss: () -> Unit,
    scaleFactor: Float
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val strings = LocalAppStrings.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = IosCardBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(strings.TOR_OPTIONS_TITLE, fontWeight = FontWeight.Bold, fontSize = (18 * scaleFactor).sp, color = Color.White)
        }
    }
}

private fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    fun pad(n: Long) = if (n < 10) "0$n" else n.toString()
    return "${pad(h)}:${pad(m)}:${pad(s)}"
}

private fun formatTrafficBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0).coerceAtMost(9_000_000_000_000_000L)
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = safeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = (value * 100).toLong() / 100.0
    return if (unitIndex == 0) {
        "$safeBytes ${units[unitIndex]}"
    } else {
        val formatted = if (roundedValue >= 100) "${roundedValue.toLong()}" else "$roundedValue"
        "$formatted ${units[unitIndex]}"
    }
}

@Composable
private fun TrafficValue(label: String, value: String, color: Color, alignment: Alignment.Horizontal, modifier: Modifier = Modifier, speed: Double = 0.0, scaleFactor: Float = 1f) {
    Column(modifier = modifier.heightIn(min = 38.dp), horizontalAlignment = alignment) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = IosSecondaryLabel, fontSize = (8 * scaleFactor).sp)
        Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color, fontSize = (12 * scaleFactor).sp)
    }
}

@Composable
fun IosConfigChip(label: String, value: String, scaleFactor: Float = 1f) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = IosSecondaryLabel, fontSize = (8 * scaleFactor).sp, fontWeight = FontWeight.Bold)
        Text(text = value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White, fontSize = (10 * scaleFactor).sp)
    }
}

private const val TelegramChannelUrl = "https://t.me/PowerSigma"
