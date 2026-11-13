package io.github.immaghzbad.aetherst.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.immaghzbad.aetherst.shared.i18n.LocalAppStrings
import io.github.immaghzbad.aetherst.shared.model.CustomWireGuardConfig
import io.github.immaghzbad.aetherst.shared.ui.theme.AppPalette
import io.github.immaghzbad.aetherst.shared.util.WireGuardConfigParser

@Composable
fun CustomWireGuardDialog(
    initialConfig: CustomWireGuardConfig,
    onSave: (CustomWireGuardConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    var rawConfigText by remember { mutableStateOf(initialConfig.rawConfig) }
    var parsedConfig by remember {
        mutableStateOf(
            if (initialConfig.rawConfig.isNotBlank()) {
                WireGuardConfigParser.parse(initialConfig.rawConfig)
            } else {
                initialConfig
            }
        )
    }

    fun updateFromRaw(text: String) {
        rawConfigText = text
        if (text.isNotBlank()) {
            parsedConfig = WireGuardConfigParser.parse(text)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = AppPalette.surfaceRaised),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppPalette.accentVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.VpnKey, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = strings.EXIT_NODE_WG_DIALOG_TITLE,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = strings.EXIT_NODE_CUSTOM_WG_DESC,
                                color = AppPalette.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Paste from clipboard button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                clipboardManager.getText()?.text?.let { text ->
                                    if (text.isNotBlank()) updateFromRaw(text)
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppPalette.accent),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(AppPalette.accent.copy(alpha = 0.5f)))
                        ) {
                            Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(strings.EXIT_NODE_WG_PASTE_CONF, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Raw .conf Text Area
                    Text(
                        text = strings.EXIT_NODE_WG_PASTE_CONF,
                        color = AppPalette.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = rawConfigText,
                        onValueChange = { updateFromRaw(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = {
                            Text(
                                text = strings.EXIT_NODE_WG_CONF_PLACEHOLDER,
                                color = AppPalette.textSecondary.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppPalette.accent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedContainerColor = AppPalette.divider,
                            unfocusedContainerColor = AppPalette.divider
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Parsed Summary Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = AppPalette.divider.copy(alpha = 0.5f)),
                        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.08f)))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = strings.EXIT_NODE_WG_PARSED_SUMMARY,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                val isValid = parsedConfig.isValid()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (isValid) AppPalette.statusConnected else AppPalette.statusError,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isValid) "Valid" else "Incomplete",
                                        color = if (isValid) AppPalette.statusConnected else AppPalette.statusError,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            ConfigSummaryRow(strings.EXIT_NODE_WG_ENDPOINT, parsedConfig.endpoint.ifBlank { "—" })
                            ConfigSummaryRow(strings.EXIT_NODE_WG_ADDRESS, listOfNotNull(parsedConfig.addressV4.takeIf { it.isNotBlank() }, parsedConfig.addressV6.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { "—" })
                            ConfigSummaryRow(strings.EXIT_NODE_WG_DNS, parsedConfig.dns.ifBlank { "1.1.1.1" })
                            ConfigSummaryRow(strings.EXIT_NODE_WG_MTU, "${parsedConfig.mtu}")
                            ConfigSummaryRow(strings.EXIT_NODE_WG_ALLOWED_IPS, listOfNotNull(parsedConfig.allowedIpsV4.takeIf { it.isNotBlank() }, parsedConfig.allowedIpsV6.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { "0.0.0.0/0" })
                            ConfigSummaryRow(strings.EXIT_NODE_WG_PUBLIC_KEY, if (parsedConfig.publicKey.isNotBlank()) "${parsedConfig.publicKey.take(12)}...${parsedConfig.publicKey.takeLast(6)}" else "—")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Actions: Cancel & Save
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppPalette.textSecondary)
                    ) {
                        Text(strings.CANCEL, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            val finalCfg = if (rawConfigText.isNotBlank()) {
                                WireGuardConfigParser.parse(rawConfigText)
                            } else {
                                parsedConfig
                            }
                            onSave(finalCfg)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppPalette.accent,
                            contentColor = Color.White
                        )
                    ) {
                        Text(strings.SAVE, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = AppPalette.textSecondary, fontSize = 11.sp)
        Text(
            text = value,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

