package com.dtn.mesh.ui.shell

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtn.mesh.receiver.TransportKind
import com.dtn.mesh.ui.DtnViewModel
import com.dtn.mesh.ui.chats.ChatPage
import com.dtn.mesh.ui.devtools.DevPage
import com.dtn.mesh.ui.groups.CreateGroupPage
import com.dtn.mesh.ui.groups.GroupPage
import com.dtn.mesh.ui.groups.GroupsPage
import com.dtn.mesh.ui.identity.FirstLaunchPage
import com.dtn.mesh.ui.identity.IdentityPage
import com.dtn.mesh.ui.links.LinkPage
import com.dtn.mesh.ui.links.linkKindOf
import com.dtn.mesh.ui.links.routeName
import com.dtn.mesh.ui.peers.ContactPage
import com.dtn.mesh.ui.OnResume
import com.dtn.mesh.ui.PreflightDialog
import com.dtn.mesh.ui.UiText
import com.dtn.mesh.ui.UserMessageSnackbars
import com.dtn.mesh.ui.components.Avatar
import com.dtn.mesh.ui.components.StatusCard
import com.dtn.mesh.ui.components.StatusPill
import com.dtn.mesh.ui.devtools.DevToolsSubPage
import com.dtn.mesh.ui.devtools.DeveloperToolsPage
import com.dtn.mesh.ui.queue.MessagePage
import com.dtn.mesh.ui.queue.QueuedMessagePage
import com.dtn.mesh.ui.rememberBluetoothEnabler
import com.dtn.mesh.ui.settings.RelayConfigPage
import com.dtn.mesh.ui.settings.SettingsPage
import com.dtn.mesh.ui.theme.DtnColors
import com.dtn.mesh.ui.theme.DtnIcons
import com.dtn.mesh.ui.theme.DtnType

/** Navigation routes (string routes). */
internal object Routes {
    const val TABS = "tabs"
    const val CHAT = "chat/{key}"
    const val CONTACT = "contact/{node}"
    /** The Message page (full trace), by the message id's first 8 characters. */
    const val MESSAGE = "message/{id}"
    /** The Queued message page (live routing math), same id. */
    const val QUEUED = "queued/{id}"
    const val SETTINGS = "settings"
    const val DEV = "dev"
    const val DEV_PAGE = "dev/{page}"
    /** Groups list, one group's page, Create group. */
    const val GROUPS = "groups"
    const val GROUP = "group/{channel}"
    const val GROUP_NEW = "groups/new"
    /** My Identity (QR, node id, safety number, Scan / Upload / Share QR). */
    const val IDENTITY = "identity"
    /** Relay Configuration (Settings › Relay server, Developer Tools, the Internet relay page). */
    const val RELAY = "relay"
    const val TURN = "turn"
    /** A link's page: ble, wifi_direct, hub, internet. */
    const val TRANSPORT = "transport/{kind}"

    fun chat(key: String) = "chat/" + Uri.encode(key)
    fun contact(node: String) = "contact/" + Uri.encode(node)
    fun message(id8: String) = "message/" + Uri.encode(id8)
    fun queued(id8: String) = "queued/" + Uri.encode(id8)
    fun dev(page: String) = "dev/$page"
    fun group(channel: Int) = "group/$channel"
    fun transport(kind: TransportKind) = "transport/" + kind.routeName()
}

/** The four bottom-nav tabs; Network is the start destination. */
internal enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    NETWORK("network", "Network", DtnIcons.Network),
    CHATS("chats", "Chats", DtnIcons.Chats),
    QUEUE("queue", "Queue", DtnIcons.Queue),
    PEERS("peers", "Peers", DtnIcons.Peers),
}

/**
 * The app: First launch while no display name was saved (Continue then opens the shell on
 * Network), otherwise the shell. Nothing is drawn for the moment the saved name is loading.
 */
@Composable
fun DtnApp(viewModel: DtnViewModel) {
    val firstLaunch by viewModel.firstLaunch.collectAsState()
    when (firstLaunch) {
        null -> androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(DtnColors.Background))
        true -> FirstLaunchPage(viewModel)
        false -> DtnShell(viewModel)
    }
}

/**
 * The shell: one NavHost, the tabs as a nested graph that starts on Network, the chat, contact,
 * Message (`message/{id}`) and Queued message (`queued/{id}`) destinations, Groups, My Identity,
 * Settings, Relay Configuration, the link pages and Developer Tools. Back from another tab
 * returns to Network; back on Network leaves the app.
 */
@Composable
private fun DtnShell(viewModel: DtnViewModel) {
    val nav = rememberNavController()
    ChatRouteSync(viewModel, nav)

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.TABS,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        // All four tabs live in ONE destination with one pager: swiping and the bottom nav move the
        // pager, never the back stack (a destination per tab cut every swipe off mid-gesture).
        composable(Routes.TABS) { TabScaffold(viewModel, nav) }
        composable(Routes.CHAT, arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
            val key = entry.arguments?.getString("key").orEmpty()
            // After process death the back stack comes back before the ViewModel's selection does
            // (and back from a contact page re-selects the chat underneath).
            LaunchedEffect(key) { if (viewModel.selectedChat.value != key) viewModel.openChat(key) }
            // System back is the NavHost's own pop; ChatRouteSync then clears the selection.
            ChatPage(
                viewModel, chatKey = key,
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
                onOpenMessage = { nav.openMessage(it) },
                onOpenGroup = { nav.navigate(Routes.group(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.GROUPS) {
            GroupsPage(
                viewModel, onBack = { nav.popBackStack() },
                onOpenGroup = { nav.navigate(Routes.group(it)) { launchSingleTop = true } },
                onCreate = { nav.navigate(Routes.GROUP_NEW) { launchSingleTop = true } },
            )
        }
        composable(Routes.GROUP_NEW) {
            CreateGroupPage(
                viewModel, onBack = { nav.popBackStack() },
                // The new group's page (its invite QR) replaces Create group.
                onCreated = { ch -> nav.navigate(Routes.group(ch)) { popUpTo(Routes.GROUP_NEW) { inclusive = true } } },
            )
        }
        composable(Routes.GROUP, arguments = listOf(navArgument("channel") { type = NavType.IntType })) { entry ->
            val channel = entry.arguments?.getInt("channel") ?: 0
            GroupPage(
                viewModel, channel = channel, onBack = { nav.popBackStack() },
                onOpenChat = { ch ->
                    // Came from that group's chat: go back to it instead of stacking another copy.
                    val below = nav.previousBackStackEntry
                    if (below?.destination?.route == Routes.CHAT && below.arguments?.getString("key") == "grp:$ch") nav.popBackStack()
                    else viewModel.openChat("grp:$ch")
                },
                onLeft = { nav.popOffGroup(channel) },
            )
        }
        composable(Routes.IDENTITY) { IdentityPage(viewModel, onBack = { nav.popBackStack() }) }
        composable(Routes.RELAY) { RelayConfigPage(viewModel, onBack = { nav.popBackStack() }) }
        composable(Routes.TURN) { com.dtn.mesh.ui.settings.TurnConfigPage(viewModel, onBack = { nav.popBackStack() }) }
        composable(Routes.TRANSPORT, arguments = listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
            val kind = linkKindOf(entry.arguments?.getString("kind").orEmpty())
            LinkPage(
                viewModel, kind = kind, onBack = { nav.popBackStack() },
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
                onOpenRelay = { nav.navigate(Routes.RELAY) { launchSingleTop = true } },
                onOpenLinkPage = { nav.navigate(Routes.transport(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.MESSAGE, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            MessagePage(viewModel, msgId8 = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.QUEUED, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            QueuedMessagePage(
                viewModel, msgId8 = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenMessage = { nav.openMessage(it) },
            )
        }
        composable(Routes.CONTACT, arguments = listOf(navArgument("node") { type = NavType.StringType })) { entry ->
            val node = entry.arguments?.getString("node").orEmpty()
            ContactPage(
                viewModel = viewModel,
                nodeId = node,
                onBack = { nav.popBackStack() },
                onOpenChat = { id ->
                    // Came from that chat: go back to it instead of stacking another copy.
                    val below = nav.previousBackStackEntry
                    if (below?.destination?.route == Routes.CHAT && below.arguments?.getString("key") == id) nav.popBackStack()
                    else viewModel.openChat(id)
                },
                onClose = { nav.popOffConversation() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsPage(
                viewModel = viewModel,
                onBack = { nav.popBackStack() },
                onOpenDevTools = { nav.navigate(Routes.DEV) },
                onOpenDevPage = { nav.navigate(Routes.dev(it)) },
                onOpenIdentity = { nav.navigate(Routes.IDENTITY) { launchSingleTop = true } },
                onOpenGroups = { nav.navigate(Routes.GROUPS) { launchSingleTop = true } },
                onOpenRelay = { nav.navigate(Routes.RELAY) { launchSingleTop = true } },
                onOpenTurn = { nav.navigate(Routes.TURN) { launchSingleTop = true } },
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
            )
        }
        composable(Routes.DEV) {
            DeveloperToolsPage(onBack = { nav.popBackStack() }, onOpen = { page ->
                if (page == DevPage.RELAY) nav.navigate(Routes.RELAY) { launchSingleTop = true } else nav.navigate(Routes.dev(page))
            })
        }
        composable(Routes.DEV_PAGE, arguments = listOf(navArgument("page") { type = NavType.StringType })) { entry ->
            DevToolsSubPage(
                viewModel = viewModel,
                page = entry.arguments?.getString("page").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpen = { nav.navigate(Routes.dev(it)) },
                onOpenMessage = { nav.openMessage(it) },
                onOpenContact = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } },
            )
        }
    }

    InAppNotificationOverlay(
        viewModel = viewModel,
        onOpenChat = { key -> viewModel.openChat(key) },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
    }

    // The "before connecting" checklist can open from any Connect button.
    val showPreflight by viewModel.showPreflight.collectAsState()
    val requestBluetoothOn = rememberBluetoothEnabler(viewModel)
    if (showPreflight) PreflightDialog(viewModel, requestBluetoothOn)

    val incomingLink by viewModel.incomingLinkPrompt.collectAsState()
    if (incomingLink != null) {
        val link = incomingLink!!
        when (link) {
            is com.dtn.mesh.crypto.DeepLinkParser.ParsedLink.Contact -> {
                val hexTag = "%08x".format(link.parsed.nodeNum).takeLast(4).lowercase()
                com.dtn.mesh.ui.components.ConfirmDialog(
                    title = "Add contact",
                    text = "Add ${link.parsed.name.ifBlank { "Contact" }} (${link.parsed.name.ifBlank { "Contact" }}#$hexTag) to your contacts?",
                    confirmLabel = "Add contact",
                    dismissLabel = "Cancel",
                    onConfirm = { viewModel.confirmIncomingLink(link) },
                    onDismiss = { viewModel.dismissIncomingLink() },
                )
            }
            is com.dtn.mesh.crypto.DeepLinkParser.ParsedLink.Group -> {
                com.dtn.mesh.ui.components.ConfirmDialog(
                    title = "Join group",
                    text = "Join group “${link.parsed.name}”? Anyone with this invite link can read and send messages in this group.",
                    confirmLabel = "Join group",
                    dismissLabel = "Cancel",
                    onConfirm = { viewModel.confirmIncomingLink(link) },
                    onDismiss = { viewModel.dismissIncomingLink() },
                )
            }
        }
    }
}

/**
 * Keeps `viewModel.selectedChat` (every existing `openChat(...)` call) and the `chat/{key}`
 * destination in step, both ways:
 * - a new selection opens `chat/{key}`; clearing it (the chat's ← arrow, leaving the group) pops it;
 * - the chat leaving the top of the back stack any other way (system back) clears the selection,
 *   so opening the same chat again still navigates.
 */
@Composable
private fun ChatRouteSync(viewModel: DtnViewModel, nav: NavHostController) {
    val selected by viewModel.selectedChat.collectAsState()
    // The selection the previous run saw. It starts null, so a chat restored after process death
    // (back stack back, selection not yet) isn't closed before its destination re-selects it.
    val lastSeen = remember { arrayOfNulls<String>(1) }
    LaunchedEffect(selected) {
        val previous = lastSeen[0]
        lastSeen[0] = selected
        val entry = nav.currentBackStackEntry
        val onChat = entry?.destination?.route == Routes.CHAT
        val openKey = if (onChat) entry?.arguments?.getString("key") else null
        val key = selected
        when {
            key != null && key != openKey -> nav.navigate(Routes.chat(key)) {
                // One chat replaces another instead of stacking up.
                if (onChat) popUpTo(Routes.CHAT) { inclusive = true }
                launchSingleTop = true
            }
            key == null && previous != null && onChat -> nav.popBackStack()
        }
    }
    LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            if (entry.destination.route != Routes.CHAT && viewModel.selectedChat.value != null) viewModel.openChat(null)
        }
    }
}

/** The Message page for one message (8-character id); the same message twice doesn't stack. */
private fun NavHostController.openMessage(id8: String) = navigate(Routes.message(id8)) { launchSingleTop = true }

/** After a Delete / Block on the contact page: leave it and the (now gone) chat under it. */
private fun NavHostController.popOffConversation() {
    while (currentBackStackEntry?.destination?.route.let { it == Routes.CONTACT || it == Routes.CHAT }) {
        if (!popBackStack()) break
    }
}

/** After leaving a group on its page: leave the page, and that group's chat if it's underneath. */
private fun NavHostController.popOffGroup(channel: Int) {
    if (!popBackStack()) return
    val top = currentBackStackEntry
    if (top?.destination?.route == Routes.CHAT && top.arguments?.getString("key") == "grp:$channel") popBackStack()
}

/**
 * The main tabs: top bar, status card (opens Connection links), a swipeable pager of the four tab
 * pages, bottom nav. Back on another tab returns to Network; back on Network leaves the app.
 */
@Composable
private fun TabScaffold(viewModel: DtnViewModel, nav: NavHostController) {
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbars = remember { SnackbarHostState() }
    UserMessageSnackbars(viewModel, snackbars)
    val displayName by viewModel.displayNameSetting.collectAsState()
    val unreadChats by viewModel.totalUnreadCount.collectAsState()
    val queuedCount by viewModel.pendingOutgoingCount.collectAsState()

    val tabs = remember { Tab.entries }
    // The pager is the single source of truth for the selected tab (its page survives rotation
    // and returning from other destinations). currentPage follows the finger mid-swipe, so the
    // bottom nav highlight and status card track the gesture.
    val pagerState = rememberPagerState(initialPage = 0) { tabs.size }
    val tab = tabs[pagerState.currentPage]
    val coroutineScope = rememberCoroutineScope()
    BackHandler(enabled = pagerState.currentPage != 0) {
        coroutineScope.launch { pagerState.animateScrollToPage(0) }
    }

    Scaffold(
        containerColor = DtnColors.Background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            MainTopBar(
                initial = UiText.avatarInitial(displayName),
                onDevTools = { nav.navigate(Routes.DEV) },
                onGroups = { nav.navigate(Routes.GROUPS) { launchSingleTop = true } },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onIdentity = { nav.navigate(Routes.IDENTITY) { launchSingleTop = true } },
            )
        },
        bottomBar = {
            BottomNav(
                current = tab,
                unreadChats = unreadChats,
                queueCount = queuedCount,
                onSelect = { selectedTab ->
                    val targetIndex = tabs.indexOf(selectedTab)
                    if (targetIndex >= 0 && targetIndex != pagerState.targetPage) {
                        coroutineScope.launch { pagerState.animateScrollToPage(targetIndex) }
                    }
                }
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ShellStatus(viewModel, tab, onOpenLinks = { overlay = OVERLAY_LINKS })
            val openContact: (String) -> Unit = { nav.navigate(Routes.contact(it)) { launchSingleTop = true } }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                when (tabs[page]) {
                    Tab.NETWORK -> NetworkTabPage(viewModel, onOpenContact = openContact,
                        onOpenRoutingEngine = { nav.navigate(Routes.dev(DevPage.ENGINE)) },
                        onOpenLinkPage = { nav.navigate(Routes.transport(it)) { launchSingleTop = true } })
                    Tab.CHATS -> ChatsTabPage(viewModel)
                    Tab.QUEUE -> QueueTabPage(
                        viewModel,
                        onOpenQueued = { nav.navigate(Routes.queued(it)) { launchSingleTop = true } },
                        onOpenMessage = { nav.openMessage(it) },
                    )
                    Tab.PEERS -> PeersTabPage(viewModel, onOpenContact = openContact,
                        onShowMyQr = { nav.navigate(Routes.IDENTITY) { launchSingleTop = true } })
                }
            }
        }
    }

    if (overlay == OVERLAY_LINKS) {
        ConnectionLinksSheet(
            viewModel, onDismiss = { overlay = null },
            onOpenLinkPage = { nav.navigate(Routes.transport(it)) { launchSingleTop = true } },
        )
    }
}

private const val OVERLAY_LINKS = "links"

/** "DTN Mesh Relay" on the left; Groups, Settings and the My Identity avatar on the right. */
@Composable
private fun MainTopBar(
    initial: String,
    onDevTools: () -> Unit,
    onGroups: () -> Unit,
    onSettings: () -> Unit,
    onIdentity: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(DtnColors.Background).statusBarsPadding()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append("DTN Mesh ")
                withStyle(SpanStyle(color = DtnColors.Primary)) { append("Relay") }
            },
            style = DtnType.AppTitle,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDevTools) { Icon(DtnIcons.DevTools, contentDescription = "Developer Tools", tint = DtnColors.TextTitle) }
        IconButton(onClick = onGroups) { Icon(DtnIcons.Groups, contentDescription = "Groups", tint = DtnColors.TextTitle) }
        IconButton(onClick = onSettings) { Icon(DtnIcons.Settings, contentDescription = "Settings", tint = DtnColors.TextTitle) }
        IconButton(onClick = onIdentity, modifier = Modifier.semantics { contentDescription = "My identity" }) {
            Avatar(initial, DtnColors.Primary, size = 36.dp)
        }
    }
}

@Composable
private fun BottomNav(
    current: Tab,
    unreadChats: Int,
    queueCount: Int,
    onSelect: (Tab) -> Unit,
) {
    Column {
        HorizontalDivider(color = DtnColors.CardBorder)
        NavigationBar(containerColor = DtnColors.Card, tonalElevation = 0.dp) {
            Tab.entries.forEach { tab ->
                val badgeCount = when (tab) {
                    Tab.CHATS -> unreadChats
                    Tab.QUEUE -> queueCount
                    else -> 0
                }
                NavigationBarItem(
                    selected = tab == current,
                    onClick = { onSelect(tab) },
                    icon = {
                        if (badgeCount > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = if (tab == Tab.CHATS) DtnColors.Primary else DtnColors.CardBorder,
                                        contentColor = Color.White,
                                    ) {
                                        Text(if (badgeCount > 99) "99+" else "$badgeCount")
                                    }
                                }
                            ) {
                                Icon(tab.icon, contentDescription = null)
                            }
                        } else {
                            Icon(tab.icon, contentDescription = null)
                        }
                    },
                    label = { Text(tab.label, fontWeight = if (tab == current) FontWeight.SemiBold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DtnColors.Primary,
                        selectedTextColor = DtnColors.Primary,
                        indicatorColor = DtnColors.PrimarySoft,
                        unselectedIconColor = DtnColors.TextSubtitle,
                        unselectedTextColor = DtnColors.TextSubtitle,
                    ),
                )
            }
        }
    }
}

/**
 * The status card under the top bar (tap → Connection links): the full card on Network (links in
 * use, node, the active algorithm read-only, buffer), link state + queued count on Chats, a small
 * pill on Queue and Peers.
 */
@Composable
private fun ShellStatus(viewModel: DtnViewModel, tab: Tab, onOpenLinks: () -> Unit) {
    // Re-read radios + permissions on return to the app (back from system settings), on every
    // tab, as the old always-visible connection card did.
    OnResume { viewModel.refreshRadios() }
    val snapshot = rememberLinkSnapshot(viewModel)
    val nodeId by viewModel.localNodeId.collectAsState()
    val strategy by viewModel.activeStrategy.collectAsState()
    val buffered by viewModel.bufferedCount.collectAsState()
    val h = snapshot.headline
    val tone = h.health.tone()
    val queued = if (buffered == 1) "1 message queued" else "$buffered messages queued"
    val pad = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    when (tab) {
        Tab.NETWORK -> StatusCard(
            pill = h.pill(), tone = tone, onClick = onOpenLinks, modifier = pad,
            meta = listOf(h.detail, "Node ${nodeId?.takeLast(8) ?: "—"}", strategy, "Buffer $buffered").joinToString(" · "),
        )
        Tab.CHATS -> StatusCard(pill = h.pill(), tone = tone, onClick = onOpenLinks, modifier = pad, meta = h.detail) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (h.health == com.dtn.mesh.ui.LinkHealth.ON) queued else "No live link · $queued",
                style = DtnType.Meta, fontWeight = FontWeight.SemiBold,
            )
        }
        Tab.QUEUE, Tab.PEERS -> Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(onClickLabel = "Show connection links", role = Role.Button, onClick = onOpenLinks)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(h.pill(), tone)
            Spacer(Modifier.width(10.dp))
            Text("$queued ›", style = DtnType.Meta, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}


@Composable
private fun InAppNotificationOverlay(
    viewModel: DtnViewModel,
    onOpenChat: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeBanner by remember { mutableStateOf<com.dtn.mesh.service.MessageNotifier.InAppBanner?>(null) }
    val currentOpenChat by viewModel.selectedChat.collectAsState()

    LaunchedEffect(Unit) {
        // collectLatest: a newer message replaces the banner at once (collect queued every banner
        // behind the previous one's 3.5 s, so a burst kept banners coming for minutes).
        viewModel.inAppBanner.collectLatest { banner ->
            // Suppress banner if user is actively viewing this exact chat
            if (currentOpenChat != banner.convKey) {
                activeBanner = banner
                delay(3500)
                if (activeBanner?.id == banner.id) {
                    activeBanner = null
                }
            }
        }
    }

    AnimatedVisibility(
        visible = activeBanner != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier.zIndex(99f),
    ) {
        val banner = activeBanner ?: return@AnimatedVisibility
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = DtnColors.Card,
                contentColor = DtnColors.TextTitle,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val key = banner.convKey
                    activeBanner = null
                    onOpenChat(key)
                },
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Sender Avatar / Pill
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(DtnColors.PrimarySoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = banner.senderTitle.firstOrNull()?.uppercase() ?: "#",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DtnColors.Primary,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = banner.senderTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = DtnColors.TextTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = banner.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = DtnColors.TextSubtitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { activeBanner = null },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = DtnIcons.Close,
                        contentDescription = "Dismiss",
                        tint = DtnColors.TextHint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
