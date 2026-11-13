package io.github.immaghzbad.aetherst.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.immaghzbad.aetherst.shared.i18n.LocalAppStrings
import io.github.immaghzbad.aetherst.shared.ui.theme.AppPalette

@Composable
fun CustomSocks5Dialog(
    initialHost: String,
    initialPort: Int,
    initialUser: String,
    initialPass: String,
    onSave: (host: String, port: Int, user: String, pass: String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current

    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(if (initialPort > 0) initialPort.toString() else "1080") }
    var username by remember { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf(initialPass) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = AppPalette.surfaceRaised),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppPalette.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Router, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = strings.EXIT_NODE_SOCKS5_DIALOG_TITLE,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = strings.EXIT_NODE_CUSTOM_SOCKS5_DESC,
                            color = AppPalette.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // SOCKS5 Host
                IosInputField(
                    label = strings.EXIT_NODE_SOCKS5_HOST,
                    value = host,
                    onValueChange = { host = it },
                    placeholder = "127.0.0.1 or domain.com",
                    testTag = "input_socks5_host",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // SOCKS5 Port
                IosInputField(
                    label = strings.EXIT_NODE_SOCKS5_PORT,
                    value = port,
                    onValueChange = { port = it },
                    placeholder = "1080",
                    keyboardType = KeyboardType.Number,
                    testTag = "input_socks5_port",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Username (Optional)
                IosInputField(
                    label = strings.EXIT_NODE_SOCKS5_USER,
                    value = username,
                    onValueChange = { username = it },
                    placeholder = "Optional",
                    testTag = "input_socks5_user",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Password (Optional)
                IosInputField(
                    label = strings.EXIT_NODE_SOCKS5_PASS,
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Optional",
                    testTag = "input_socks5_pass",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

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
                            val parsedPort = port.toIntOrNull()?.coerceIn(1, 65535) ?: 1080
                            onSave(host.trim(), parsedPort, username.trim(), password.trim())
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

