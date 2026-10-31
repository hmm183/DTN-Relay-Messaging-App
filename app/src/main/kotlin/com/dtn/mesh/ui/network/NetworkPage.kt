package com.dtn.mesh.ui.network

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.components.DtnCard
import com.dtn.mesh.ui.components.SettingRow
import com.dtn.mesh.ui.peers.PeerLists
import com.dtn.mesh.ui.peers.PeerRow
import com.dtn.mesh.ui.peers.rememberContactActions
import com.dtn.mesh.ui.rememberQrActions
import com.dtn.mesh.ui.shell.LinkActionsHost
import com.dtn.mesh.ui.shell.LinkButtonsRow
import com.dtn.mesh.ui.shell.LinkTilesCard
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType

/**
 * Network (start tab): the 4 link tiles (today's tap rules; long-press = details), Connect / Sync /
 * Clear buffer…, the phones in reach right now (how each is reachable, RSSI, ⋮ Open chat / Info /
 * Rename / Delete / Block, refresh) and Add contact by QR. The old body — overview, per-peer
 * scores and route observations — lives in Developer Tools › Routing Engine (row at the bottom).
 */
@Composable
fun NetworkPage(
    viewModel: DtnViewModel,
    onOpenContact: (String) -> Unit,
    onOpenRoutingEngine: () -> Unit,
    onOpenLinkPage: (TransportKind) -> Unit,
) {
    val peers by viewModel.peerList.collectAsState()
    val deleted by viewModel.deletedContacts.collectAsState()
    val nearby = remember(peers, deleted) { PeerLists.nearby(peers, deleted.keys) }
    val actions = rememberContactActions(viewModel)
    val qr = rememberQrActions(
        prompt = "Scan a contact's QR (My identity on their phone)",
        onResult = { text -> viewModel.handleScannedQr(text) { key -> viewModel.openChat(key) } },
        onProblem = { viewModel.showMessage(it) },
    )

    LinkActionsHost(viewModel, onOpenLinkPage = onOpenLinkPage) { links ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item(key = "link-tiles") {
                Spacer(Modifier.height(4.dp))
                LinkTilesCard(links)
            }
            item(key = "link-buttons") {
                Spacer(Modifier.height(12.dp))
                LinkButtonsRow(viewModel)
                Spacer(Modifier.height(12.dp))
            }
            item(key = "discover-mode") {
                val discoverable by viewModel.discoverable.collectAsState()
                DtnCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Discover Mode", style = DtnType.RowTitle)
                            Text(
                                if (discoverable) "Visible to all nearby phones & relay"
                                else "Stealth — only saved contacts can see you",
                                style = DtnType.RowSubtitle,
                            )
                        }
                        Switch(
                            checked = discoverable,
                            onCheckedChange = { viewModel.setDiscoverable(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = DtnColors.Primary,
                                uncheckedThumbColor = DtnColors.TextSubtitle,
                                uncheckedTrackColor = DtnColors.CardBorder,
                            )
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            item(key = "battery-exemption") { BatteryExemptionCard() }
            item(key = "nearby") {
                DtnCard(contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                        Text("Nearby devices · ${nearby.size}", style = DtnType.RowTitle, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.refreshPeerStatus() }) {
                            Icon(DtnIcons.Refresh, contentDescription = "Refresh nearby devices", tint = DtnColors.Primary)
                        }
                    }
                    if (nearby.isEmpty()) {
                        Text(
                            "No phones in reach right now. Connect, or check the links above: phones show up here " +
                                "when Bluetooth, Wi-Fi Direct, a LoRa hub or the internet relay reaches them.",
                            style = DtnType.RowSubtitle,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    } else {
                        nearby.forEach { p ->
                            HorizontalDivider(color = DtnColors.CardBorder)
                            PeerRow(
                                p, onClick = { onOpenContact(p.nodeId) },
                                menu = actions.menu(p, onOpenChat = { viewModel.openChat(it) }, onInfo = onOpenContact,
                                    onUnblock = { viewModel.setBlocked(it, false) }),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            item(key = "add-qr") {
                DtnCard(color = DtnColors.PrimarySoft, contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow("Add contact by QR", icon = DtnIcons.QrCode, subtitle = "Scan their QR, or upload an image of it",
                        iconBackground = DtnColors.Card, onClick = qr.scan)
                }
                Spacer(Modifier.height(12.dp))
            }
            item(key = "engine") {
                DtnCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    SettingRow("Routing details", icon = DtnIcons.DevTools,
                        subtitle = "Overview, per-peer scores and route observations · Developer Tools",
                        iconTint = DtnColors.TextTitle, iconBackground = DtnColors.GreySoft, onClick = onOpenRoutingEngine)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Shown only while the app is battery-optimised: Doze / Samsung "sleeping apps" then cut the
 * relay in the background. One tap opens the system's exemption dialog; re-checked on resume.
 */
@Composable
private fun BatteryExemptionCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var exempt by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.dtn.mesh.service.BatteryOptimization.isExempt(context))
    }
    com.dtn.mesh.ui.OnResume { exempt = com.dtn.mesh.service.BatteryOptimization.isExempt(context) }
    if (exempt) return
    DtnCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep running in the background", style = DtnType.RowTitle)
                Text(
                    "Battery optimisation stops the relay when the screen is off, so messages wait " +
                        "until you open the app. Allow unrestricted battery use to keep carrying them.",
                    style = DtnType.RowSubtitle,
                )
            }
            androidx.compose.material3.TextButton(onClick = { com.dtn.mesh.service.BatteryOptimization.requestExemption(context) }) {
                Text("Allow", color = DtnColors.Primary)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}
