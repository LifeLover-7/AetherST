package io.github.immaghzbad.aetherst.shared.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.immaghzbad.aetherst.platform.isDesktop
import io.github.immaghzbad.aetherst.shared.core.NetworkUtils
import io.github.immaghzbad.aetherst.shared.model.*
import io.github.immaghzbad.aetherst.shared.ui.components.*
import io.github.immaghzbad.aetherst.shared.i18n.LocalAppStrings
import io.github.immaghzbad.aetherst.shared.ui.theme.AppPalette

private val IosCardBg get() = AppPalette.surfaceRaised
private val IosGroupBg get() = AppPalette.divider
private val IosSecondaryLabel get() = AppPalette.textSecondary
private val IosActiveBlue get() = AppPalette.accent
private val IosDividerColor get() = AppPalette.divider
private val IosActiveGreen get() = AppPalette.statusConnected

enum class SettingsPage(val title: String) {
    PRESETS("Configuration Profiles"),
    CONNECTION("Connection & Tunneling"),
    PROTOCOL("Protocol & Transport"),
    ZEROTRUST("Cloudflare Zero Trust"),
    NETWORK("Network Parameters"),
    SECURITY("Security & Reliability"),
    DIAGNOSTICS("Diagnostics & Core"),
    SYSTEM("System & Maintenance"),
    HEV_ENGINE("HEV Engine"),
    INTERFACE("User Interface")
}

@Composable
fun SettingsScreen(
    config: AetherConfig,
    isBatteryOptimized: Boolean,
    onUpdateConfig: (AetherConfig) -> Unit,
    onUpdateTunnelEngine: (TunnelEngine) -> Unit,
    onApplyPreset: (String) -> Unit,
    onOpenSplitTunneling: () -> Unit,
    onOpenRoutingRules: () -> Unit,
    onOpenAutoDetect: () -> Unit = {},
    onOpenDnsOptimizer: () -> Unit = {},
    onOpenSpeedTest: () -> Unit = {},
    onRequestBatteryOptimization: () -> Unit,
    onOpenVpnSettings: () -> Unit = {},
    onResetAll: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onOptimizeMtu: () -> Unit,
    isOptimizingMtu: Boolean = false,
    onShowToast: (String, Boolean) -> Unit = { _, _ -> },
    initialPage: SettingsPage? = null,
    onSubPageClosed: () -> Unit = {},
    currentPage: SettingsPage? = null,
    onPageChange: ((SettingsPage?) -> Unit)? = null,
    bottomContentPadding: Dp = 0.dp,
) {
    val strings = LocalAppStrings.current
    var internalPage by remember { mutableStateOf(initialPage) }
    val activePage = if (onPageChange != null) currentPage else internalPage
    val setPage: (SettingsPage?) -> Unit = { newPage ->
        if (onPageChange != null) onPageChange(newPage) else internalPage = newPage
        if (newPage == null) onSubPageClosed()
    }

    LaunchedEffect(initialPage) {
        if (initialPage != null) setPage(initialPage)
    }

    val isAndroid = remember { try { Class.forName("android.os.Build"); true } catch(_: Throwable) { false } }

    Crossfade(targetState = activePage, animationSpec = tween(220), label = "settingsPageTransition") { page ->
        if (page != null) {
            SettingsSubPage(
                page = page,
                config = config,
                isBatteryOptimized = isBatteryOptimized,
                onBack = { setPage(null) },
                onUpdateConfig = onUpdateConfig,
                onUpdateTunnelEngine = onUpdateTunnelEngine,
                onApplyPreset = onApplyPreset,
                onOpenSplitTunneling = onOpenSplitTunneling,
                onOpenRoutingRules = onOpenRoutingRules,
                onRequestBatteryOptimization = onRequestBatteryOptimization,
                onOpenVpnSettings = onOpenVpnSettings,
                onResetAll = onResetAll,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onOptimizeMtu = onOptimizeMtu,
                isOptimizingMtu = isOptimizingMtu,
                onShowToast = onShowToast,
                bottomContentPadding = bottomContentPadding,
                onOpenDnsOptimizer = onOpenDnsOptimizer
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().background(AppPalette.surfaceSunken),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomContentPadding + 12.dp, top = if (isDesktop) 12.dp else 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") {
                    Column(modifier = Modifier.padding(bottom = 4.dp)) {
                        Text(strings.SETTINGS_TITLE, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 26.sp, lineHeight = 30.sp)
                        Text(strings.SETTINGS_SUBTITLE, color = IosSecondaryLabel, fontSize = 12.sp)
                    }
                }
                item(key = "tools") {
                    IosGroupCard {
                        Column {
                            IosActionRow(icon = Icons.Default.Speed, iconBg = AppPalette.statusScanning, title = strings.SPEED_TEST_TITLE, subtitle = strings.SPEED_TEST_SUBTITLE, onClick = onOpenSpeedTest)
                            HorizontalDivider(color = IosDividerColor, thickness = 0.5.dp)
                            IosActionRow(icon = Icons.Default.Radar, iconBg = AppPalette.accent, title = strings.AUTO_DETECT_TITLE, subtitle = strings.AUTO_DETECT_SUBTITLE, onClick = onOpenAutoDetect)
                        }
                    }
                }
                item(key = "presets") { CategoryCard(icon = Icons.Default.Tune, iconBg = AppPalette.textSecondary, title = strings.CAT_CONFIGURATION_PROFILES, subtitle = strings.CAT_CONFIGURATION_PROFILES_SUB, onClick = { setPage(SettingsPage.PRESETS) }) }
                item(key = "connection") { CategoryCard(icon = Icons.Default.VpnLock, iconBg = AppPalette.statusConnected, title = strings.CAT_CONNECTION_TUNNELING, subtitle = strings.CAT_CONNECTION_TUNNELING_SUB, onClick = { setPage(SettingsPage.CONNECTION) }) }
                item(key = "protocol") { CategoryCard(icon = Icons.Default.Shield, iconBg = IosActiveBlue, title = strings.CAT_PROTOCOL_TRANSPORT, subtitle = strings.CAT_PROTOCOL_TRANSPORT_SUB, onClick = { setPage(SettingsPage.PROTOCOL) }) }
                if (config.protocol == AetherProtocol.ZERO_TRUST) {
                    item(key = "zt") { CategoryCard(icon = Icons.Default.Business, iconBg = AppPalette.accentVariant, title = strings.CAT_ZEROTRUST, subtitle = strings.CAT_ZEROTRUST_SUB, onClick = { setPage(SettingsPage.ZEROTRUST) }) }
                }
                item(key = "network") { CategoryCard(icon = Icons.Default.Language, iconBg = IosActiveBlue, title = strings.CAT_NETWORK_PARAMETERS, subtitle = strings.CAT_NETWORK_PARAMETERS_SUB, onClick = { setPage(SettingsPage.NETWORK) }) }
                item(key = "security") { CategoryCard(icon = Icons.Default.Lock, iconBg = AppPalette.statusError, title = strings.CAT_SECURITY_RELIABILITY, subtitle = strings.CAT_SECURITY_RELIABILITY_SUB, onClick = { setPage(SettingsPage.SECURITY) }) }
                item(key = "diagnostics") { CategoryCard(icon = Icons.Default.BugReport, iconBg = AppPalette.debugCyan, title = strings.CAT_DIAGNOSTICS_CORE, subtitle = strings.CAT_DIAGNOSTICS_CORE_SUB, onClick = { setPage(SettingsPage.DIAGNOSTICS) }) }
                if (isAndroid) {
                    item(key = "hev") { CategoryCard(icon = Icons.Default.Memory, iconBg = AppPalette.accentVariantAlt, title = strings.CAT_HEV_ENGINE, subtitle = strings.CAT_HEV_ENGINE_SUB, onClick = { setPage(SettingsPage.HEV_ENGINE) }) }
                }
                item(key = "system") { CategoryCard(icon = Icons.Default.Settings, iconBg = AppPalette.textSecondary, title = strings.CAT_SYSTEM_MAINTENANCE, subtitle = strings.CAT_SYSTEM_MAINTENANCE_SUB, onClick = { setPage(SettingsPage.SYSTEM) }) }
                item(key = "interface") { CategoryCard(icon = Icons.Default.PhoneAndroid, iconBg = AppPalette.accentVariant, title = strings.CAT_USER_INTERFACE, subtitle = strings.CAT_USER_INTERFACE_SUB, onClick = { setPage(SettingsPage.INTERFACE) }) }
                item(key = "footer") { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun CategoryCard(icon: ImageVector, iconBg: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = IosCardBg)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IosIconBadge(icon = icon, backgroundColor = iconBg)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp); Text(subtitle, color = IosSecondaryLabel, fontSize = 12.sp) }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = IosSecondaryLabel, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SettingsSubPage(page: SettingsPage, config: AetherConfig, isBatteryOptimized: Boolean, onBack: () -> Unit, onUpdateConfig: (AetherConfig) -> Unit, onUpdateTunnelEngine: (TunnelEngine) -> Unit, onApplyPreset: (String) -> Unit, onOpenSplitTunneling: () -> Unit, onOpenRoutingRules: () -> Unit, onRequestBatteryOptimization: () -> Unit, onOpenVpnSettings: () -> Unit, onResetAll: () -> Unit, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onOptimizeMtu: () -> Unit, isOptimizingMtu: Boolean, onShowToast: (String, Boolean) -> Unit, bottomContentPadding: Dp, onOpenDnsOptimizer: () -> Unit = {}) {
    var showResetDialog by remember { mutableStateOf(false) }
    var showAdvancedZt by remember { mutableStateOf(false) }
    val isAndroid = remember { try { Class.forName("android.os.Build"); true } catch(_: Throwable) { false } }
    val focusManager = LocalFocusManager.current

    PlatformBackHandler(enabled = true, onBack = onBack)

    Column(modifier = Modifier.fillMaxSize().background(AppPalette.surfaceSunken).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { focusManager.clearFocus() }) {
        val strings = LocalAppStrings.current
        Row(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = if (isDesktop) 12.dp else 36.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            Text(
                when (page) {
                    SettingsPage.PRESETS -> strings.CAT_CONFIGURATION_PROFILES
                    SettingsPage.CONNECTION -> strings.CAT_CONNECTION_TUNNELING
                    SettingsPage.PROTOCOL -> strings.CAT_PROTOCOL_TRANSPORT
                    SettingsPage.ZEROTRUST -> strings.CAT_ZEROTRUST
                    SettingsPage.NETWORK -> strings.CAT_NETWORK_PARAMETERS
                    SettingsPage.SECURITY -> strings.CAT_SECURITY_RELIABILITY
                    SettingsPage.DIAGNOSTICS -> strings.CAT_DIAGNOSTICS_CORE
                    SettingsPage.HEV_ENGINE -> strings.CAT_HEV_ENGINE
                    SettingsPage.SYSTEM -> strings.CAT_SYSTEM_MAINTENANCE
                    SettingsPage.INTERFACE -> strings.CAT_USER_INTERFACE
                }, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 20.sp
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomContentPadding + 12.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                SettingsPage.PRESETS -> item { PresetPage(config, onApplyPreset, onShowToast) }
                SettingsPage.CONNECTION -> item { ConnectionPage(config, isAndroid, onUpdateConfig, onUpdateTunnelEngine, onOpenSplitTunneling, onOpenRoutingRules) }
                SettingsPage.PROTOCOL -> item { ProtocolPage(config, onUpdateConfig, onOptimizeMtu, isOptimizingMtu) }
                SettingsPage.ZEROTRUST -> item { ZeroTrustPage(config, showAdvancedZt, onUpdateConfig) { showAdvancedZt = it } }
                SettingsPage.NETWORK -> item { NetworkPage(config, onUpdateConfig, onShowToast, onOpenDnsOptimizer) }
                SettingsPage.SECURITY -> item { SecurityPage(config, isAndroid, isBatteryOptimized, onUpdateConfig, onRequestBatteryOptimization) }
                SettingsPage.DIAGNOSTICS -> item { DiagnosticsPage(config, onUpdateConfig) }
                SettingsPage.HEV_ENGINE -> item { HevEnginePage(config, onUpdateConfig) }
                SettingsPage.SYSTEM -> item { SystemPage(isAndroid, onExportBackup, onImportBackup, onOpenVpnSettings) { showResetDialog = true } }
                SettingsPage.INTERFACE -> item { InterfacePage(config, onUpdateConfig) }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
    val strings = LocalAppStrings.current
    if (showResetDialog) IosConfirmationDialog(title = strings.SETTINGS_TITLE, message = strings.RESET_DEFAULTS_SUB, confirmText = strings.RESET_DEFAULTS, confirmColor = AppPalette.statusError, onConfirm = { onResetAll(); showResetDialog = false; onShowToast(strings.RESET_DEFAULTS_SUB, false) }, onDismiss = { showResetDialog = false })
}

@Composable private fun PresetPage(config: AetherConfig, onApplyPreset: (String) -> Unit, onShowToast: (String, Boolean) -> Unit) {
    val strings = LocalAppStrings.current
    IosGroupCard { Column {
        IosPresetItem(icon = Icons.Default.Bolt, iconBg = AppPalette.statusScanning, title = strings.PRESETS_TURBO, subtitle = strings.PRESETS_TURBO_SUB, isActive = config.presetId == "turbo", onClick = { onApplyPreset("turbo"); onShowToast(strings.PRESETS_TURBO, false) })
        Row(modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.background(AppPalette.statusScanning.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(strings.PRESET_CHIP_SPEED, color = AppPalette.statusScanning, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            Box(modifier = Modifier.background(IosGroupBg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("MTU 1320", color = IosSecondaryLabel, fontSize = 10.sp) }
            if (config.presetId == "turbo") Box(modifier = Modifier.background(Color(0xFFFFD700).copy(alpha = 0.2f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("Recommended", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
        }
        AppDivider(); IosPresetItem(icon = Icons.Default.Search, iconBg = AppPalette.accent, title = strings.PRESETS_THOROUGH, subtitle = strings.PRESETS_THOROUGH_SUB, isActive = config.presetId == "thorough", onClick = { onApplyPreset("thorough"); onShowToast(strings.PRESETS_THOROUGH, false) })
        Row(modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.background(AppPalette.accent.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(strings.PRESET_CHIP_STABLE, color = AppPalette.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            Box(modifier = Modifier.background(IosGroupBg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("MTU 1320", color = IosSecondaryLabel, fontSize = 10.sp) }
        }
        AppDivider(); IosPresetItem(icon = Icons.Default.VisibilityOff, iconBg = AppPalette.accentVariant, title = strings.PRESETS_STEALTH, subtitle = strings.PRESETS_STEALTH_SUB, isActive = config.presetId == "stealth", onClick = { onApplyPreset("stealth"); onShowToast(strings.PRESETS_STEALTH, false) })
        Row(modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.background(AppPalette.accentVariant.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(strings.PRESET_CHIP_STEALTH, color = AppPalette.accentVariant, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            Box(modifier = Modifier.background(IosGroupBg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("MTU 1330", color = IosSecondaryLabel, fontSize = 10.sp) }
        }
        AppDivider(); IosPresetItem(icon = Icons.Default.Shield, iconBg = IosActiveBlue, title = strings.PRESETS_IRONCLAD, subtitle = strings.PRESETS_IRONCLAD_SUB, isActive = config.presetId == "ironclad", onClick = { onApplyPreset("ironclad"); onShowToast(strings.PRESETS_IRONCLAD, false) })
        Row(modifier = Modifier.fillMaxWidth().padding(start = 58.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.background(AppPalette.statusError.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(strings.PRESET_CHIP_IRONCLAD, color = AppPalette.statusError, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            Box(modifier = Modifier.background(IosGroupBg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("MTU 1330", color = IosSecondaryLabel, fontSize = 10.sp) }
        }
        AppDivider(); IosPresetItem(icon = Icons.Default.Tune, iconBg = AppPalette.textSecondary, title = strings.PRESETS_CUSTOM, subtitle = strings.PRESETS_CUSTOM_SUB, isActive = config.presetId == "custom", onClick = { onApplyPreset("custom"); onShowToast(strings.PRESETS_CUSTOM, false) })
    } }
}

@Composable private fun ConnectionPage(
    config: AetherConfig,
    isAndroid: Boolean,
    onUpdateConfig: (AetherConfig) -> Unit,
    onUpdateTunnelEngine: (TunnelEngine) -> Unit,
    onOpenSplitTunneling: () -> Unit,
    onOpenRoutingRules: () -> Unit
) {
    val strings = LocalAppStrings.current
    IosGroupCard { Column {
        val opts = if (isAndroid) listOf(strings.TUN_MODE, strings.PROXY_ONLY) else listOf(strings.TUN_MODE_GLOBAL, strings.SYSTEM_PROXY, strings.PROXY_ONLY)
        IosPickerRow(
            icon = Icons.Default.VpnLock,
            iconBg = AppPalette.statusConnected,
            title = strings.CONNECTION_MODE,
            value = when (config.connectionMode) {
                ConnectionMode.TUNNEL -> if (isAndroid) strings.TUN_MODE else strings.TUN_MODE_GLOBAL
                ConnectionMode.SYSTEM_PROXY -> strings.SYSTEM_PROXY
                else -> strings.PROXY_ONLY
            },
            options = opts,
            onOptionSelected = {
                val m = if (isAndroid) {
                    if (it == 0) ConnectionMode.TUNNEL else ConnectionMode.PROXY_ONLY
                } else {
                    when (it) { 0 -> ConnectionMode.TUNNEL; 1 -> ConnectionMode.SYSTEM_PROXY; else -> ConnectionMode.PROXY_ONLY }
                }
                onUpdateConfig(config.copy(connectionMode = m))
            }
        )
        if (config.connectionMode == ConnectionMode.TUNNEL) {
            AppDivider()
            IosPickerRow(icon = Icons.Default.VpnLock, iconBg = AppPalette.accentVariant, title = strings.TUNNEL_ENGINE, value = config.tunnelEngine.displayName, options = TunnelEngine.entries.map { it.displayName }, onOptionSelected = { onUpdateTunnelEngine(TunnelEngine.entries[it]) })
            AppDivider()
            IosSwitchRow(icon = Icons.Default.AllInclusive, iconBg = IosActiveBlue, title = strings.TUNNEL_WHOLE_DEVICE, subtitle = strings.TUNNEL_WHOLE_DEVICE_SUB, checked = config.tunnelAllApps, onCheckedChange = { onUpdateConfig(config.copy(tunnelAllApps = it)) }, testTag = "switch_tunnel_all")
            AppDivider()
            // اصلاح نمایش تعداد برنامه‌های Split Tunneling بر اساس حالت کل دستگاه (excludedPackages) یا حالت انتخابی (tunneledPackages)
            val activeSplitCount = if (config.tunnelAllApps) {
                config.excludedPackages.size + config.blockedPackages.size
            } else {
                config.tunneledPackages.size + config.blockedPackages.size
            }
            IosPickerRow(icon = Icons.Default.Tune, iconBg = AppPalette.accentVariant, title = strings.SPLIT_TUNNELING, value = "$activeSplitCount ${strings.SPLIT_TUNNELING_CUSTOM}", options = emptyList(), onOptionSelected = {}, onClickOverride = onOpenSplitTunneling, enabled = true)
            AppDivider()
        }

        IosPickerRow(icon = Icons.AutoMirrored.Filled.AltRoute, iconBg = IosActiveBlue, title = strings.DOMAIN_IP_ROUTING, value = "${config.routingRules.size} ${strings.ROUTING_RULES_TITLE}", options = emptyList(), onOptionSelected = {}, onClickOverride = onOpenRoutingRules)

        AppDivider()
        val providerOptions = listOf("Psiphon", "Tor")
        val providerIdx = if (config.chainProvider == ChainProvider.TOR) 1 else 0
        IosPickerRow(icon = Icons.Default.Shield, iconBg = AppPalette.accentVariant, title = strings.CHAIN_PROVIDER, value = providerOptions[providerIdx], options = providerOptions, onOptionSelected = { idx ->
            val provider = if (idx == 1) ChainProvider.TOR else ChainProvider.PSIPHON
            onUpdateConfig(config.copy(chainProvider = provider, psiphonEnabled = if (provider == ChainProvider.TOR) false else config.psiphonEnabled, torEnabled = if (provider == ChainProvider.PSIPHON) false else config.torEnabled))
        })
        if (config.chainProvider == ChainProvider.PSIPHON) {
            AppDivider()
            IosSwitchRow(icon = Icons.Default.Shield, iconBg = IosActiveGreen, title = strings.PSIPHON_ONLY, subtitle = strings.PSIPHON_ONLY_SUB, checked = config.psiphonOnly, onCheckedChange = { onUpdateConfig(config.copy(psiphonOnly = it)) }, testTag = "switch_psiphon_only")
        }

        if (isAndroid) {
            AppDivider()
            IosSwitchRow(icon = Icons.Default.Share, iconBg = AppPalette.accentVariantAlt, title = strings.SHARE_HOTSPOT, subtitle = strings.SHARE_HOTSPOT_SUB, checked = config.shareHotspot, onCheckedChange = { onUpdateConfig(config.copy(shareHotspot = it)) }, testTag = "switch_share_hotspot")
            if (config.shareHotspot) HotspotInfo(config)
        }
    } }
}

@Composable private fun InterfacePage(config: AetherConfig, onUpdateConfig: (AetherConfig) -> Unit) {
    val strings = LocalAppStrings.current
    val isSwipeSelected = config.connectButtonStyle == "swipe_manual"
    val themeCodes = listOf("default", "glass", "emerald", "amethyst", "crimson")
    val themeNames = listOf(
        "تیره کلاسیک (Default Dark)",
        "شیشه‌ای مات (Frosted Glass)",
        "زمردی نئون (Emerald)",
        "بنفش سلطنتی (Amethyst)",
        "سرخ گیمینگ (Crimson)"
    )
    val currentThemeIdx = themeCodes.indexOf(AppPalette.currentTheme).coerceAtLeast(0)

    IosGroupCard {
        Column {
            // انتخابگر تم برنامه (با قابلیت تغییر آنی)
            IosPickerRow(
                icon = Icons.Default.Palette,
                iconBg = AppPalette.accentVariantAlt,
                title = "پوسته و ظاهر برنامه (Theme)",
                value = themeNames[currentThemeIdx],
                options = themeNames,
                onOptionSelected = { idx ->
                    val newTheme = themeCodes[idx]
                    AppPalette.currentTheme = newTheme
                    onUpdateConfig(config.copy(appTheme = newTheme))
                }
            )
            AppDivider()
            // پیش‌فرض روی کپسول ثابت (Capsule) قرار دارد
            IosPickerRow(
                icon = Icons.Default.TouchApp,
                iconBg = IosActiveBlue,
                title = strings.INTERFACE_CONNECT_BUTTON,
                value = if (isSwipeSelected) strings.CONNECT_BUTTON_SWIPE else strings.CONNECT_BUTTON_CAPSULE,
                options = listOf(strings.CONNECT_BUTTON_CAPSULE, strings.CONNECT_BUTTON_SWIPE),
                onOptionSelected = { idx ->
                    onUpdateConfig(config.copy(connectButtonStyle = if (idx == 0) "capsule" else "swipe_manual"))
                }
            )
            AppDivider()
            val langOptions = listOf(strings.LANGUAGE_AUTO, strings.LANGUAGE_ENGLISH, strings.LANGUAGE_PERSIAN)
            val langCodes = listOf("auto", "en", "fa")
            val currentLangIdx = langCodes.indexOf(config.appLanguage).coerceAtLeast(0)
            IosPickerRow(
                icon = Icons.Default.Language,
                iconBg = AppPalette.accentVariant,
                title = strings.APP_LANGUAGE,
                value = langOptions[currentLangIdx],
                options = langOptions,
                onOptionSelected = { idx -> onUpdateConfig(config.copy(appLanguage = langCodes[idx])) }
            )
        }
    }
}

@Composable private fun HotspotInfo(config: AetherConfig) {
    val strings = LocalAppStrings.current
    Column(modifier = Modifier.fillMaxWidth().background(IosGroupBg.copy(alpha = 0.4f)).padding(14.dp)) {
        var localIp by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { localIp = NetworkUtils.getLocalIpAddress() }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { Icon(if (localIp != null) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (localIp != null) IosActiveGreen else AppPalette.statusScanning, modifier = Modifier.size(16.dp)); Spacer(modifier = Modifier.width(8.dp)); Text(if (localIp != null) strings.HOTSPOT_ACTIVE else strings.HOTSPOT_INACTIVE, color = if (localIp != null) IosActiveGreen else AppPalette.statusScanning, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            IconButton(onClick = { localIp = NetworkUtils.getLocalIpAddress() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, null, tint = IosActiveBlue, modifier = Modifier.size(18.dp)) }
        }
        if (localIp != null) { Spacer(modifier = Modifier.height(10.dp)); Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.3f)) { Column(modifier = Modifier.padding(12.dp)) { Text(strings.PROXY_ADDRESS, color = IosSecondaryLabel, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp); Spacer(modifier = Modifier.height(6.dp)); Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("$localIp:${config.socksPort}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) } } } }
    }
}

@Composable private fun ProtocolPage(config: AetherConfig, onUpdateConfig: (AetherConfig) -> Unit, onOptimizeMtu: () -> Unit, isOptimizingMtu: Boolean) {
    val strings = LocalAppStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IosGroupCard { Column {
            IosPickerRow(icon = Icons.Default.VpnLock, iconBg = IosActiveBlue, title = strings.TRANSPORT_PROTOCOL, value = config.protocol.displayName, options = AetherProtocol.entries.map { it.displayName }, onOptionSelected = { onUpdateConfig(config.copy(protocol = AetherProtocol.entries[it])) })
            if (config.protocol == AetherProtocol.MASQUE) { AppDivider(); IosSwitchRow(icon = Icons.Default.Http, iconBg = IosActiveBlue, title = strings.HTTP2_FALLBACK, subtitle = strings.HTTP2_FALLBACK_SUB, checked = config.h2Mode, onCheckedChange = { onUpdateConfig(config.copy(h2Mode = it)) }, testTag = "switch_h2_mode"); AppDivider(); IosSwitchRow(icon = Icons.Default.VerticalSplit, iconBg = AppPalette.accentVariant, title = strings.PACKET_FRAGMENTATION, subtitle = strings.PACKET_FRAGMENTATION_SUB, checked = config.h2Fragment, onCheckedChange = { onUpdateConfig(config.copy(h2Fragment = it)) }, testTag = "switch_fragment"); if (config.h2Fragment) { IosInputFieldRow(icon = Icons.Default.Straighten, iconBg = IosSecondaryLabel, label = strings.FRAGMENT_SIZE, value = config.fragmentSize, onValueChange = { onUpdateConfig(config.copy(fragmentSize = it)) }, placeholder = "16-32", testTag = "fragment_size_input"); AppDivider(); IosInputFieldRow(icon = Icons.Default.Timer, iconBg = IosSecondaryLabel, label = strings.FRAGMENT_DELAY, value = config.fragmentDelay, onValueChange = { onUpdateConfig(config.copy(fragmentDelay = it)) }, placeholder = "2-10", testTag = "fragment_delay_input"); AppDivider() }; IosSwitchRow(icon = Icons.Default.EnhancedEncryption, iconBg = IosActiveGreen, title = strings.ECH, subtitle = strings.ECH_SUB, checked = config.echEnabled, onCheckedChange = { onUpdateConfig(config.copy(echEnabled = it)) }, testTag = "switch_ech_enabled"); AppDivider(); IosInputFieldRow(icon = Icons.Default.Straighten, iconBg = IosSecondaryLabel, label = strings.MASQUE_INNER_MTU, value = if (config.masqueMtu > 0) config.masqueMtu.toString() else "", onValueChange = { onUpdateConfig(config.copy(masqueMtu = it.toIntOrNull()?.coerceIn(0, 9000) ?: 0)) }, placeholder = "Auto", keyboardType = KeyboardType.Number, testTag = "masque_mtu_input"); AppDivider(); IosSwitchRow(icon = Icons.Default.SwapHoriz, iconBg = Color(0xFF30B0C7), title = strings.MIM_ENABLE, subtitle = strings.MIM_ENABLE_SUB, checked = config.mimEnabled, onCheckedChange = { onUpdateConfig(config.copy(mimEnabled = it)) }, testTag = "switch_mim_enabled"); if (config.mimEnabled) { AppDivider(); IosInputFieldRow(icon = Icons.AutoMirrored.Filled.Send, iconBg = AppPalette.accentVariant, label = strings.MIM_OUTER, value = config.mimOuter, onValueChange = { onUpdateConfig(config.copy(mimOuter = it.trim())) }, placeholder = "e.g. 162.159.192.1:443", testTag = "mim_outer_input"); AppDivider(); IosInputFieldRow(icon = Icons.AutoMirrored.Filled.Send, iconBg = AppPalette.accentVariant, label = strings.MIM_INNER, value = config.mimInner, onValueChange = { onUpdateConfig(config.copy(mimInner = it.trim())) }, placeholder = "e.g. 188.114.96.1:443", testTag = "mim_inner_input"); AppDivider(); IosSwitchRow(icon = Icons.Default.Refresh, iconBg = AppPalette.statusScanning, title = strings.MIM_SCAN, subtitle = null, checked = config.mimScan, onCheckedChange = { onUpdateConfig(config.copy(mimScan = it)) }, testTag = "switch_mim_scan"); AppDivider() }; IosSwitchRow(icon = Icons.Default.Speed, iconBg = AppPalette.statusScanning, title = strings.QUIC_V2_PROBE, subtitle = strings.QUIC_V2_PROBE_SUB, checked = config.quicV2Probe, onCheckedChange = { onUpdateConfig(config.copy(quicV2Probe = it)) }, testTag = "switch_quic_v2"); AppDivider(); IosInputFieldRow(icon = Icons.Default.Shield, iconBg = IosSecondaryLabel, label = strings.FIREWALL_MARK, value = config.firewallMark, onValueChange = { onUpdateConfig(config.copy(firewallMark = it.trim())) }, placeholder = "0xff", testTag = "firewall_mark_input"); AppDivider() }
            IosSwitchRow(icon = Icons.Default.DataUsage, iconBg = AppPalette.statusScanning, title = strings.DISABLE_DATA_VERIFICATION, subtitle = strings.DISABLE_DATA_VERIFICATION_SUB, checked = config.noDataCheck, onCheckedChange = { onUpdateConfig(config.copy(noDataCheck = it)) }, testTag = "switch_no_data_check"); AppDivider()
            val availNoise = if (config.protocol == AetherProtocol.MASQUE) listOf(AetherNoise.FIREWALL, AetherNoise.GFW, AetherNoise.OFF) else listOf(AetherNoise.BALANCED, AetherNoise.AGGRESSIVE, AetherNoise.LIGHT, AetherNoise.OFF)
            IosPickerRow(icon = Icons.Default.Tune, iconBg = AppPalette.accentVariantAlt, title = strings.BYPASS_OBFUSCATION, value = config.noise.displayName.substringBefore(" ("), options = availNoise.map { it.displayName }, onOptionSelected = { onUpdateConfig(config.copy(noise = availNoise[it])) }); AppDivider()
            IosPickerRow(icon = Icons.Default.NetworkCheck, iconBg = AppPalette.statusScanning, title = strings.SPEED_STRATEGY, value = config.scanMode.name.lowercase().replaceFirstChar { it.uppercase() }, options = AetherScanMode.entries.map { "${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }} (${it.description})" }, onOptionSelected = { onUpdateConfig(config.copy(scanMode = AetherScanMode.entries[it])) }); AppDivider()
            IosPickerRow(icon = Icons.AutoMirrored.Filled.AltRoute, iconBg = AppPalette.accentVariant, title = strings.NETWORK_STACK, value = config.ipMode.rawValue, options = AetherIpMode.entries.map { it.displayName }, onOptionSelected = { onUpdateConfig(config.copy(ipMode = AetherIpMode.entries[it])) }); AppDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) { Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { IosIconBadge(icon = Icons.Default.Tune, backgroundColor = IosActiveGreen); Spacer(modifier = Modifier.width(12.dp)); IosInputField(label = strings.CUSTOM_MTU, value = config.mtu.toString(), onValueChange = { onUpdateConfig(config.copy(mtu = it.toIntOrNull() ?: 1100)) }, modifier = Modifier.weight(1f), placeholder = "1100", keyboardType = KeyboardType.Number, testTag = "mtu_input") }; Spacer(modifier = Modifier.width(8.dp)); Button(onClick = onOptimizeMtu, enabled = !isOptimizingMtu, modifier = Modifier.height(46.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = IosActiveBlue.copy(alpha = 0.15f), contentColor = IosActiveBlue, disabledContainerColor = IosActiveBlue.copy(alpha = 0.05f), disabledContentColor = IosActiveBlue.copy(alpha = 0.3f)), contentPadding = PaddingValues(horizontal = 16.dp)) { if (isOptimizingMtu) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = IosActiveBlue, strokeWidth = 2.dp) else Text(strings.OPTIMIZE, fontSize = 13.sp, fontWeight = FontWeight.Bold) } }
        } }
        Text(strings.CLOAK_OBFUSCATION, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = IosSecondaryLabel, fontSize = 11.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(start = 8.dp, top = 4.dp))
        IosGroupCard { Column {
            IosSwitchRow(icon = Icons.Default.Security, iconBg = AppPalette.accentVariant, title = strings.CLOAK_DECOY, subtitle = if (config.cloakEnabled) strings.CLOAK_DECOY_ON else if (isDesktop) strings.CLOAK_DECOY_OFF_WIN else strings.CLOAK_DECOY_OFF_ANDROID, checked = config.cloakEnabled, onCheckedChange = { onUpdateConfig(config.copy(cloakEnabled = it)) }, testTag = "switch_cloak_enabled")
            if (config.cloakEnabled) {
                AppDivider()
                IosInputFieldRow(icon = Icons.Default.Public, iconBg = IosActiveGreen, label = strings.CLOAK_DECOY_SNI_LIST, value = config.cloakSniList, onValueChange = { onUpdateConfig(config.copy(cloakSniList = it)) }, placeholder = "www.bing.com,www.hcaptcha.com", testTag = "cloak_sni_input"); AppDivider()
                IosInputFieldRow(icon = Icons.Default.Timer, iconBg = AppPalette.statusScanning, label = strings.CLOAK_TTL_LIST, value = config.cloakTtlList, onValueChange = { onUpdateConfig(config.copy(cloakTtlList = it)) }, placeholder = "4,5,6,8", testTag = "cloak_ttl_input"); AppDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { IosIconBadge(icon = Icons.Default.Timer, backgroundColor = IosSecondaryLabel); Spacer(modifier = Modifier.width(12.dp)); IosInputField(label = strings.CLOAK_JITTER_MIN, value = config.cloakJitterMin.toString(), onValueChange = { onUpdateConfig(config.copy(cloakJitterMin = it.toIntOrNull() ?: 20)) }, placeholder = "20", keyboardType = KeyboardType.Number, testTag = "cloak_jitter_min") }
                    Spacer(modifier = Modifier.width(12.dp))
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { IosInputField(label = strings.CLOAK_JITTER_MAX, value = config.cloakJitterMax.toString(), onValueChange = { onUpdateConfig(config.copy(cloakJitterMax = it.toIntOrNull() ?: 80)) }, placeholder = "80", keyboardType = KeyboardType.Number, testTag = "cloak_jitter_max") }
                }; AppDivider()
                IosSwitchRow(icon = Icons.Default.VerticalSplit, iconBg = AppPalette.accentVariant, title = strings.CLOAK_FRAGMENT_REAL_HELLO, subtitle = strings.CLOAK_FRAGMENT_REAL_HELLO_SUB, checked = config.cloakFragment, onCheckedChange = { onUpdateConfig(config.copy(cloakFragment = it)) }, testTag = "switch_cloak_fragment"); AppDivider()
                IosSwitchRow(icon = Icons.Default.Refresh, iconBg = IosActiveGreen, title = strings.CLOAK_ADAPTIVE_STATS, subtitle = strings.CLOAK_ADAPTIVE_STATS_SUB, checked = config.cloakAdaptive, onCheckedChange = { onUpdateConfig(config.copy(cloakAdaptive = it)) }, testTag = "switch_cloak_adaptive"); AppDivider()
                IosSwitchRow(icon = Icons.Default.FontDownload, iconBg = IosSecondaryLabel, title = strings.CLOAK_RANDOMIZE_SNI_CASE, subtitle = strings.CLOAK_RANDOMIZE_SNI_CASE_SUB, checked = config.cloakRandomizeSniCase, onCheckedChange = { onUpdateConfig(config.copy(cloakRandomizeSniCase = it)) }, testTag = "switch_cloak_randomize"); AppDivider()
                IosInputFieldRow(icon = Icons.Default.Dns, iconBg = IosActiveBlue, label = strings.CLOAK_FALLBACK_PORTS, value = config.cloakFallbackPorts, onValueChange = { onUpdateConfig(config.copy(cloakFallbackPorts = it)) }, placeholder = "443,2053,2083,2087,2096,8443", testTag = "cloak_fallback_input"); AppDivider()
                IosPickerRow(icon = Icons.Default.BugReport, iconBg = IosSecondaryLabel, title = strings.CLOAK_LOG_LEVEL_TITLE, value = config.cloakLogLevel, options = listOf("error", "warn", "info", "debug"), onOptionSelected = { idx -> val lvl = listOf("error", "warn", "info", "debug")[idx]; onUpdateConfig(config.copy(cloakLogLevel = lvl)) })
            }
        } }
    }
}

@Composable private fun ZeroTrustPage(config: AetherConfig, showAdvanced: Boolean, onUpdateConfig: (AetherConfig) -> Unit, onToggleAdvanced: (Boolean) -> Unit) {
    val strings = LocalAppStrings.current
    val isZt = config.protocol == AetherProtocol.ZERO_TRUST
    val ztError = if (isZt) config.zeroTrustError() else null
    val hasAuth = config.teamName.isNotBlank() &&
        (config.accessEmail.isNotBlank() || config.accessId.isNotBlank() || config.accessSecret.isNotBlank() || config.accessToken.isNotBlank())
    IosGroupCard { Column {
        IosInputFieldRow(icon = Icons.Default.Business, iconBg = AppPalette.accentVariant, label = if (isZt) strings.ZT_TEAM_NAME_REQUIRED else strings.ZT_TEAM_NAME, value = config.teamName, onValueChange = { onUpdateConfig(config.copy(teamName = it)) }, placeholder = "e.g. my-org", testTag = "zt_team_input"); AppDivider()
        IosInputFieldRow(icon = Icons.Default.Language, iconBg = IosActiveBlue, label = strings.ZT_ACCESS_EMAIL, value = config.accessEmail, onValueChange = { onUpdateConfig(config.copy(accessEmail = it)) }, placeholder = "user@example.com", testTag = "zt_email_input"); AppDivider()
        IosSwitchRow(icon = Icons.Default.Shield, iconBg = IosActiveGreen, title = strings.ZT_GATEWAY, subtitle = strings.ZT_GATEWAY_SUB, checked = config.useGateway, onCheckedChange = { onUpdateConfig(config.copy(useGateway = it)) }, testTag = "switch_zt_gateway"); AppDivider()
        IosSwitchRow(icon = Icons.Default.CheckCircle, iconBg = IosActiveBlue, title = strings.ZT_STAY_SIGNED_IN, subtitle = strings.ZT_STAY_SIGNED_IN_SUB, checked = config.ztStaySignedIn, onCheckedChange = { onUpdateConfig(config.copy(ztStaySignedIn = it)) }, testTag = "switch_zt_stay_signed_in"); AppDivider()
        if (hasAuth) {
            Row(modifier = Modifier.fillMaxWidth().clickable { onUpdateConfig(config.copy(teamName = "", accessEmail = "", accessId = "", accessSecret = "", accessToken = "", ztTokenExpiry = 0, ztStaySignedIn = false)) }.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { IosIconBadge(icon = Icons.AutoMirrored.Filled.Logout, backgroundColor = AppPalette.statusError); Spacer(modifier = Modifier.width(12.dp)); Text(strings.ZT_SIGN_OUT, fontWeight = FontWeight.Medium, color = AppPalette.statusError, fontSize = 15.sp) } }
        }
        Row(modifier = Modifier.fillMaxWidth().clickable { onToggleAdvanced(!showAdvanced) }.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Row(verticalAlignment = Alignment.CenterVertically) { IosIconBadge(icon = Icons.Default.Lock, backgroundColor = IosSecondaryLabel); Spacer(modifier = Modifier.width(12.dp)); Text(strings.ZT_ADVANCED_AUTH, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 15.sp) }; Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = IosSecondaryLabel, modifier = Modifier.size(18.dp)) }
        AnimatedVisibility(visible = showAdvanced, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { Column(modifier = Modifier.fillMaxWidth().background(IosGroupBg.copy(alpha = 0.4f)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(strings.ZT_CHOOSE_ONE_METHOD, color = IosSecondaryLabel, fontSize = 12.sp); IosInputField(label = strings.ZT_ACCESS_ID, value = config.accessId, onValueChange = { onUpdateConfig(config.copy(accessId = it)) }, placeholder = "Required for Service Tokens", testTag = "zt_access_id"); IosInputField(label = strings.ZT_ACCESS_SECRET, value = config.accessSecret, onValueChange = { onUpdateConfig(config.copy(accessSecret = it)) }, placeholder = "Required for Service Tokens", testTag = "zt_access_secret"); IosInputField(label = strings.ZT_ACCESS_TOKEN, value = config.accessToken, onValueChange = { onUpdateConfig(config.copy(accessToken = it, ztTokenExpiry = config.parseJwtExpiry(it))) }, placeholder = "Existing token you already hold", testTag = "zt_access_token") } }
    } }
    if (ztError != null) {
        Text(text = ztError, color = AppPalette.statusError, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
    }
}

@Composable private fun NetworkPage(config: AetherConfig, onUpdateConfig: (AetherConfig) -> Unit, onShowToast: (String, Boolean) -> Unit = { _, _ -> }, onOpenDnsOptimizer: () -> Unit = {}) {
    val strings = LocalAppStrings.current
    val httpLocked = config.isPsiphonActive() || config.isTorActive()
    val httpLockToast = if (config.isTorActive()) strings.TOAST_DISABLE_TOR_FIRST else strings.TOAST_DISABLE_PSIPHON_FIRST
    val httpLockSub = if (config.isTorActive()) strings.INTERNAL_HTTP_PROXY_LOCKED_BY_TOR else strings.INTERNAL_HTTP_PROXY_LOCKED_BY_PSIPHON
    IosGroupCard { Column {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { IosIconBadge(icon = Icons.Default.Language, backgroundColor = IosActiveBlue); Spacer(modifier = Modifier.width(12.dp)); IosInputField(label = strings.SOCKS5_HOST, value = config.socksHost, onValueChange = { onUpdateConfig(config.copy(socksHost = it)) }, modifier = Modifier.weight(1f), placeholder = "127.0.0.1", testTag = "socks_host_input"); Spacer(modifier = Modifier.width(10.dp)); IosInputField(label = strings.SOCKS_PORT, value = config.socksPort, onValueChange = { onUpdateConfig(config.copy(socksPort = it)) }, modifier = Modifier.width(75.dp), placeholder = "1819", keyboardType = KeyboardType.Number, testTag = "socks_port_input"); Spacer(modifier = Modifier.width(8.dp)); IosInputField(label = strings.HTTP_PORT, value = config.httpPort, onValueChange = { onUpdateConfig(config.copy(httpPort = it)) }, modifier = Modifier.width(75.dp), placeholder = "1820", keyboardType = KeyboardType.Number, testTag = "http_port_input") }
        AppDivider(); Box(modifier = if (httpLocked) Modifier.fillMaxWidth().clickable { onShowToast(httpLockToast, true) } else Modifier.fillMaxWidth()) { IosSwitchRow(icon = Icons.Default.Http, iconBg = IosActiveBlue, title = strings.INTERNAL_HTTP_PROXY, subtitle = if (httpLocked) httpLockSub else strings.INTERNAL_HTTP_PROXY_SUB, checked = config.httpProxyEnabled, enabled = !httpLocked, onCheckedChange = { if (httpLocked && !it) { onShowToast(httpLockToast, true); return@IosSwitchRow }; onUpdateConfig(config.copy(httpProxyEnabled = it)) }, testTag = "switch_http_proxy_enabled") }; AppDivider()
        IosInputFieldRow(icon = Icons.Default.Code, iconBg = IosSecondaryLabel, label = strings.TLS_KEY_GROUPS, value = config.tlsGroups, onValueChange = { onUpdateConfig(config.copy(tlsGroups = it)) }, placeholder = "P-256:X25519:P-384", testTag = "tls_groups_input"); AppDivider()
        IosSwitchRow(icon = Icons.Default.Dns, iconBg = IosActiveBlue, title = strings.CUSTOM_DNS, subtitle = strings.CUSTOM_DNS_SUB, checked = config.dnsEnabled, onCheckedChange = { onUpdateConfig(config.copy(dnsEnabled = it, dnsList = if (it) config.dnsList.ifBlank { "1.1.1.1,1.0.0.1" } else config.dnsList)) }, testTag = "switch_custom_dns"); AppDivider()
        if (config.dnsEnabled) { Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) { Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { IosIconBadge(icon = Icons.Default.Dns, backgroundColor = IosActiveBlue); Spacer(modifier = Modifier.width(12.dp)); IosInputField(label = if (isDesktop) strings.TUNNEL_DNS_DESKTOP else strings.TUNNEL_DNS, value = config.dnsList, onValueChange = { onUpdateConfig(config.copy(dnsList = it.replace(Regex("\\s*,\\s*"), ","))) }, modifier = Modifier.weight(1f), placeholder = "1.1.1.1,1.0.0.1", testTag = "dns_list_input") }; Spacer(modifier = Modifier.width(8.dp)); Button(onClick = onOpenDnsOptimizer, modifier = Modifier.height(46.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = IosActiveBlue.copy(alpha = 0.15f), contentColor = IosActiveBlue), contentPadding = PaddingValues(horizontal = 16.dp)) { Text(strings.DNS_OPTIMIZE, fontSize = 13.sp, fontWeight = FontWeight.Bold) } }; AppDivider() }
        IosInputFieldRow(icon = Icons.AutoMirrored.Filled.AltRoute, iconBg = AppPalette.accentVariant, label = strings.FORCED_PEER_IP, value = config.peer, onValueChange = { onUpdateConfig(config.copy(peer = it)) }, placeholder = "e.g. 1.2.3.4:443", testTag = "peer_input"); AppDivider()
        if (config.protocol == AetherProtocol.WG || config.protocol == AetherProtocol.GOOL) {
            IosInputFieldRow(icon = Icons.AutoMirrored.Filled.AltRoute, iconBg = AppPalette.accentVariant, label = strings.WG_PEER, value = config.wgPeer, onValueChange = { onUpdateConfig(config.copy(wgPeer = it)) }, placeholder = "e.g. 162.159.192.1:2408", testTag = "wg_peer_input"); AppDivider()
        }
        if (config.protocol == AetherProtocol.GOOL) {
            IosInputFieldRow(icon = Icons.AutoMirrored.Filled.AltRoute, iconBg = AppPalette.accentVariant, label = strings.WIW_OUTER, value = config.wiwOuter, onValueChange = { onUpdateConfig(config.copy(wiwOuter = it)) }, placeholder = "e.g. 162.159.192.1:2408", testTag = "wiw_outer_input"); AppDivider()
            IosInputFieldRow(icon = Icons.AutoMirrored.Filled.AltRoute, iconBg = AppPalette.accentVariant, label = strings.WIW_INNER, value = config.wiwInner, onValueChange = { onUpdateConfig(config.copy(wiwInner = it)) }, placeholder = "e.g. 188.114.96.1:2408", testTag = "wiw_inner_input"); AppDivider()
            IosSwitchRow(icon = Icons.Default.Radar, iconBg = AppPalette.statusScanning, title = strings.WIW_SCAN, subtitle = strings.WIW_SCAN_SUB, checked = config.wiwScan, onCheckedChange = { onUpdateConfig(config.copy(wiwScan = it)) }, testTag = "switch_wiw_scan"); AppDivider()
        }
        if (config.protocol == AetherProtocol.WG || config.protocol == AetherProtocol.GOOL) {
            IosSwitchRow(icon = Icons.Default.Bolt, iconBg = AppPalette.statusScanning, title = strings.KEEPALIVE_PACKETS, subtitle = if (config.keepaliveEnabled) strings.KEEPALIVE_ON_SUB else strings.KEEPALIVE_OFF_SUB, checked = config.keepaliveEnabled, onCheckedChange = { onUpdateConfig(config.copy(keepaliveEnabled = it)) }, testTag = "switch_keepalive_enabled"); AppDivider()
            AnimatedVisibility(visible = config.keepaliveEnabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) { Column { IosInputFieldRow(icon = Icons.Default.Bolt, iconBg = AppPalette.statusScanning, label = strings.KEEPALIVE_INTERVAL, value = config.keepalive.toString(), onValueChange = { onUpdateConfig(config.copy(keepalive = it.toIntOrNull()?.coerceIn(1, 300) ?: 5)) }, placeholder = "5", keyboardType = KeyboardType.Number, testTag = "keepalive_input"); AppDivider() } }
        }
        IosInputFieldRow(icon = Icons.Default.Timer, iconBg = IosSecondaryLabel, label = strings.VALIDATION_INTERVAL, value = config.validateSecs.toString(), onValueChange = { onUpdateConfig(config.copy(validateSecs = it.toIntOrNull()?.coerceIn(1, 300) ?: 10)) }, placeholder = "10", keyboardType = KeyboardType.Number, testTag = "validate_secs_input"); AppDivider()
        IosInputFieldRow(icon = Icons.Default.Timer, iconBg = IosSecondaryLabel, label = "Reconnect Interval", value = config.reconnectSecs.toString(), onValueChange = { onUpdateConfig(config.copy(reconnectSecs = it.toIntOrNull()?.coerceIn(1, 300) ?: 2)) }, placeholder = "2", keyboardType = KeyboardType.Number, testTag = "reconnect_secs_input"); AppDivider()
        IosSwitchRow(icon = Icons.Default.Block, iconBg = AppPalette.statusError, title = "No Profile Retry", subtitle = "Disable retry with next profile on failure", checked = config.noProfileRetry, onCheckedChange = { onUpdateConfig(config.copy(noProfileRetry = it)) }, testTag = "switch_no_profile_retry")
    } }
}

@Composable private fun SecurityPage(config: AetherConfig, isAndroid: Boolean, isBatteryOptimized: Boolean, onUpdateConfig: (AetherConfig) -> Unit, onRequestBatteryOptimization: () -> Unit) {
    val strings = LocalAppStrings.current
    IosGroupCard { Column {
        IosSwitchRow(icon = Icons.Default.VpnLock, iconBg = AppPalette.accentVariant, title = strings.STRICT_KILL_SWITCH, subtitle = strings.STRICT_KILL_SWITCH_SUB, checked = config.strictKillSwitch, onCheckedChange = { onUpdateConfig(config.copy(strictKillSwitch = it)) }, testTag = "switch_strict_kill_switch"); AppDivider()
        IosSwitchRow(icon = Icons.Default.Lock, iconBg = AppPalette.statusError, title = strings.KILL_SWITCH, subtitle = strings.KILL_SWITCH_SUB, checked = config.killSwitch, onCheckedChange = { onUpdateConfig(config.copy(killSwitch = it)) }, testTag = "switch_kill_switch"); AppDivider()
        IosSwitchRow(icon = Icons.Default.Security, iconBg = AppPalette.accentVariant, title = strings.IPV6_LEAK, subtitle = strings.IPV6_LEAK_SUB, checked = config.ipv6Leak, onCheckedChange = { onUpdateConfig(config.copy(ipv6Leak = it)) }, testTag = "switch_ipv6_leak"); AppDivider()
        IosSwitchRow(icon = Icons.Default.Restore, iconBg = IosActiveGreen, title = strings.SMART_RECONNECT, subtitle = strings.SMART_RECONNECT_SUB, checked = config.smartReconnect, onCheckedChange = { onUpdateConfig(config.copy(smartReconnect = it)) }, testTag = "switch_smart_reconnect")
        if (config.smartReconnect) { AppDivider(); Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { IosIconBadge(icon = Icons.Default.Repeat, backgroundColor = IosSecondaryLabel); Spacer(modifier = Modifier.width(12.dp)); IosInputField(label = strings.MAX_RETRIES, value = config.reconnectRetryLimit.toString(), onValueChange = { onUpdateConfig(config.copy(reconnectRetryLimit = it.toIntOrNull() ?: 10)) }, placeholder = "10", keyboardType = KeyboardType.Number, testTag = "reconnect_limit_input") }; Spacer(modifier = Modifier.width(12.dp)); Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { IosInputField(label = strings.DELAY_SECS, value = config.reconnectSecs.toString(), onValueChange = { onUpdateConfig(config.copy(reconnectSecs = it.toIntOrNull() ?: 2)) }, placeholder = "2", keyboardType = KeyboardType.Number, testTag = "reconnect_secs_input") } } }
        AppDivider(); IosSwitchRow(icon = Icons.Default.Sync, iconBg = IosActiveGreen, title = strings.REPROVISION, subtitle = strings.REPROVISION_SUB, checked = config.reprovision, onCheckedChange = { onUpdateConfig(config.copy(reprovision = it)) }, testTag = "switch_reprovision")
        if (isAndroid) { AppDivider(); IosSwitchRow(icon = Icons.Default.BatteryAlert, iconBg = AppPalette.statusError, title = strings.BATTERY_OPTIMIZATION, subtitle = strings.BATTERY_OPTIMIZATION_SUB, checked = isBatteryOptimized, enabled = !isBatteryOptimized, onCheckedChange = { if (it) onRequestBatteryOptimization() }, testTag = "switch_battery_opt") }
    } }
}

@Composable private fun DiagnosticsPage(config: AetherConfig, onUpdateConfig: (AetherConfig) -> Unit) {
    val strings = LocalAppStrings.current
    IosGroupCard { Column {
        IosInputFieldRow(icon = Icons.Default.Speed, iconBg = AppPalette.statusScanning, label = strings.PING_URL_LABEL, value = config.pingUrl, onValueChange = { onUpdateConfig(config.copy(pingUrl = it)) }, placeholder = "https://www.gstatic.com/generate_204", testTag = "ping_url_input"); AppDivider()
        IosPickerRow(icon = Icons.Default.Public, iconBg = IosActiveBlue, title = strings.IP_INFO_PROVIDER_LABEL, value = config.ipInfoProvider.displayName, options = IpInfoProvider.entries.map { it.displayName }, onOptionSelected = { onUpdateConfig(config.copy(ipInfoProvider = IpInfoProvider.entries[it])) }); AppDivider()
        val logLevelLocalized = mapOf("off" to strings.LOG_LEVEL_OFF, "error" to strings.LOG_LEVEL_ERROR, "warn" to strings.LOG_LEVEL_WARN, "info" to strings.LOG_LEVEL_INFO, "debug" to strings.LOG_LEVEL_DEBUG)
        IosPickerRow(icon = Icons.Default.BugReport, iconBg = AppPalette.debugCyan, title = strings.APP_SYSTEM_LOGGING, value = logLevelLocalized[config.appLogLevel.rawValue] ?: config.appLogLevel.displayName.substringBefore(" ("), options = logLevelLocalized.values.toList(), onOptionSelected = { idx -> val key = logLevelLocalized.keys.toList()[idx]; val level = AetherLogLevel.entries.find { it.rawValue == key } ?: AetherLogLevel.INFO; onUpdateConfig(config.copy(appLogLevel = level)) }); AppDivider()
        IosPickerRow(icon = Icons.Default.VpnLock, iconBg = IosSecondaryLabel, title = strings.AETHER_CORE_LOGGING, value = logLevelLocalized[config.coreLogLevel.rawValue] ?: config.coreLogLevel.displayName.substringBefore(" ("), options = logLevelLocalized.values.toList(), onOptionSelected = { idx -> val key = logLevelLocalized.keys.toList()[idx]; val level = AetherLogLevel.entries.find { it.rawValue == key } ?: AetherLogLevel.INFO; onUpdateConfig(config.copy(coreLogLevel = level)) }); AppDivider()
        IosPickerRow(icon = Icons.Default.Speed, iconBg = IosActiveGreen, title = strings.PERFORMANCE_PROFILE, value = config.perfProfile.displayName, options = AetherPerfProfile.entries.map { it.displayName }, onOptionSelected = { onUpdateConfig(config.copy(perfProfile = AetherPerfProfile.entries[it])) }); AppDivider()
        IosSwitchRow(icon = Icons.AutoMirrored.Filled.Rule, iconBg = IosActiveBlue, title = strings.DOMAIN_SNIFFING, subtitle = strings.DOMAIN_SNIFFING_SUB, checked = config.routeSniffing, onCheckedChange = { onUpdateConfig(config.copy(routeSniffing = it)) }, testTag = "switch_route_sniffing")
        AppDivider(); IosSwitchRow(icon = Icons.Default.Restore, iconBg = IosActiveGreen, title = strings.QUICK_RECONNECT_STRATEGY, subtitle = strings.QUICK_RECONNECT_STRATEGY_SUB, checked = config.quickReconnect, onCheckedChange = { onUpdateConfig(config.copy(quickReconnect = it)) }, testTag = "switch_quick_reconnect")
    } }
}

@Composable private fun HevEnginePage(config: AetherConfig, onUpdateConfig: (AetherConfig) -> Unit) {
    val strings = LocalAppStrings.current
    val hevLevels = listOf("error", "warn", "info", "debug")
    val levelLabels = mapOf("error" to strings.LOG_LEVEL_ERROR, "warn" to strings.HEV_LOG_WARN_DEFAULT, "info" to strings.HEV_LOG_INFO, "debug" to strings.HEV_LOG_DEBUG)
    val currentLevel = if (config.hevLogLevel in hevLevels) config.hevLogLevel else "warn"
    val hevUdpOptions = listOf("udp", "tcp", "off")
    val hevUdpLabels = mapOf("udp" to strings.HEV_UDP_ASSOCIATE, "tcp" to strings.HEV_UDP_ICMP_TCP, "off" to strings.HEV_UDP_DISABLED)
    val rawMode = config.hevUdpMode.lowercase().trim().let { if (it == "icmp" || it == "true") "tcp" else it }
    val hevUdpMode = if (rawMode in hevUdpOptions) rawMode else "tcp"

    IosGroupCard { Column {
        IosPickerRow(icon = Icons.Default.BugReport, iconBg = AppPalette.accentVariantAlt, title = strings.HEV_LOG_LEVEL, value = levelLabels[currentLevel] ?: strings.HEV_LOG_WARN_DEFAULT, options = hevLevels.map { levelLabels[it]!! }, onOptionSelected = { index -> onUpdateConfig(config.copy(hevLogLevel = hevLevels[index])) }); AppDivider()
        IosPickerRow(icon = Icons.Default.SwapVert, iconBg = Color(0xFF30B0C7), title = strings.HEV_UDP_FORWARDING_MODE, value = hevUdpLabels[hevUdpMode] ?: strings.HEV_UDP_ASSOCIATE, options = hevUdpOptions.map { hevUdpLabels[it]!! }, onOptionSelected = { index -> onUpdateConfig(config.copy(hevUdpMode = hevUdpOptions[index])) })
    } }
}

@Composable private fun SystemPage(isAndroid: Boolean, onExportBackup: () -> Unit, onImportBackup: () -> Unit, onOpenVpnSettings: () -> Unit, onResetClick: () -> Unit) {
    IosGroupCard { Column {
        val strings = LocalAppStrings.current
        if (isAndroid) {
            IosActionRow(icon = Icons.Default.Lock, iconBg = Color(0xFF0A84FF), title = strings.ALWAYS_ON_VPN, subtitle = strings.ALWAYS_ON_VPN_SUB, onClick = onOpenVpnSettings); AppDivider()
        }
        IosActionRow(icon = Icons.Default.CloudUpload, iconBg = AppPalette.accentVariant, title = strings.FULL_BACKUP_TITLE, subtitle = strings.FULL_BACKUP_SUB, onClick = onExportBackup); AppDivider()
        IosActionRow(icon = Icons.Default.CloudDownload, iconBg = IosActiveGreen, title = strings.RESTORE_BACKUP_TITLE, subtitle = strings.RESTORE_BACKUP_SUB, onClick = onImportBackup); AppDivider()
        IosActionRow(icon = Icons.Default.DeleteForever, iconBg = AppPalette.statusError, title = strings.RESET_DEFAULTS, subtitle = strings.RESET_DEFAULTS_SUB, onClick = onResetClick, titleColor = AppPalette.statusError)
    } }
}
