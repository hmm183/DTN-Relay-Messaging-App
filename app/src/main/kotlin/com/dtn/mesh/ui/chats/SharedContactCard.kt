package com.dtn.mesh.ui.chats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dtn.mesh.crypto.IdentityQr
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.components.OutlineButton
import com.dtn.mesh.ui.components.PrimaryButton
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnShapes
import com.dtn.mesh.ui.theme.DtnSpacing
import com.dtn.mesh.ui.theme.DtnType

/**
 * An interactive Contact Card rendered inside a chat bubble when someone shares a contact.
 * Shows the contact's name, Discord-style handle (Name#769a), verification status,
 * and an action button to "Message" (if already added) or "Add Contact" (pins unverified).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SharedContactCard(
    viewModel: DtnViewModel,
    contact: IdentityQr.Parsed,
    outgoing: Boolean,
    sharedByName: String,
    maxWidth: Dp,
    onOpenChat: (String) -> Unit,
    onOpenStatus: () -> Unit,
) {
    val peers by viewModel.peers.collectAsState()
    val targetNodeId = NodeId.fromNodeNum(contact.nodeNum).value
    val existing = peers.firstOrNull { it.nodeId == targetNodeId }
    val isAlreadyAdded = existing?.added == true
    val isVerified = existing?.keyVerified == true

    val shortHex = targetNodeId.takeLast(4).lowercase()
    val handle = "${contact.name.ifBlank { "Contact" }}#$shortHex"

    Surface(
        shape = DtnShapes.Card,
        color = DtnColors.Card,
        border = BorderStroke(1.dp, if (outgoing) DtnColors.Primary.copy(alpha = 0.4f) else DtnColors.CardBorder),
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(DtnShapes.Card)
            .combinedClickable(
                onClickLabel = "Message status",
                onLongClickLabel = "Message status",
                onClick = onOpenStatus,
                onLongClick = onOpenStatus,
            ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar Circle
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(DtnColors.PrimarySoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = contact.name.firstOrNull()?.uppercase() ?: "#",
                        style = DtnType.RowTitle.copy(fontWeight = FontWeight.Bold, color = DtnColors.Primary),
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        text = if (outgoing) "Shared contact" else "Shared by $sharedByName · not verified",
                        style = DtnType.Meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = contact.name.ifBlank { "Unknown" },
                        style = DtnType.RowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = handle,
                        style = DtnType.Meta.copy(color = DtnColors.TextSubtitle),
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (isAlreadyAdded) {
                OutlineButton(
                    text = "Message",
                    onClick = { onOpenChat(targetNodeId) },
                    modifier = Modifier.fillMaxWidth().height(DtnSpacing.MainButtonHeight),
                )
            } else {
                PrimaryButton(
                    text = "Add Contact",
                    onClick = {
                        viewModel.importSharedContact(contact) { addedId ->
                            if (addedId != null) onOpenChat(addedId)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(DtnSpacing.MainButtonHeight),
                )
            }
        }
    }
}
