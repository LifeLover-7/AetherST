package io.github.immaghzbad.aetherst.shared.ui.screens

import io.github.immaghzbad.aetherst.shared.ui.theme.AppPalette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.immaghzbad.aetherst.platform.AppIcon
import io.github.immaghzbad.aetherst.platform.isDesktop
import io.github.immaghzbad.aetherst.shared.i18n.LocalAppStrings
import io.github.immaghzbad.aetherst.shared.model.AppInfo
import io.github.immaghzbad.aetherst.shared.model.SplitTunnelAppManager

private val IosCardBg get() = AppPalette.surfaceRaised
private val IosSecondaryLabel get() = AppPalette.textSecondary
private val IosActiveBlue get() = AppPalette.accent
private val IosActiveGreen get() = AppPalette.statusConnected
private val IosInactiveTrack get() = AppPalette.inactiveTrack

private val IranianAppKeywords = listOf(
    "snapp", "tapsi", "tap30", "divar", "torob", "digikala", "digipay", "bale", "eitaa",
    "soroush", "mmdt", "rubika", "gap", "igap", "shaparak", "asanpardakht", "sekeh",
    "bahamta", "blubank", "sibche", "myket", "farsitel", "cafebazaar", "bazaar",
    "irancell", "myirancell", "mci", "hamrah", "rightel", "shatel", "asiatech",
    "aparat", "filimo", "namava", "telewebion", "fidibo", "taaghche", "ketabrah",
    "basalam", "okala", "alopeyk", "takhfifan", "alibaba", "mrbilit", "flytoday",
    "jabama", "otaghak", "achareh", "karnameh", "azki", "bimito", "nobitex", "wallex",
    "ramzinex", "bitpin", "tabdeal", "ompfinex", "mellat", "melli", "saderat", "sepah",
    "tejarat", "parsian", "pasargad", "saman", "keshavarzi", "maskan", "refah",
    "gardeshgari", "ayandeh", "neshan", "balad", "virgool", "zoomit", "digiato",
    "varzesh3", "hamrahbank", "mobillet", "780", "724", "pishkhan", "sakha", "taamin",
    "tamin", "rahvar", "shad", "zarinpal", "payping", "tobank", "wepod", "pod"
)
private val PersianCharRegex = Regex("[\\u0600-\\u06FF]")

private fun isIranianApp(app: AppInfo): Boolean {
    val pkg = app.packageName.lowercase().trim()
    val name = app.name.lowercase().trim()
    if (pkg.startsWith("ir.") || pkg.startsWith("com.ir.") || pkg.contains(".ir.")) return true
    if (PersianCharRegex.containsMatchIn(app.name)) return true
    return IranianAppKeywords.any { kw -> pkg.contains(kw) || name.contains(kw) }
}

@Composable
fun SplitTunnelingScreen(
    apps: List<AppInfo>,
    excludedPackages: Set<String>,
    blockedPackages: Set<String>,
    tunnelAllApps: Boolean,
    onUpdateMode: (String, Int) -> Unit,
    onBack: () -> Unit,
    scaleFactor: Float = 1f,
    tunneledPackages: Set<String> = emptySet(),
    onBulkUpdateMode: (List<String>, Int) -> Unit = { _, _ -> },
    iranDirectEnabled: Boolean = true,
    iranDirectCount: Int = 0,
    onIranDirectChange: (Boolean) -> Unit = {},
    onOpenRoutingRules: (() -> Unit)? = null
) {
    val strings = LocalAppStrings.current
    val focusManager = LocalFocusManager.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var animateDialog by remember { mutableStateOf(false) }
    var modeFilter by remember { mutableIntStateOf(0) }
    var autoDetectedCount by remember { mutableStateOf<Int?>(null) }
    val isWindowsDesktop = isDesktop

    val customApps by SplitTunnelAppManager.customApps.collectAsState()
    val removedPackages by SplitTunnelAppManager.removedPackages.collectAsState()

    val effectiveApps = remember(apps, customApps, removedPackages) {
        (customApps + apps)
            .distinctBy { it.packageName.lowercase() }
            .filterNot { removed -> removedPackages.any { it.equals(removed.packageName, ignoreCase = true) } }
    }

    if (showHelpDialog) {
        SplitTunnelHelpDialog(
            visible = animateDialog,
            onDismiss = { animateDialog = false },
            onTransitionEnd = { showHelpDialog = false },
            scaleFactor = scaleFactor,
            desktopCapabilities = isWindowsDesktop
        )
    }
    LaunchedEffect(showHelpDialog) { if (showHelpDialog) animateDialog = true }

    // On desktop "Bypass" is enforced by destination (routing rules + the Iran-direct
    // preset), never per application: Windows has no per-app routing without a driver.
    val blockedCount = blockedPackages.size
    val bypassCount = if (isWindowsDesktop) 0 else if (tunnelAllApps) excludedPackages.size else (effectiveApps.size - tunneledPackages.size - blockedCount).coerceAtLeast(0)
    val tunneledCount = if (isWindowsDesktop) (effectiveApps.size - blockedCount).coerceAtLeast(0) else if (tunnelAllApps) (effectiveApps.size - bypassCount - blockedCount).coerceAtLeast(0) else tunneledPackages.size

    /** Desktop: per-app "bypass" is not enforceable, so an app is only TUNNEL or BLOCKED. */
    fun modeOf(app: AppInfo): Int = when {
        blockedPackages.contains(app.packageName) -> 2
        isWindowsDesktop -> 1
        tunnelAllApps -> if (excludedPackages.contains(app.packageName)) 0 else 1
        else -> if (tunneledPackages.contains(app.packageName)) 1 else 0
    }

    val filteredApps = remember(effectiveApps, searchQuery, selectedTab, excludedPackages, tunneledPackages, blockedPackages, modeFilter, tunnelAllApps) {
        effectiveApps.filter { app ->
            val matchesTab = if (selectedTab == 0) !app.isSystemApp else app.isSystemApp
            val matchesSearch = searchQuery.isEmpty() || app.name.contains(searchQuery, ignoreCase = true) || app.packageName.contains(searchQuery, ignoreCase = true)
            val mode = modeOf(app)
            val matchesMode = when (modeFilter) {
                1 -> mode == 1
                2 -> mode == 0
                3 -> mode == 2
                else -> true
            }
            matchesTab && matchesSearch && matchesMode
        }.sortedWith(compareBy<AppInfo> { modeOf(it) }.thenBy { it.name.lowercase() })
    }

    Column(modifier = Modifier.fillMaxSize().background(AppPalette.surfaceSunken).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focusManager.clearFocus() }) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = (8 * scaleFactor).dp, end = (12 * scaleFactor).dp, top = if (isWindowsDesktop) 12.dp else 36.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size((36 * scaleFactor).dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size((20 * scaleFactor).dp))
            }
            Spacer(modifier = Modifier.width((4 * scaleFactor).dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(strings.SPLIT_TITLE, fontWeight = FontWeight.Bold, color = Color.White, fontSize = (18 * scaleFactor).sp, lineHeight = (20 * scaleFactor).sp)
                Text(strings.SPLIT_STATS_TUNNEL_BYPASS_BLOCKED.format(tunneledCount, bypassCount, blockedCount), color = IosSecondaryLabel, fontSize = (11 * scaleFactor).sp)
            }
            if (isWindowsDesktop) {
                if (removedPackages.isNotEmpty()) {
                    IconButton(
                        onClick = { SplitTunnelAppManager.onRestoreRemovedApps?.invoke() },
                        modifier = Modifier.size((34 * scaleFactor).dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Restore Removed Apps", tint = IosActiveGreen, modifier = Modifier.size((18 * scaleFactor).dp))
                    }
                    Spacer(modifier = Modifier.width((4 * scaleFactor).dp))
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(IosActiveBlue)
                        .clickable {
                            SplitTunnelAppManager.onPickDesktopApp?.invoke { pickedApp ->
                                if (pickedApp != null) {
                                    selectedTab = 0
                                    // Desktop: an added app is tunneled by default; blocking is explicit.
                                    onUpdateMode(pickedApp.packageName, 1)
                                }
                            }
                        }
                        .padding(horizontal = (10 * scaleFactor).dp, vertical = (6 * scaleFactor).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size((15 * scaleFactor).dp))
                    Spacer(modifier = Modifier.width((4 * scaleFactor).dp))
                    Text("افزودن برنامه", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (11 * scaleFactor).sp)
                }
                Spacer(modifier = Modifier.width((4 * scaleFactor).dp))
            } else {
                // دکمه شناسایی خودکار برنامه‌های ایرانی نصب‌شده روی موبایل و افزودن به لیست Bypass
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(IosActiveGreen.copy(alpha = 0.2f))
                        .clickable {
                            val iranianPkgs = effectiveApps
                                .filter { !it.isSystemApp && isIranianApp(it) }
                                .map { it.packageName }
                            if (iranianPkgs.isNotEmpty()) {
                                onBulkUpdateMode(iranianPkgs, 0) // 0 = Bypass (Direct)
                                autoDetectedCount = iranianPkgs.size
                                modeFilter = 2 // نمایش تب Bypass
                            } else {
                                autoDetectedCount = 0
                            }
                        }
                        .padding(horizontal = (10 * scaleFactor).dp, vertical = (6 * scaleFactor).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = IosActiveGreen, modifier = Modifier.size((15 * scaleFactor).dp))
                    Spacer(modifier = Modifier.width((4 * scaleFactor).dp))
                    Text(
                        text = if (autoDetectedCount != null) "اپ‌های ایرانی (${autoDetectedCount})" else "اپ‌های ایرانی",
                        color = IosActiveGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = (11 * scaleFactor).sp
                    )
                }
                Spacer(modifier = Modifier.width((4 * scaleFactor).dp))
            }
            IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.size((36 * scaleFactor).dp)) {
                Icon(Icons.Default.Info, null, tint = IosActiveBlue, modifier = Modifier.size((20 * scaleFactor).dp))
            }
        }

        if (!isWindowsDesktop && tunnelAllApps) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = (16 * scaleFactor).dp, vertical = (6 * scaleFactor).dp).background(Color(0xFFFFD60A).copy(alpha = 0.1f), RoundedCornerShape(10.dp)).padding((10 * scaleFactor).dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, null, tint = Color(0xFFFFD60A), modifier = Modifier.size((16 * scaleFactor).dp))
                Spacer(modifier = Modifier.width((8 * scaleFactor).dp))
                Column {
                    Text(strings.SPLIT_WHOLE_DEVICE_ON, color = Color(0xFFFFD60A), fontWeight = FontWeight.Bold, fontSize = (12 * scaleFactor).sp, textAlign = TextAlign.Start)
                    Text("هر برنامه‌ای را که در لیست زیر خاموش کنید (یا با دکمه «اپ‌های ایرانی» انتخاب شود)، مستقیم و بدون فیلترشکن (Bypass) وصل می‌شود.", color = Color.White.copy(alpha = 0.7f), fontSize = (11 * scaleFactor).sp, lineHeight = (15 * scaleFactor).sp, textAlign = TextAlign.Start)
                }
            }
        }

        // Desktop: per-app bypass is not possible without a kernel driver, so Bypass is
        // offered honestly by destination. Block stays per application (firewall based).
        if (isWindowsDesktop) DesktopBypassCard(
            iranDirectEnabled = iranDirectEnabled,
            iranDirectCount = iranDirectCount,
            onIranDirectChange = onIranDirectChange,
            onOpenRoutingRules = onOpenRoutingRules,
            scaleFactor = scaleFactor
        )

        Box(modifier = Modifier.padding(horizontal = (16 * scaleFactor).dp, vertical = (6 * scaleFactor).dp)) {
            BasicTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth().height((40 * scaleFactor).dp).background(IosCardBg, RoundedCornerShape(10.dp)), textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White, fontSize = (13 * scaleFactor).sp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }), singleLine = true, cursorBrush = SolidColor(IosActiveBlue), decorationBox = { innerTextField ->
                Row(modifier = Modifier.padding(horizontal = (12 * scaleFactor).dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = IosSecondaryLabel, modifier = Modifier.size((18 * scaleFactor).dp))
                    Spacer(modifier = Modifier.width((8 * scaleFactor).dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) Text(if (isWindowsDesktop) strings.SPLIT_SEARCH_HINT_DESKTOP else strings.SPLIT_SEARCH_HINT, color = IosSecondaryLabel, fontSize = (12 * scaleFactor).sp)
                        innerTextField()
                    }
                    if (searchQuery.isNotEmpty()) Icon(Icons.Default.Block, null, tint = IosSecondaryLabel, modifier = Modifier.size((16 * scaleFactor).dp).clickable { searchQuery = "" })
                }
            })
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = (16 * scaleFactor).dp, vertical = (4 * scaleFactor).dp).background(IosCardBg, RoundedCornerShape(10.dp)).padding(2.dp)) {
            listOf(strings.SPLIT_TAB_USER_APPS, strings.SPLIT_TAB_SYSTEM_APPS).forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isSelected) IosActiveBlue else Color.Transparent).clickable { selectedTab = index }.padding(vertical = (7 * scaleFactor).dp), contentAlignment = Alignment.Center) {
                    Text(title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = if (isSelected) Color.White else IosSecondaryLabel, fontSize = (11 * scaleFactor).sp)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = (16 * scaleFactor).dp, vertical = (4 * scaleFactor).dp), horizontalArrangement = Arrangement.spacedBy((6 * scaleFactor).dp)) {
            listOf(strings.SPLIT_FILTER_ALL, strings.SPLIT_FILTER_TUNNEL, strings.SPLIT_FILTER_BYPASS, strings.SPLIT_FILTER_BLOCKED).forEachIndexed { index, label ->
                val isSelected = modeFilter == index
                val count = when (index) { 1 -> tunneledCount; 2 -> bypassCount; 3 -> blockedCount; else -> filteredApps.size }
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isSelected) IosActiveBlue.copy(alpha = 0.25f) else IosCardBg).clickable { modeFilter = index }.padding(vertical = (6 * scaleFactor).dp), contentAlignment = Alignment.Center) {
                    Text("$label • $count", color = if (isSelected) Color.White else IosSecondaryLabel, fontSize = (10 * scaleFactor).sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }

        // Desktop escape hatch: firewall block rules persist, so offer one-tap unblock.
        if (isWindowsDesktop && blockedCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = (16 * scaleFactor).dp, vertical = (4 * scaleFactor).dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppPalette.statusError.copy(alpha = 0.14f))
                    .clickable {
                        val unblocked = blockedPackages.toList()
                        if (unblocked.isNotEmpty()) onBulkUpdateMode(unblocked, 1)
                        modeFilter = 0
                    }
                    .padding(horizontal = (10 * scaleFactor).dp, vertical = (7 * scaleFactor).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LockOpen, null, tint = AppPalette.statusError, modifier = Modifier.size((15 * scaleFactor).dp))
                Spacer(modifier = Modifier.width((6 * scaleFactor).dp))
                Text(
                    "رفع مسدودی همه برنامه‌ها ($blockedCount)",
                    color = AppPalette.statusError,
                    fontWeight = FontWeight.Bold,
                    fontSize = (11 * scaleFactor).sp
                )
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = (16 * scaleFactor).dp, end = (16 * scaleFactor).dp, bottom = (24 * scaleFactor).dp), verticalArrangement = Arrangement.spacedBy((8 * scaleFactor).dp)) {
            items(filteredApps, key = { it.packageName }) { app ->
                AppLineItem(
                    app = app,
                    mode = modeOf(app),
                    onUpdateMode = { m -> onUpdateMode(app.packageName, m) },
                    onRemoveApp = if (isWindowsDesktop) {
                        {
                            onUpdateMode(app.packageName, 1)
                            SplitTunnelAppManager.onRemoveApp?.invoke(app.packageName)
                        }
                    } else null,
                    enabled = true,
                    scaleFactor = scaleFactor,
                    perAppTunnelOnly = isWindowsDesktop
                )
            }
        }
    }
}

@Composable
private fun AppLineItem(
    app: AppInfo,
    mode: Int,
    onUpdateMode: (Int) -> Unit,
    onRemoveApp: (() -> Unit)? = null,
    enabled: Boolean = true,
    scaleFactor: Float,
    perAppTunnelOnly: Boolean = false
) {
    val strings = LocalAppStrings.current
    val bg = when (mode) {
        2 -> AppPalette.statusError.copy(alpha = 0.12f)
        0 -> IosActiveGreen.copy(alpha = 0.10f)
        else -> IosCardBg
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape((12 * scaleFactor).dp)).padding(horizontal = (10 * scaleFactor).dp, vertical = (8 * scaleFactor).dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app = app, modifier = Modifier.size((36 * scaleFactor).dp).clip(RoundedCornerShape((8 * scaleFactor).dp)))
            Spacer(modifier = Modifier.width((10 * scaleFactor).dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.SemiBold, color = if (mode == 2) AppPalette.statusError else Color.White, fontSize = (13 * scaleFactor).sp, maxLines = 1, textAlign = TextAlign.Left)
                Text(app.packageName, color = IosSecondaryLabel, fontSize = (10 * scaleFactor).sp, maxLines = 1, textAlign = TextAlign.Left)
                Text(
                    text = when (mode) {
                        2 -> strings.SPLIT_BLOCKED_NO_INTERNET
                        0 -> "Bypass • Direct"
                        else -> strings.SPLIT_TUNNEL_VPN
                    },
                    color = when (mode) {
                        2 -> AppPalette.statusError
                        0 -> IosActiveGreen
                        else -> IosActiveBlue
                    },
                    fontSize = (10 * scaleFactor).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Left
                )
            }
            Spacer(modifier = Modifier.width((6 * scaleFactor).dp))
            if (onRemoveApp != null) {
                IconButton(onClick = onRemoveApp, modifier = Modifier.size((30 * scaleFactor).dp), enabled = enabled) {
                    Box(modifier = Modifier.size((26 * scaleFactor).dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove App", tint = IosSecondaryLabel, modifier = Modifier.size((15 * scaleFactor).dp))
                    }
                }
                Spacer(modifier = Modifier.width((2 * scaleFactor).dp))
            }
            IconButton(onClick = {
                if (!enabled) return@IconButton
                // Desktop has no per-app bypass, so un-blocking must return to Tunnel.
                onUpdateMode(when { mode != 2 -> 2; perAppTunnelOnly -> 1; else -> 0 })
            }, modifier = Modifier.size((30 * scaleFactor).dp), enabled = enabled) {
                Box(modifier = Modifier.size((26 * scaleFactor).dp).background(if (mode == 2) AppPalette.statusError else Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Block, null, tint = if (mode == 2) Color.White else IosSecondaryLabel, modifier = Modifier.size((15 * scaleFactor).dp))
                }
            }
            Spacer(modifier = Modifier.width((4 * scaleFactor).dp))
            if (perAppTunnelOnly) {
                // Desktop cannot bypass a single application (needs a kernel/WFP driver),
                // so there is no per-app switch here - only Tunnel or Blocked.
                Box(modifier = Modifier.height((26 * scaleFactor).dp).background(if (mode == 2) AppPalette.statusError.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(7.dp)).padding(horizontal = (8 * scaleFactor).dp), contentAlignment = Alignment.Center) {
                    Text(if (mode == 2) strings.SPLIT_BLOCKED_NO_INTERNET else strings.SPLIT_TUNNEL_VPN, color = if (mode == 2) AppPalette.statusError else IosSecondaryLabel, fontSize = (9 * scaleFactor).sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Switch(checked = mode == 1, onCheckedChange = { checked -> if (!enabled || mode == 2) return@Switch; onUpdateMode(if (checked) 1 else 0) }, enabled = enabled && mode != 2, modifier = Modifier.graphicsLayer { scaleX = 0.75f; scaleY = 0.75f }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = IosActiveBlue, checkedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White, uncheckedTrackColor = IosInactiveTrack, uncheckedBorderColor = Color.Transparent))
            }
        }
    }
}

@Composable
private fun DesktopBypassCard(
    iranDirectEnabled: Boolean,
    iranDirectCount: Int,
    onIranDirectChange: (Boolean) -> Unit,
    onOpenRoutingRules: (() -> Unit)?,
    scaleFactor: Float
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = (16 * scaleFactor).dp, vertical = (6 * scaleFactor).dp)
            .background(IosActiveBlue.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .border(1.dp, IosActiveBlue.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding((10 * scaleFactor).dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Public, null, tint = IosActiveGreen, modifier = Modifier.size((16 * scaleFactor).dp))
            Spacer(modifier = Modifier.width((8 * scaleFactor).dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Bypass بر اساس مقصد (دامنه و IP)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (12 * scaleFactor).sp,
                    textAlign = TextAlign.Start
                )
                Text(
                    if (iranDirectEnabled) "دامنه‌ها و IPهای ایرانی مستقیم می‌روند ($iranDirectCount قانون فعال). بقیه از تونل رد می‌شوند."
                    else "خاموش است؛ همه مقصدها از تونل رد می‌شوند. برای bypass، قوانین Domain/IP را فعال کنید.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = (11 * scaleFactor).sp,
                    lineHeight = (15 * scaleFactor).sp,
                    textAlign = TextAlign.Start
                )
            }
            Spacer(modifier = Modifier.width((6 * scaleFactor).dp))
            Switch(
                checked = iranDirectEnabled,
                onCheckedChange = onIranDirectChange,
                modifier = Modifier.graphicsLayer { scaleX = 0.75f; scaleY = 0.75f },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = IosActiveGreen,
                    checkedBorderColor = Color.Transparent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = IosInactiveTrack,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
        if (onOpenRoutingRules != null) {
            Spacer(modifier = Modifier.height((8 * scaleFactor).dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(IosActiveBlue.copy(alpha = 0.18f))
                    .clickable { onOpenRoutingRules() }
                    .padding(horizontal = (10 * scaleFactor).dp, vertical = (6 * scaleFactor).dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.AltRoute, null, tint = IosActiveBlue, modifier = Modifier.size((14 * scaleFactor).dp))
                Spacer(modifier = Modifier.width((6 * scaleFactor).dp))
                Text(
                    "قوانین دامنه / IP (Bypass یا Block)",
                    color = IosActiveBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = (11 * scaleFactor).sp
                )
            }
        }
        Spacer(modifier = Modifier.height((8 * scaleFactor).dp))
        Text(
            "در ویندوز، bypass به تفکیک برنامه بدون درایور کرنل ممکن نیست؛ به همین دلیل از این لیست فقط می‌توانید برنامه‌ها را «مسدود (Block)» کنید.",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = (10 * scaleFactor).sp,
            lineHeight = (14 * scaleFactor).sp,
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun SplitTunnelHelpDialog(visible: Boolean, onDismiss: () -> Unit, onTransitionEnd: () -> Unit, scaleFactor: Float, desktopCapabilities: Boolean = false) {
    val strings = LocalAppStrings.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true)) {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.9f), exit = fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 0.9f)) {
            DisposableEffect(Unit) { onDispose { onTransitionEnd() } }
            Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss), contentAlignment = Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Column(modifier = Modifier.fillMaxWidth().padding((12 * scaleFactor).dp).clip(RoundedCornerShape((20 * scaleFactor).dp)).background(AppPalette.surfaceRaised.copy(alpha = 0.98f)).clickable(enabled = false) {}.padding((20 * scaleFactor).dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Info, null, tint = IosActiveBlue, modifier = Modifier.size((20 * scaleFactor).dp))
                            Spacer(modifier = Modifier.width((8 * scaleFactor).dp))
                            Text(strings.SPLIT_HELP_TITLE, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (16 * scaleFactor).sp, textAlign = TextAlign.Center)
                        }
                        Spacer(modifier = Modifier.height((16 * scaleFactor).dp))
                        Column(verticalArrangement = Arrangement.spacedBy((14 * scaleFactor).dp)) {
                            if (desktopCapabilities) {
                                HelpItem(title = "Bypass بر اساس مقصد", desc = "در ویندوز مسیر هر برنامه قابل تفکیک نیست؛ برای دور زدن تونل، دامنه یا IP مقصد را در قوانین مسیریابی روی حالت Direct بگذارید (پریست ایران از قبل فعال است).", icon = Icons.Default.Public, color = IosActiveGreen, scaleFactor = scaleFactor)
                                HelpItem(title = strings.SPLIT_HELP_TUNNEL_TITLE, desc = strings.SPLIT_HELP_TUNNEL_DESC, icon = Icons.Default.Security, color = IosActiveBlue, scaleFactor = scaleFactor)
                                HelpItem(title = strings.SPLIT_HELP_BLOCKED_TITLE, desc = "با فایروال ویندوز به‌صورت per-app اعمال می‌شود؛ برنامه انتخاب‌شده در زمان اتصال هیچ اینترنتی نخواهد داشت.", icon = Icons.Default.Block, color = AppPalette.statusError, scaleFactor = scaleFactor)
                            } else {
                                HelpItem(title = strings.SPLIT_HELP_BYPASS_TITLE, desc = strings.SPLIT_HELP_BYPASS_DESC, icon = Icons.Default.Public, color = IosActiveGreen, scaleFactor = scaleFactor)
                                HelpItem(title = strings.SPLIT_HELP_TUNNEL_TITLE, desc = strings.SPLIT_HELP_TUNNEL_DESC, icon = Icons.Default.Security, color = IosActiveBlue, scaleFactor = scaleFactor)
                                HelpItem(title = strings.SPLIT_HELP_BLOCKED_TITLE, desc = strings.SPLIT_HELP_BLOCKED_DESC, icon = Icons.Default.Block, color = AppPalette.statusError, scaleFactor = scaleFactor)
                            }
                        }
                        Spacer(modifier = Modifier.height((16 * scaleFactor).dp))
                        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height((44 * scaleFactor).dp), colors = ButtonDefaults.buttonColors(containerColor = IosActiveBlue, contentColor = Color.White), shape = RoundedCornerShape(12.dp)) { Text(strings.ROUTING_HELP_GOT_IT, fontWeight = FontWeight.Bold, fontSize = (14 * scaleFactor).sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpItem(title: String, desc: String, icon: ImageVector, color: Color, scaleFactor: Float) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size((36 * scaleFactor).dp).background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size((18 * scaleFactor).dp)) }
        Spacer(modifier = Modifier.width((12 * scaleFactor).dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = (13 * scaleFactor).sp, textAlign = TextAlign.Start)
            Spacer(modifier = Modifier.height((2 * scaleFactor).dp))
            Text(desc, color = Color.White.copy(alpha = 0.6f), fontSize = (11 * scaleFactor).sp, lineHeight = (15 * scaleFactor).sp, textAlign = TextAlign.Start)
        }
    }
}
