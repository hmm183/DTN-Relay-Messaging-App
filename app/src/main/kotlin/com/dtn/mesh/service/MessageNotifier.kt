package com.dtn.mesh.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dtn.mesh.R
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages in-app floating banner notifications and Android system tray notifications for
 * incoming DTN messages, mirroring WhatsApp behavior.
 */
@Singleton
class MessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactDao: ContactDao,
    private val groupKeyStore: com.dtn.mesh.crypto.GroupKeyStore,
) {
    data class InAppBanner(
        val id: String,
        val convKey: String,
        val peerNodeId: String?,
        val senderTitle: String,
        val text: String,
        val channel: Int = 0,
    )

    private val _inAppBanners = MutableSharedFlow<InAppBanner>(extraBufferCapacity = 16)
    val inAppBanners: SharedFlow<InAppBanner> = _inAppBanners.asSharedFlow()

    /** Tracked by MainActivity lifecycle to decide between in-app floating banner vs system tray. */
    val isAppInForeground = AtomicBoolean(false)

    /** Key of the chat conversation currently open on screen (null if on tabs or another page). */
    val activeChatKey = AtomicReference<String?>(null)

    companion object {
        const val CHANNEL_ID = "dtn_messages_channel"
        const val EXTRA_OPEN_CHAT_KEY = "extra_open_chat_key"
        private const val NOTIFICATION_ID_OFFSET = 2000
        private const val TAG = "MessageNotifier"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Messages"
            val descriptionText = "Incoming chat and group messages"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    suspend fun notifyIncoming(
        id: String,
        convKey: String,
        peerNodeId: String?,
        channel: Int,
        text: String,
    ) {
        // The UI's key for the chat (broadcasts are stored under NodeId.BROADCAST but the chat page
        // is BROADCAST_CHAT_ID): used for suppression, the banner and the notification tap.
        val chatKey = uiChatKey(convKey)
        // WhatsApp behavior: if user is actively viewing this exact conversation, suppress notifications.
        if (isAppInForeground.get() && activeChatKey.get() == chatKey) {
            return
        }

        // Same precedence as PeerInfo.displayName(): custom > mesh long name > announced name > id.
        suspend fun nameOf(node: String): String {
            val c = runCatching { contactDao.getByNodeId(node) }.getOrNull()
            return c?.customName?.takeIf { it.isNotBlank() }
                ?: c?.longName?.takeIf { it.isNotBlank() }
                ?: c?.announceName?.takeIf { it.isNotBlank() }
                ?: node.takeLast(8)
        }
        val sender = peerNodeId?.let { nameOf(it) }
        val title: String = when {
            channel > 0 -> {
                val group = runCatching { groupKeyStore.groups().firstOrNull { it.channel == channel }?.name }.getOrNull()
                    ?: "Group #$channel"
                if (sender != null) "$sender @ $group" else group
            }
            convKey == NodeId.BROADCAST.value -> if (sender != null) "$sender · Broadcast" else "Broadcast"
            sender != null -> sender
            else -> "New message"
        }

        // A group invite DM carries the group key: never put that text in a notification.
        val snippet = com.dtn.mesh.ui.chats.ContactCardDm.preview(text) ?: com.dtn.mesh.ui.groups.GroupInviteDm.preview(text)
            ?: text.replace('\n', ' ').trim().take(120)

        if (isAppInForeground.get()) {
            // Foreground: Show animated floating banner inside Compose
            _inAppBanners.tryEmit(
                InAppBanner(
                    id = id,
                    convKey = chatKey,
                    peerNodeId = peerNodeId,
                    senderTitle = title,
                    text = snippet,
                    channel = channel,
                )
            )
        } else {
            // Background or screen locked: Show Android system notification tray alert
            postSystemNotification(chatKey, title, snippet)
        }
    }

    private fun uiChatKey(convKey: String): String =
        if (convKey == NodeId.BROADCAST.value) com.dtn.mesh.ui.DtnViewModel.BROADCAST_CHAT_ID else convKey

    private fun postSystemNotification(convKey: String, title: String, snippet: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_CHAT_KEY, convKey)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getActivity(context, convKey.hashCode(), intent, flags)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dtn)
            .setContentTitle(title)
            .setContentText(snippet)
            .setStyle(NotificationCompat.BigTextStyle().bigText(snippet))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            // Lock screen: who/what stays hidden unless the user allows sensitive content.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_dtn)
                    .setContentTitle("DTN Mesh Relay")
                    .setContentText("New message")
                    .build(),
            )
            .build()

        val notificationId = notificationIdFor(convKey)
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }.onFailure { Log.w(TAG, "failed to post system notification", it) }
    }

    /**
     * One notification per chat. Parenthesised on purpose: `h and 0x7FFFFFFF % 10000` parses as
     * `h and 3647` (`%` binds tighter than infix `and`), which collided chats.
     */
    private fun notificationIdFor(convKey: String): Int =
        NOTIFICATION_ID_OFFSET + (convKey.hashCode() and 0x7FFFFFFF) % 10_000

    fun cancelForChat(convKey: String) {
        val notificationId = notificationIdFor(convKey)
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }
}
