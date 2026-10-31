package com.dtn.mesh.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.receiver.webrtc.DefaultTurnConfig
import com.dtn.mesh.receiver.webrtc.IceResolution
import com.dtn.mesh.receiver.webrtc.TurnProviderType
import com.dtn.mesh.receiver.webrtc.TurnSettings
import com.dtn.mesh.receiver.webrtc.TurnTestResult
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.InfoNote
import com.dtn.mesh.ui.components.InnerTopBar
import com.dtn.mesh.ui.components.NoteKind
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.components.SectionHeader
import com.dtn.mesh.ui.components.SegmentedControl
import com.dtn.mesh.ui.components.StatusDot
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnType
import kotlinx.coroutines.launch

/**
 * Settings › TURN server (Bring Your Own TURN).
 *
 * The page edits one [TurnSettings] draft. Each provider keeps its own credential slots, so
 * switching the selector never sends one provider's credential to another server. Secrets are
 * loaded decrypted only here and saved KeyVault-encrypted; nothing on this page goes to peers.
 *
 * Note: the WebRTC direct transport that will consume these servers is not built yet; until it
 * is, this configuration is stored and testable but unused.
 */
@Composable
fun TurnConfigPage(viewModel: DtnViewModel, onBack: () -> Unit) {
    var saved by remember { mutableStateOf<TurnSettings?>(null) }
    var draft by remember { mutableStateOf<TurnSettings?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TurnTestResult?>(null) }
    val resolution by viewModel.turnResolution.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)

    LaunchedEffect(Unit) {
        val s = viewModel.loadTurnSettings()
        saved = s
        draft = s
        viewModel.resolveIceServers()
    }

    val d = draft
    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { InnerTopBar("TURN server", onBack = onBack, subtitle = "Settings › Connectivity") },
        bottomBar = {
            if (d != null) Surface(color = DtnColors.Background) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlineButton(
                        if (testing) "Testing…" else "Test",
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = viewModel.testTurnConfig(d)
                                testing = false
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        "Save",
                        onClick = { viewModel.saveTurnConfig(d) { saved = d } },
                        enabled = d != saved,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { padding ->
        if (d == null) return@Scaffold
        fun edit(f: (TurnSettings) -> TurnSettings) { draft = f(d); testResult = null }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoNote(
                "Use your own TURN account so your direct connections use your quota. Your credentials " +
                    "are encrypted on this phone and are never sent to other phones or included in " +
                    "connection setup. Each side of a connection uses its own TURN server.",
                kind = NoteKind.INFO,
            )

            SectionHeader("Provider")
            DtnCard {
                val types = TurnProviderType.entries
                SegmentedControl(
                    options = listOf("Default", "Metered", "Own server", "JSON"),
                    selected = types.indexOf(d.type),
                    onSelect = { i -> edit { it.copy(type = types[i]) } },
                )
            }

            when (d.type) {
                TurnProviderType.DEFAULT -> DtnCard {
                    Text("DTN Mesh default", style = DtnType.RowTitle.copy(fontSize = 14.sp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (DefaultTurnConfig.hasSharedApiKey) "Shared TURN from ${DefaultTurnConfig.DEFAULT_METERED_DOMAIN}. " +
                            "Its quota is shared by everyone on the default."
                        else "This build has no shared TURN: public STUN only. Most connections still work directly; " +
                            "the rest use the relays.",
                        style = DtnType.Meta,
                    )
                }

                TurnProviderType.METERED -> DtnCard {
                    Text("Your Metered.ca app. The API key fetches fresh credentials; a static credential is the alternative.",
                        style = DtnType.Meta)
                    Spacer(Modifier.height(8.dp))
                    Field(d.meteredDomain, { v -> edit { it.copy(meteredDomain = v.trim()) } }, "App domain", "myapp.metered.live", KeyboardType.Uri)
                    Secret(d.meteredApiKey, { v -> edit { it.copy(meteredApiKey = v.trim()) } }, "API key")
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = DtnColors.CardBorder)
                    Spacer(Modifier.height(6.dp))
                    Text("Or a static credential", style = DtnType.RowTitle.copy(fontSize = 14.sp))
                    Field(d.meteredUsername, { v -> edit { it.copy(meteredUsername = v.trim()) } }, "Username", "From the Metered dashboard")
                    Secret(d.meteredPassword, { v -> edit { it.copy(meteredPassword = v.trim()) } }, "Credential")
                }

                TurnProviderType.COTURN -> DtnCard {
                    Field(d.coturnHost, { v -> edit { it.copy(coturnHost = v.trim()) } }, "Host or IP", "turn.example.org", KeyboardType.Uri)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Port(d.coturnPort, { p -> edit { it.copy(coturnPort = p.coerceAtLeast(1)) } }, "UDP/TCP port", Modifier.weight(1f))
                        Port(d.coturnTlsPort, { p -> edit { it.copy(coturnTlsPort = p) } }, "TLS port (0 = none)", Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Shared secret (use-auth-secret)", style = DtnType.RowTitle.copy(fontSize = 14.sp))
                            Text("The phone derives a 6-hour credential from coturn's static-auth-secret.", style = DtnType.Meta)
                        }
                        Switch(checked = d.coturnSharedSecret, onCheckedChange = { on -> edit { it.copy(coturnSharedSecret = on) } })
                    }
                    Field(d.coturnUsername, { v -> edit { it.copy(coturnUsername = v.trim()) } },
                        if (d.coturnSharedSecret) "Label (optional)" else "Username", if (d.coturnSharedSecret) "dtn" else "TURN username")
                    Secret(d.coturnPassword, { v -> edit { it.copy(coturnPassword = v.trim()) } },
                        if (d.coturnSharedSecret) "Shared secret" else "Password")
                }

                TurnProviderType.CUSTOM_JSON -> DtnCard {
                    Text("An iceServers array, e.g. [{\"urls\":\"turn:host:3478\",\"username\":\"u\",\"credential\":\"p\"}]. " +
                        "Stored encrypted.", style = DtnType.Meta)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = d.customJson, onValueChange = { v -> edit { it.copy(customJson = v) } },
                        modifier = Modifier.fillMaxWidth(), shape = DtnShapes.Field,
                        label = { Text("iceServers JSON") }, maxLines = 8,
                    )
                }
            }

            ResolutionCard(resolution)

            if (testing || testResult != null) {
                SectionHeader("Test")
                DtnCard {
                    val r = testResult
                    if (testing || r == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Testing…", style = DtnType.RowSubtitle)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (r.ok) DtnColors.Green else DtnColors.Red)
                            Spacer(Modifier.width(8.dp))
                            Text(r.message + if (r.tookMs > 0) " · ${r.tookMs} ms" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = if (r.ok) DtnColors.GreenText else DtnColors.RedText)
                        }
                        if (r.details.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(r.details, style = DtnType.Meta)
                        }
                    }
                }
            }

            TextButton(onClick = {
                viewModel.resetTurnToDefault {
                    scope.launch { val s = viewModel.loadTurnSettings(); saved = s; draft = s; testResult = null }
                }
            }) { Text("Reset to default and forget my TURN credentials", color = DtnColors.RedText) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** What the RTCPeerConnection would get right now, and whose TURN account that is. Credentials masked. */
@Composable
private fun ResolutionCard(r: IceResolution?) {
    SectionHeader("In use (${r?.servers?.size ?: 0} servers)")
    DtnCard {
        if (r == null) { Text("Resolving…", style = DtnType.Meta); return@DtnCard }
        Text(r.source, style = DtnType.RowTitle.copy(fontSize = 14.sp))
        if (r.fellBack) {
            Spacer(Modifier.height(2.dp))
            Text("${r.reason}. Fallback usage counts against that account, not yours.", style = DtnType.Meta, color = DtnColors.RedText)
        }
        r.servers.forEach { s ->
            HorizontalDivider(color = DtnColors.CardBorder, modifier = Modifier.padding(vertical = 6.dp))
            Text(s.urls.joinToString(", "), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = DtnColors.TextTitle)
            Text(if (s.username != null) "With credential" else "No credential", style = DtnType.Meta)
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = DtnShapes.Field,
        label = { Text(label) }, placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
    )
}

@Composable
private fun Secret(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = DtnShapes.Field,
        label = { Text(label) }, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
    )
}

@Composable
private fun Port(value: Int, onChange: (Int) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value.toString(), onValueChange = { v -> onChange(v.filter(Char::isDigit).take(5).toIntOrNull()?.coerceAtMost(65535) ?: 0) },
        singleLine = true, modifier = modifier.padding(vertical = 4.dp), shape = DtnShapes.Field, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
    )
}
