package com.rekosk.remesh.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rekosk.remesh.ui.components.OverflowNav
import com.rekosk.remesh.ui.screens.AddChannelScreen
import com.rekosk.remesh.ui.screens.AddContactScreen
import com.rekosk.remesh.ui.screens.DiscoverContactsScreen
import com.rekosk.remesh.ui.screens.DiscoverNearbyScreen
import com.rekosk.remesh.ui.screens.MapLocationPickerScreen
import com.rekosk.remesh.ui.screens.NoiseFloorScreen
import com.rekosk.remesh.ui.screens.PacketLogScreen
import com.rekosk.remesh.ui.screens.PathTraceScreen
import com.rekosk.remesh.ui.screens.ScanContactQrScreen
import com.rekosk.remesh.ui.screens.ToolsNav
import com.rekosk.remesh.ui.screens.ToolsScreen
import com.rekosk.remesh.ui.screens.ChannelsScreen
import com.rekosk.remesh.data.model.NodeType
import com.rekosk.remesh.ui.screens.ChatScreen
import com.rekosk.remesh.ui.screens.ContactDetailScreen
import com.rekosk.remesh.ui.screens.SetRouteScreen
import com.rekosk.remesh.ui.screens.ConnectScreen
import com.rekosk.remesh.ui.screens.ContactsScreen
import com.rekosk.remesh.ui.screens.CreatePrivateChannelScreen
import com.rekosk.remesh.ui.screens.HeardRepeatsScreen
import com.rekosk.remesh.ui.screens.MessageRouteScreen
import com.rekosk.remesh.ui.screens.MessageRoutesScreen
import com.rekosk.remesh.ui.screens.JoinHashtagChannelScreen
import com.rekosk.remesh.ui.screens.JoinPrivateChannelScreen
import com.rekosk.remesh.ui.screens.JoinPublicChannelScreen
import com.rekosk.remesh.ui.screens.MapScreen
import com.rekosk.remesh.ui.screens.MapTracePickerScreen
import com.rekosk.remesh.ui.screens.ScanChannelQrScreen
import com.rekosk.remesh.ui.screens.ShareChannelScreen
import com.rekosk.remesh.ui.screens.SettingsNav
import com.rekosk.remesh.ui.screens.SettingsScreen
import com.rekosk.remesh.ui.screens.ShareContactScreen
import com.rekosk.remesh.ui.screens.settings.BluetoothSettingsScreen
import com.rekosk.remesh.ui.screens.settings.ContactSettingsScreen
import com.rekosk.remesh.ui.screens.settings.ExperimentalSettingsScreen
import com.rekosk.remesh.ui.screens.settings.ExportConfigScreen
import com.rekosk.remesh.ui.screens.settings.IdentityKeyScreen
import com.rekosk.remesh.ui.screens.settings.ImportConfigScreen
import com.rekosk.remesh.ui.screens.settings.LocationSettingsScreen
import com.rekosk.remesh.ui.screens.settings.MessageSettingsScreen
import com.rekosk.remesh.ui.screens.settings.NotificationSettingsScreen
import com.rekosk.remesh.ui.screens.settings.TelemetryScreen
import com.rekosk.remesh.ui.screens.settings.TelemetrySettingsScreen

private const val ROUTE_CONTACTS = "contacts"
private const val ROUTE_CHANNELS = "channels"
private const val ROUTE_MAP = "map"
private const val ROUTE_CHAT = "chat"
private const val ROUTE_CONTACT_DETAIL = "contact"
private const val ROUTE_SET_ROUTE = "set_route"
private const val ROUTE_PICK_SELF_LOCATION = "location_picker/self"
private const val ROUTE_PICK_CONTACT_LOCATION = "location_picker/contact"
private const val ROUTE_CONNECT = "connect"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SHARE_QR = "share_qr"
private const val ROUTE_IDENTITY_KEY = "settings/identity"
private const val ROUTE_BLUETOOTH = "settings/bluetooth"
private const val ROUTE_CONTACT_SETTINGS = "settings/contacts"
private const val ROUTE_MESSAGE_SETTINGS = "settings/messages"
private const val ROUTE_NOTIFICATION_SETTINGS = "settings/notifications"
private const val ROUTE_LOCATION_SETTINGS = "settings/location"
private const val ROUTE_TELEMETRY_SETTINGS = "settings/telemetry"
private const val ROUTE_EXPERIMENTAL_SETTINGS = "settings/experimental"
private const val ROUTE_IMPORT_CONFIG = "settings/import"
private const val ROUTE_EXPORT_CONFIG = "settings/export"
private const val ROUTE_TELEMETRY = "settings/telemetry_view"
private const val ROUTE_ADD_CHANNEL = "channels/add"
private const val ROUTE_CREATE_PRIVATE_CHANNEL = "channels/add/create_private"
private const val ROUTE_JOIN_PRIVATE_CHANNEL = "channels/add/join_private"
private const val ROUTE_JOIN_PUBLIC_CHANNEL = "channels/add/join_public"
private const val ROUTE_JOIN_HASHTAG_CHANNEL = "channels/add/join_hashtag"
private const val ROUTE_SCAN_CHANNEL_QR = "channels/add/scan"
private const val ROUTE_HEARD_REPEATS = "heard_repeats"
private const val ROUTE_MESSAGE_ROUTES = "message_routes"
private const val ROUTE_MESSAGE_ROUTE = "message_route"
private const val ROUTE_SHARE_CHANNEL = "channels/share"
private const val ROUTE_ADD_CONTACT = "contacts/add"
private const val ROUTE_SCAN_CONTACT_QR = "contacts/add/scan"
private const val ROUTE_DISCOVER_CONTACTS = "contacts/discover"
private const val ROUTE_TOOLS = "tools"
private const val ROUTE_PATH_TRACE = "tools/path_trace"
private const val ROUTE_MAP_TRACE = "tools/map_trace"
private const val ROUTE_MAP_TRACE_RESULT = "tools/map_trace_result"
private const val ROUTE_PACKET_LOG = "tools/packet_log"
private const val ROUTE_DISCOVER_NEARBY = "tools/discover_nearby"
private const val ROUTE_NOISE_FLOOR = "tools/noise_floor"

/**
 * Route to a node's contact menu from a Discover list. Carries the derived contact id
 * (so a known node shows its full menu) plus the raw identity as query args, so an
 * unknown node can still render + be added. Mirrors MeshRepository's id scheme:
 * "c:" + first 6 bytes of the key, and AdvType ints CHAT=1/REPEATER=2/ROOM=3/SENSOR=4.
 */
private fun nodeDetailRoute(publicKey: ByteArray, type: NodeType, name: String): String {
    fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    val id = "c:" + publicKey.copyOfRange(0, 6).hex()
    val advType = when (type) {
        NodeType.REPEATER -> 2
        NodeType.ROOM -> 3
        NodeType.SENSOR -> 4
        else -> 1
    }
    return "$ROUTE_CONTACT_DETAIL/$id?pk=${publicKey.hex()}&advType=$advType&nm=${Uri.encode(name)}"
}

private enum class TopLevel(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    Contacts(ROUTE_CONTACTS, "Contacts", Icons.Filled.Groups, Icons.Outlined.Groups),
    Channels(ROUTE_CHANNELS, "Channels", Icons.Filled.Tag, Icons.Outlined.Tag),
    Map(ROUTE_MAP, "Map", Icons.Filled.Map, Icons.Outlined.Map),
    Me(ROUTE_CONNECT, "Me", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
}

@Composable
fun ReMeshApp(viewModel: MeshViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showBottomBar = TopLevel.entries.any { top ->
        currentDestination?.hierarchy?.any { it.route == top.route } == true
    }

    Scaffold(
        // The per-screen Scaffolds below own their system-bar insets (TopAppBar handles the
        // status bar, NavigationBar the nav bar), so this outer one must not add them again.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevel.entries.forEach { top ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == top.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(top.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) top.selectedIcon else top.icon,
                                    contentDescription = top.label,
                                )
                            },
                            label = { Text(top.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        // One instance shared by the three top-level screens, so their menus match.
        val overflow = OverflowNav(
            onAddContact = { navController.navigate(ROUTE_ADD_CONTACT) },
            onDiscoverContacts = { navController.navigate(ROUTE_DISCOVER_CONTACTS) },
            onMyContactCode = { navController.navigate(ROUTE_SHARE_QR) },
            onTools = { navController.navigate(ROUTE_TOOLS) },
        )

        NavHost(
            navController = navController,
            startDestination = ROUTE_CONTACTS,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(ROUTE_CONTACTS) {
                ContactsScreen(
                    viewModel = viewModel,
                    overflow = overflow,
                    // Chat contacts open a conversation; everything else (repeaters,
                    // rooms, sensors) opens the detail screen -- they are not messaged.
                    onContactClick = { contact ->
                        if (contact.type == NodeType.CHAT) {
                            navController.navigate("$ROUTE_CHAT/${contact.id}")
                        } else {
                            navController.navigate("$ROUTE_CONTACT_DETAIL/${contact.id}")
                        }
                    },
                )
            }
            composable(ROUTE_CHANNELS) {
                ChannelsScreen(
                    viewModel = viewModel,
                    overflow = overflow,
                    onOpenConversation = { navController.navigate("$ROUTE_CHAT/$it") },
                    onAddChannel = { navController.navigate(ROUTE_ADD_CHANNEL) },
                    onShareChannel = { navController.navigate("$ROUTE_SHARE_CHANNEL/$it") },
                )
            }
            composable(ROUTE_MAP) {
                MapScreen(
                    viewModel = viewModel,
                    overflow = overflow,
                    onOpenContact = { contactId ->
                        navController.navigate("$ROUTE_CONTACT_DETAIL/$contactId")
                    },
                )
            }
            composable(ROUTE_CONNECT) {
                // The "Me" tab: same node list, no back arrow. Opening an offline node
                // jumps to Contacts so its saved data is right there.
                ConnectScreen(
                    viewModel = viewModel,
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onNodeOpened = {
                        navController.navigate(ROUTE_CONTACTS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onOpenShareQr = { navController.navigate(ROUTE_SHARE_QR) },
                    onEditSelfLocation = { navController.navigate(ROUTE_PICK_SELF_LOCATION) },
                    nav = SettingsNav(
                        onIdentityKey = { navController.navigate(ROUTE_IDENTITY_KEY) },
                        onBluetooth = { navController.navigate(ROUTE_BLUETOOTH) },
                        onContacts = { navController.navigate(ROUTE_CONTACT_SETTINGS) },
                        onMessages = { navController.navigate(ROUTE_MESSAGE_SETTINGS) },
                        onNotifications = { navController.navigate(ROUTE_NOTIFICATION_SETTINGS) },
                        onLocation = { navController.navigate(ROUTE_LOCATION_SETTINGS) },
                        onTelemetry = { navController.navigate(ROUTE_TELEMETRY_SETTINGS) },
                        onExperimental = { navController.navigate(ROUTE_EXPERIMENTAL_SETTINGS) },
                        onImportConfig = { navController.navigate(ROUTE_IMPORT_CONFIG) },
                        onExportConfig = { navController.navigate(ROUTE_EXPORT_CONFIG) },
                        onShowTelemetry = { navController.navigate(ROUTE_TELEMETRY) },
                    ),
                )
            }
            composable(ROUTE_SHARE_QR) {
                ShareContactScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }

            val back: () -> Unit = { navController.popBackStack() }
            composable(ROUTE_TELEMETRY) { TelemetryScreen(viewModel, back) }
            composable(ROUTE_IMPORT_CONFIG) { ImportConfigScreen(viewModel, back) }
            composable(ROUTE_EXPORT_CONFIG) { ExportConfigScreen(viewModel, back) }
            composable(ROUTE_IDENTITY_KEY) { IdentityKeyScreen(viewModel, back) }
            composable(ROUTE_BLUETOOTH) { BluetoothSettingsScreen(viewModel, back) }
            composable(ROUTE_CONTACT_SETTINGS) { ContactSettingsScreen(viewModel, back) }
            composable(ROUTE_MESSAGE_SETTINGS) { MessageSettingsScreen(viewModel, back) }
            composable(ROUTE_NOTIFICATION_SETTINGS) { NotificationSettingsScreen(viewModel, back) }
            composable(ROUTE_LOCATION_SETTINGS) { LocationSettingsScreen(viewModel, back) }
            composable(ROUTE_TELEMETRY_SETTINGS) { TelemetrySettingsScreen(viewModel, back) }
            composable(ROUTE_EXPERIMENTAL_SETTINGS) { ExperimentalSettingsScreen(viewModel, back) }
            composable("$ROUTE_CHAT/{conversationId}") { entry ->
                val conversationId = entry.arguments?.getString("conversationId").orEmpty()
                ChatScreen(
                    viewModel = viewModel,
                    conversationId = conversationId,
                    onBack = { navController.popBackStack() },
                    onOpenHeardRepeats = { messageId ->
                        navController.navigate("$ROUTE_HEARD_REPEATS/$conversationId/$messageId")
                    },
                    onShowMessageRoutes = { messageId ->
                        navController.navigate("$ROUTE_MESSAGE_ROUTES/$conversationId/$messageId")
                    },
                    onOpenContact = { navController.navigate("$ROUTE_CONTACT_DETAIL/$conversationId") },
                )
            }
            composable(
                "$ROUTE_CONTACT_DETAIL/{contactId}?pk={pk}&advType={advType}&nm={nm}",
                arguments = listOf(
                    navArgument("pk") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("advType") { type = NavType.IntType; defaultValue = 0 },
                    navArgument("nm") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val id = entry.arguments?.getString("contactId").orEmpty()
                ContactDetailScreen(
                    viewModel = viewModel,
                    contactId = id,
                    onBack = { navController.popBackStack() },
                    onEditRoute = { navController.navigate("$ROUTE_SET_ROUTE/$id") },
                    onEditLocation = { navController.navigate("$ROUTE_PICK_CONTACT_LOCATION/$id") },
                    onSendMessage = { navController.navigate("$ROUTE_CHAT/$id") },
                    unknownName = entry.arguments?.getString("nm"),
                    unknownPublicKeyHex = entry.arguments?.getString("pk"),
                    unknownAdvType = entry.arguments?.getInt("advType") ?: 0,
                )
            }
            composable(ROUTE_PICK_SELF_LOCATION) {
                val (lat, lon) = viewModel.selfLocation() ?: (0 to 0)
                MapLocationPickerScreen(
                    initialLatE6 = lat,
                    initialLonE6 = lon,
                    title = "Set your node's location",
                    onBack = { navController.popBackStack() },
                    onConfirm = { newLat, newLon ->
                        viewModel.setSelfLocation(newLat, newLon) {}
                        navController.popBackStack()
                    },
                )
            }
            composable("$ROUTE_PICK_CONTACT_LOCATION/{contactId}") { entry ->
                val id = entry.arguments?.getString("contactId").orEmpty()
                val (lat, lon) = viewModel.contactLocation(id) ?: (0 to 0)
                MapLocationPickerScreen(
                    initialLatE6 = lat,
                    initialLonE6 = lon,
                    title = "Set contact location",
                    onBack = { navController.popBackStack() },
                    onConfirm = { newLat, newLon ->
                        viewModel.setContactLocation(id, newLat, newLon) {}
                        navController.popBackStack()
                    },
                )
            }
            composable("$ROUTE_SET_ROUTE/{contactId}") { entry ->
                SetRouteScreen(
                    viewModel = viewModel,
                    contactId = entry.arguments?.getString("contactId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("$ROUTE_HEARD_REPEATS/{conversationId}/{messageId}") { entry ->
                HeardRepeatsScreen(
                    viewModel = viewModel,
                    conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                    messageId = entry.arguments?.getString("messageId").orEmpty(),
                    onBack = back,
                )
            }
            composable("$ROUTE_MESSAGE_ROUTES/{conversationId}/{messageId}") { entry ->
                val cid = entry.arguments?.getString("conversationId").orEmpty()
                val mid = entry.arguments?.getString("messageId").orEmpty()
                MessageRoutesScreen(
                    viewModel = viewModel,
                    conversationId = cid,
                    messageId = mid,
                    onOpenRoute = { routeIndex ->
                        navController.navigate("$ROUTE_MESSAGE_ROUTE/$cid/$mid/$routeIndex")
                    },
                    onBack = back,
                )
            }
            composable("$ROUTE_MESSAGE_ROUTE/{conversationId}/{messageId}/{routeIndex}") { entry ->
                MessageRouteScreen(
                    viewModel = viewModel,
                    conversationId = entry.arguments?.getString("conversationId").orEmpty(),
                    messageId = entry.arguments?.getString("messageId").orEmpty(),
                    routeIndex = entry.arguments?.getString("routeIndex")?.toIntOrNull() ?: 0,
                    onOpenContact = { contactId ->
                        navController.navigate("$ROUTE_CONTACT_DETAIL/$contactId")
                    },
                    onBack = back,
                )
            }

            // Every "add channel" form returns to the channel list, not to the menu:
            // the channel the user just joined is what they wanted to see.
            val doneAddingChannel: () -> Unit = {
                navController.popBackStack(ROUTE_CHANNELS, inclusive = false)
            }
            composable(ROUTE_ADD_CHANNEL) {
                AddChannelScreen(
                    onBack = back,
                    onCreatePrivate = { navController.navigate(ROUTE_CREATE_PRIVATE_CHANNEL) },
                    onJoinPrivate = { navController.navigate(ROUTE_JOIN_PRIVATE_CHANNEL) },
                    onJoinPublic = { navController.navigate(ROUTE_JOIN_PUBLIC_CHANNEL) },
                    onJoinHashtag = { navController.navigate(ROUTE_JOIN_HASHTAG_CHANNEL) },
                    onScanQr = { navController.navigate(ROUTE_SCAN_CHANNEL_QR) },
                )
            }
            composable(ROUTE_CREATE_PRIVATE_CHANNEL) {
                CreatePrivateChannelScreen(viewModel, back, doneAddingChannel)
            }
            composable(ROUTE_JOIN_PRIVATE_CHANNEL) {
                JoinPrivateChannelScreen(viewModel, back, doneAddingChannel)
            }
            composable(ROUTE_JOIN_PUBLIC_CHANNEL) {
                JoinPublicChannelScreen(viewModel, back, doneAddingChannel)
            }
            composable(ROUTE_JOIN_HASHTAG_CHANNEL) {
                JoinHashtagChannelScreen(viewModel, back, doneAddingChannel)
            }
            composable(ROUTE_SCAN_CHANNEL_QR) {
                ScanChannelQrScreen(viewModel, back, doneAddingChannel)
            }
            composable(ROUTE_ADD_CONTACT) {
                AddContactScreen(
                    viewModel = viewModel,
                    onBack = back,
                    onScanQr = { navController.navigate(ROUTE_SCAN_CONTACT_QR) },
                )
            }
            composable(ROUTE_SCAN_CONTACT_QR) {
                ScanContactQrScreen(
                    viewModel = viewModel,
                    onBack = back,
                    onDone = { navController.popBackStack(ROUTE_CONTACTS, inclusive = false) },
                )
            }
            composable(ROUTE_DISCOVER_CONTACTS) {
                DiscoverContactsScreen(
                    viewModel = viewModel,
                    onBack = back,
                    onOpenNode = { advert ->
                        navController.navigate(nodeDetailRoute(advert.publicKey, advert.type, advert.name))
                    },
                )
            }
            composable(ROUTE_TOOLS) {
                ToolsScreen(
                    onBack = back,
                    nav = ToolsNav(
                        onPathTrace = { navController.navigate(ROUTE_PATH_TRACE) },
                        onPathTraceMap = {
                            viewModel.clearTracePicks()
                            navController.navigate(ROUTE_MAP_TRACE)
                        },
                        onPacketLog = { navController.navigate(ROUTE_PACKET_LOG) },
                        onDiscoverNearby = { navController.navigate(ROUTE_DISCOVER_NEARBY) },
                        onNoiseFloor = { navController.navigate(ROUTE_NOISE_FLOOR) },
                    ),
                )
            }
            composable(ROUTE_PATH_TRACE) { PathTraceScreen(viewModel, back) }
            composable(ROUTE_MAP_TRACE) {
                MapTracePickerScreen(
                    viewModel = viewModel,
                    onBack = back,
                    onConfirm = { navController.navigate(ROUTE_MAP_TRACE_RESULT) },
                )
            }
            composable(ROUTE_MAP_TRACE_RESULT) {
                PathTraceScreen(
                    viewModel = viewModel,
                    onBack = back,
                    // The map-backed variant; its FAB returns to the picker we came from.
                    onOpenMapPicker = { navController.popBackStack() },
                )
            }
            composable(ROUTE_PACKET_LOG) { PacketLogScreen(viewModel, back) }
            composable(ROUTE_DISCOVER_NEARBY) {
                DiscoverNearbyScreen(
                    viewModel = viewModel,
                    onBack = back,
                    onOpenNode = { node ->
                        navController.navigate(nodeDetailRoute(node.publicKey, node.type, node.name ?: ""))
                    },
                )
            }
            composable(ROUTE_NOISE_FLOOR) { NoiseFloorScreen(viewModel, back) }
            composable("$ROUTE_SHARE_CHANNEL/{channelIndex}") { entry ->
                ShareChannelScreen(
                    viewModel = viewModel,
                    channelIndex = entry.arguments?.getString("channelIndex")?.toIntOrNull() ?: -1,
                    onBack = back,
                )
            }
        }
    }
}
