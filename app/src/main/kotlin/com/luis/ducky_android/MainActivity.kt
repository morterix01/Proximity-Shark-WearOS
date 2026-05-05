package com.luis.ducky_android

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.CircularProgressIndicator
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

import androidx.compose.runtime.Immutable

@Immutable
data class LibraryItem(
    val name: String,
    val isDir: Boolean,
    val path: String = "",
    val children: List<LibraryItem> = emptyList()
)

@Immutable
data class BluetoothDeviceItem(val name: String, val address: String)

@Immutable
data class SharkState(
    val connectedAddress: String? = null,
    val connectionStatus: Int = 0, // 0 = disc, 1 = conn
    val isConnecting: Boolean = false,
    val connectingAddress: String? = null,
    val activeLayout: String = "pc",
    val panicEndTimeMillis: Long? = null,
    val bondedDevices: List<BluetoothDeviceItem> = emptyList()
)

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private val sharkBlue = Color(0xFF00B0FF)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // State
    private var rootItem by mutableStateOf<LibraryItem?>(null)
    private var navigationStack = mutableStateListOf<LibraryItem>()
    private var executionProgress by mutableStateOf<Float?>(null)
    private var sharkState by mutableStateOf(SharkState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        
        // Initial fetch
        scope.launch {
            try {
                val dataItems = Wearable.getDataClient(this@MainActivity).dataItems.await()
                for (item in dataItems) {
                    if (item.uri.path == "/library" || item.uri.path == "/shark_state") {
                        updateStateFromDataItem(item)
                    }
                }
                dataItems.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            WearApp(
                rootItem = rootItem,
                navStack = navigationStack,
                progress = executionProgress,
                sharkState = sharkState,
                onFolderClick = { folder -> navigationStack.add(folder) },
                onFileClick = { file -> runScript(file.path) },
                onBack = { if (navigationStack.isNotEmpty()) navigationStack.removeLast() },
                onDeviceConnect = { address -> sendMessageToPhone("/connect_device", address) },
                onLayoutChange = { layout -> sendMessageToPhone("/set_layout", layout) },
                onPanic = { sendMessageToPhone("/panic", "") },
                onTaskkill = { sendMessageToPhone("/taskkill", "") },
                onShutdown = { sendMessageToPhone("/shutdown", "") },
                onNavRotary = { direction -> sendMessageToPhone("/nav", direction) },
                onExecutionFinished = { executionProgress = null }
            )
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                try {
                    updateStateFromDataItem(event.dataItem)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun updateStateFromDataItem(dataItem: DataItem) {
        try {
            val map = DataMapItem.fromDataItem(dataItem).dataMap
            val path = dataItem.uri.path
            
            if (path == "/library") {
                val b64 = map.getString("library_json_b64")
                val jsonStr = map.getString("library_json")
                scope.launch(Dispatchers.IO) {
                    try {
                        val finalJsonStr = if (b64 != null) {
                            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            java.util.zip.GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() }
                        } else {
                            jsonStr
                        }
                        
                        if (finalJsonStr != null) {
                            val json = JSONObject(finalJsonStr)
                            val newRootItem = parseJson(json)
                            withContext(Dispatchers.Main) {
                                rootItem = newRootItem
                                navigationStack.clear()
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } else if (path == "/shark_state") {
                val jsonStr = map.getString("state_json") ?: return
                scope.launch(Dispatchers.IO) {
                    try {
                        val json = JSONObject(jsonStr)
                        val connectedAddress = json.optString("connectedAddress", null).takeIf { it != "null" }
                        val connectionStatus = json.optInt("connectionStatus", 0)
                        val isConnecting = json.optBoolean("isConnecting", false)
                        val connectingAddress = json.optString("connectingAddress", null).takeIf { it != "null" }
                        val activeLayout = json.optString("activeLayout", "pc")
                        val panicEndTimeMillis = if (json.has("panicEndTimeMillis") && !json.isNull("panicEndTimeMillis")) json.getLong("panicEndTimeMillis") else null
                        val devicesArray = json.optJSONArray("bondedDevices")
                        val devices = mutableListOf<BluetoothDeviceItem>()
                        if (devicesArray != null) {
                            for (i in 0 until devicesArray.length()) {
                                val d = devicesArray.getJSONObject(i)
                                devices.add(BluetoothDeviceItem(d.getString("name"), d.getString("address")))
                            }
                        }
                        withContext(Dispatchers.Main) {
                            sharkState = SharkState(connectedAddress, connectionStatus, isConnecting, connectingAddress, activeLayout, panicEndTimeMillis, devices)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onMessageReceived(message: MessageEvent) {
        try {
            if (message.path == "/progress") {
                val progressStr = String(message.data, StandardCharsets.UTF_8)
                val p = progressStr.toFloatOrNull() ?: 0f
                scope.launch {
                    // We only update if it's an error (-1.0f).
                    // Success (1.0f) is ignored here because we already showed instant success in runScript()
                    // to avoid showing the same notification twice.
                    if (p == -1.0f) {
                        executionProgress = p
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseJson(json: JSONObject): LibraryItem {
        val name = json.getString("name")
        val isDir = json.getBoolean("isDir")
        val path = json.optString("path", "")
        val childrenJson = json.optJSONArray("children")
        val children = mutableListOf<LibraryItem>()
        if (childrenJson != null) {
            for (i in 0 until childrenJson.length()) {
                children.add(parseJson(childrenJson.getJSONObject(i)))
            }
        }
        return LibraryItem(name, isDir, path, children)
    }

    private fun runScript(path: String) {
        sendMessageToPhone("/run_script", path)
        // Instant feedback: show success immediately upon sending
        executionProgress = 1.0f
    }
    
    private fun sendMessageToPhone(path: String, payload: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity).sendMessage(
                        node.id, path, payload.toByteArray(StandardCharsets.UTF_8)
                    ).await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        scope.cancel()
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WearApp(
    rootItem: LibraryItem?,
    navStack: List<LibraryItem>,
    progress: Float?,
    sharkState: SharkState,
    onFolderClick: (LibraryItem) -> Unit,
    onFileClick: (LibraryItem) -> Unit,
    onBack: () -> Unit,
    onDeviceConnect: (String) -> Unit,
    onLayoutChange: (String) -> Unit,
    onPanic: () -> Unit,
    onTaskkill: () -> Unit,
    onShutdown: () -> Unit,
    onNavRotary: (String) -> Unit,
    onExecutionFinished: () -> Unit
) {
    val currentItem = navStack.lastOrNull() ?: rootItem
    val sharkBlue = Color(0xFF00B0FF)
    val panicRed = Color(0xFFFF3B3B)

    // Auto-dismiss progress after 3 seconds if it's a final state (1.0 or -1.0)
    LaunchedEffect(progress) {
        if (progress == 1.0f || progress == -1.0f) {
            delay(3000)
            onExecutionFinished()
        }
    }
    
    // 4 pages: Library (0), Devices (1), Layout (2), Panic (3)
    val totalPages = 4
    val pageCount = Int.MAX_VALUE
    val startPage = (pageCount / 2 / totalPages) * totalPages // align to 0 mod totalPages
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Request focus for rotary input on launch
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    MaterialTheme {
        Scaffold(
            timeText = { if (progress != 1.0f && progress != -1.0f) TimeText() },
            pageIndicator = {
                if (progress != 1.0f && progress != -1.0f) {
                    val indicatorState = remember {
                        object : PageIndicatorState {
                            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
                            override val selectedPage: Int get() = pagerState.currentPage % totalPages
                            override val pageCount: Int get() = totalPages
                        }
                    }
                    HorizontalPageIndicator(pageIndicatorState = indicatorState)
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // ── Rotary / Digital Crown support ─────────────────────────
                    .onRotaryScrollEvent { event ->
                        val direction = if (event.verticalScrollPixels > 0) "next" else "prev"
                        scope.launch {
                            // Navigate the pager
                            val target = if (event.verticalScrollPixels > 0)
                                pagerState.currentPage + 1
                            else
                                pagerState.currentPage - 1
                            pagerState.animateScrollToPage(target)
                        }
                        // Also notify the phone app to move its tab
                        onNavRotary(if (event.verticalScrollPixels > 0) "next" else "prev")
                        true
                    }
                    .focusRequester(focusRequester)
                    .focusable()
            ) {
                if (progress == 1.0f || progress == -1.0f) {
                    ResultOverlay(progress, sharkBlue)
                } else {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().background(Color.Black)) { page ->
                        val virtualPage = page % totalPages
                        when (virtualPage) {
                            0 -> { // SCRIPT LIBRARY
                                if (currentItem == null) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Sincronizzazione...", textAlign = TextAlign.Center)
                                    }
                                } else {
                                    LibraryList(currentItem, navStack.isNotEmpty(), onFolderClick, onFileClick, onBack, sharkBlue)
                                }
                            }
                            1 -> { // BLUETOOTH DEVICES
                                DeviceList(sharkState, onDeviceConnect, sharkBlue)
                            }
                            2 -> { // LAYOUT LIST
                                LayoutList(sharkState, onLayoutChange, sharkBlue)
                            }
                            3 -> { // PANIC & TASKKILL & SHUTDOWN
                                PanicContainer(
                                    onPanic = onPanic,
                                    onTaskkill = onTaskkill,
                                    onShutdown = onShutdown,
                                    panicEndTimeMillis = sharkState.panicEndTimeMillis,
                                    panicRed = panicRed
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PanicContainer(
    onPanic: () -> Unit,
    onTaskkill: () -> Unit,
    onShutdown: () -> Unit,
    panicEndTimeMillis: Long?,
    panicRed: Color
) {
    val totalVerticalPages = 3
    val verticalPageCount = Int.MAX_VALUE
    val startVerticalPage = (verticalPageCount / 2 / totalVerticalPages) * totalVerticalPages
    val pagerState = rememberPagerState(initialPage = startVerticalPage, pageCount = { verticalPageCount })
    
    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        val virtualPage = page % totalVerticalPages
        when (virtualPage) {
            0 -> PanicPage(onPanic = onPanic, panicEndTimeMillis = panicEndTimeMillis, panicRed = panicRed)
            1 -> TaskkillPage(onTaskkill = onTaskkill)
            2 -> ShutdownPage(onShutdown = onShutdown)
        }
    }
}

// ─── Panic Page ──────────────────────────────────────────────────────────────
@Composable
fun PanicPage(onPanic: () -> Unit, panicEndTimeMillis: Long?, panicRed: Color) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    var fired by remember { mutableStateOf(false) }

    // Timer for UI refresh
    val now = remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now.value = System.currentTimeMillis()
        }
    }

    // Reset fired state after 2s
    LaunchedEffect(fired) {
        if (fired) {
            delay(2000)
            fired = false
        }
    }

    // Pulse animation on the ring
    val infiniteTransition = rememberInfiniteTransition(label = "panicPulse")
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "ringAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "⚡ PANIC",
                style = MaterialTheme.typography.title2,
                fontWeight = FontWeight.Bold,
                color = if (fired) Color.Green else panicRed,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // Big circular button
            Button(
                onClick = {
                    try {
                        if (vibrator?.hasVibrator() == true) {
                            vibrator.vibrate(
                                VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 120), -1)
                            )
                        }
                    } catch (e: Exception) {}
                    fired = true
                    onPanic()
                },
                modifier = Modifier.size(80.dp),
                colors = ButtonDefaults.primaryButtonColors(
                    backgroundColor = if (fired) Color(0xFF1B5E20) else panicRed.copy(alpha = ringAlpha)
                )
            ) {
                Text(
                    if (fired) "✓" else "!",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
            
            val remaining = (panicEndTimeMillis ?: 0L) - now.value
            if (remaining > 0) {
                val minutes = remaining / 60000
                val seconds = (remaining % 60000) / 1000
                Text(
                    text = String.format("%02d:%02d", minutes, seconds),
                    style = MaterialTheme.typography.caption2,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    if (fired) "Inviato!" else "CTRL+ALT+B",
                    style = MaterialTheme.typography.caption2,
                    color = if (fired) Color.Green else panicRed.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// ─── Taskkill Page ──────────────────────────────────────────────────────────
@Composable
fun TaskkillPage(onTaskkill: () -> Unit) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    val taskkillOrange = Color(0xFFFF5722)
    var fired by remember { mutableStateOf(false) }

    LaunchedEffect(fired) {
        if (fired) {
            delay(2000)
            fired = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "☠️ TASKKILL",
                style = MaterialTheme.typography.title2,
                fontWeight = FontWeight.Bold,
                color = if (fired) Color.Green else taskkillOrange,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = {
                    try {
                        if (vibrator?.hasVibrator() == true) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    } catch (e: Exception) {}
                    fired = true
                    onTaskkill()
                },
                modifier = Modifier.size(80.dp),
                colors = ButtonDefaults.primaryButtonColors(
                    backgroundColor = if (fired) Color(0xFF1B5E20) else taskkillOrange
                )
            ) {
                Text(
                    if (fired) "✓" else "☠",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
            Text(
                if (fired) "Inviato!" else "CHIUDI PROCESSI",
                style = MaterialTheme.typography.caption2,
                color = if (fired) Color.Green else taskkillOrange.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
fun DeviceList(state: SharkState, onConnect: (String) -> Unit, sharkBlue: Color) {
    val listState = rememberScalingLazyListState()
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    
    // Keep track of the device we recently tapped locally until the phone confirms
    var watchInitiatedConnectingAddress by remember { mutableStateOf<String?>(null) }
    
    // Auto-clear watchInitiatedConnectingAddress if phone state confirms connection or disconnection
    LaunchedEffect(state.connectionStatus, state.isConnecting, state.connectingAddress) {
        if (state.connectionStatus == 1 || state.isConnecting) {
            // If phone is already connecting, we can clear our local one if it matches
            if (state.connectingAddress == watchInitiatedConnectingAddress) {
                watchInitiatedConnectingAddress = null
            }
        }
        if (state.connectionStatus == 1 && state.isConnecting == false) {
             // Fully connected
             watchInitiatedConnectingAddress = null
        }
        if (state.connectionStatus == 0 && state.isConnecting == false) {
             // Disconnected
             watchInitiatedConnectingAddress = null
        }
    }

    // Backup timeout: if no state change happens in 25s, clear the loading state
    LaunchedEffect(watchInitiatedConnectingAddress) {
        if (watchInitiatedConnectingAddress != null) {
            delay(25000)
            watchInitiatedConnectingAddress = null
        }
    }

    // Infinite pulse animation for connecting chip border
    val pulseTransition = rememberInfiniteTransition(label = "pulse")
    val pulseBorderAlpha by pulseTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseBorder"
    )
    
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 0),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
    ) {
        item {
            Text("Dispositivi", style = MaterialTheme.typography.caption1, color = sharkBlue, modifier = Modifier.padding(bottom = 8.dp))
        }
        
        items(
            items = state.bondedDevices.filter { 
                it.name.isNotEmpty() && 
                !it.name.equals("Unknown Device", ignoreCase = true) && 
                !it.name.equals("Unknown", ignoreCase = true) 
            },
            key = { it.address }
        ) { device ->
            val isConnected = state.connectionStatus == 1 && state.connectedAddress == device.address
            val isConnecting = (state.isConnecting && state.connectingAddress == device.address) || 
                               (watchInitiatedConnectingAddress == device.address)

            // Animated chip background color: grey → dark teal when connecting
            val chipBgColor by animateColorAsState(
                targetValue = when {
                    isConnected -> Color(0xFF1B5E20)
                    isConnecting -> Color(0xFF0D2137)
                    else -> Color(0xFF1C1C1E)
                },
                animationSpec = tween(durationMillis = 350),
                label = "chipBg_${device.address}"
            )

            // Animated border/label colour
            val statusColor by animateColorAsState(
                targetValue = when {
                    isConnected -> Color.Green
                    isConnecting -> sharkBlue.copy(alpha = pulseBorderAlpha)
                    else -> Color.LightGray
                },
                animationSpec = tween(durationMillis = 300),
                label = "statusColor_${device.address}"
            )

            val subtitleStr = when {
                isConnected -> "✓ Connesso"
                isConnecting -> "⏳ Connessione in corso"
                else -> ""
            }
            
            Chip(
                onClick = {
                    try {
                        if (vibrator?.hasVibrator() == true) {
                            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    } catch (e: Exception) {}
                    watchInitiatedConnectingAddress = device.address
                    onConnect(device.address)
                },
                label = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = {
                    // AnimatedVisibility for smooth fade+expand of the subtitle
                    AnimatedVisibility(
                        visible = subtitleStr.isNotEmpty(),
                        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                        exit  = fadeOut(tween(250)) + shrinkVertically(tween(250))
                    ) {
                        Text(
                            subtitleStr,
                            color = statusColor
                        )
                    }
                },
                colors = ChipDefaults.primaryChipColors(backgroundColor = chipBgColor),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun LayoutList(state: SharkState, onLayoutChange: (String) -> Unit, sharkBlue: Color) {
    val listState = rememberScalingLazyListState()
    val layouts = remember { 
        listOf(
            "pc" to "PC (IT)", 
            "androidIt" to "Android (IT)",
            "usInternational" to "US INTL"
        ) 
    }
    
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 0),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
    ) {
        item {
            Text("Layout Tastiera", style = MaterialTheme.typography.caption1, color = sharkBlue, modifier = Modifier.padding(bottom = 8.dp))
        }
        items(
            items = layouts,
            key = { it.first }
        ) { (layoutKey, layoutName) ->
            val isActive = state.activeLayout == layoutKey
            Chip(
                onClick = {
                    try {
                        if (vibrator?.hasVibrator() == true) {
                            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    } catch (e: Exception) {}
                    onLayoutChange(layoutKey)
                },
                label = { Text(layoutName) },
                secondaryLabel = if (isActive) { { Text("Attivo", color = Color.Green) } } else null,
                colors = if (isActive) ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF1B5E20)) else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun LibraryList(
    libraryItem: LibraryItem,
    hasBack: Boolean,
    onFolderClick: (LibraryItem) -> Unit,
    onFileClick: (LibraryItem) -> Unit,
    onBack: () -> Unit,
    sharkBlue: Color
) {
    val listState = rememberScalingLazyListState()
    
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        autoCentering = AutoCenteringParams(itemIndex = 0),
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
    ) {
        item {
            Text(
                text = libraryItem.name,
                style = MaterialTheme.typography.caption1,
                color = sharkBlue,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Center
            )
        }

        if (hasBack) {
            item {
                Chip(
                    onClick = onBack,
                    label = { Text(".. (Indietro)") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        items(
            items = libraryItem.children,
            key = { it.name }
        ) { child ->
            Chip(
                onClick = { if (child.isDir) onFolderClick(child) else onFileClick(child) },
                label = { Text(child.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { 
                    Text(if (child.isDir) "📁" else "📄") 
                },
                colors = if (child.isDir) ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF1A1A1A)) 
                         else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        if (libraryItem.children.isEmpty() && !hasBack) {
            item {
                Text(
                    "Nessun script trovato",
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun ResultOverlay(progress: Float, sharkBlue: Color) {
    val isSuccess = progress == 1.0f
    
    val bgColor = if (isSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C)

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor).padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (isSuccess) "✅" else "❌",
                fontSize = 48.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = if (isSuccess) "INVIATO!" else "ERRORE!",
                style = MaterialTheme.typography.title2,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = if (isSuccess) "Payload completato" else "Invio fallito",
                style = MaterialTheme.typography.caption2,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

// ─── Shutdown Page ──────────────────────────────────────────────────────────
@Composable
fun ShutdownPage(onShutdown: () -> Unit) {
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    val shutdownRed = Color(0xFFD32F2F)
    var fired by remember { mutableStateOf(false) }

    LaunchedEffect(fired) {
        if (fired) {
            delay(2000)
            fired = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "🔋 SHUTDOWN",
                style = MaterialTheme.typography.title2,
                fontWeight = FontWeight.Bold,
                color = if (fired) Color.Green else shutdownRed,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Button(
                onClick = {
                    try {
                        if (vibrator?.hasVibrator() == true) {
                            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    } catch (e: Exception) {}
                    fired = true
                    onShutdown()
                },
                modifier = Modifier.size(80.dp),
                colors = ButtonDefaults.primaryButtonColors(
                    backgroundColor = if (fired) Color(0xFF1B5E20) else shutdownRed
                )
            ) {
                Text(
                    if (fired) "✓" else "⏻",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
            Text(
                if (fired) "Inviato!" else "SPEGNI PC",
                style = MaterialTheme.typography.caption2,
                color = if (fired) Color.Green else shutdownRed.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
