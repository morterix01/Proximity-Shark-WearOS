package com.luis.ducky_android

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class LibraryItem(
    val name: String,
    val isDir: Boolean,
    val path: String = "",
    val children: List<LibraryItem> = emptyList()
)

data class BluetoothDeviceItem(val name: String, val address: String)

data class SharkState(
    val connectedAddress: String? = null,
    val connectionStatus: Int = 0,
    val activeLayout: String = "pc",
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
                onLayoutChange = { layout -> sendMessageToPhone("/set_layout", layout) }
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
                val jsonStr = map.getString("library_json") ?: return
                scope.launch {
                    try {
                        val json = JSONObject(jsonStr)
                        rootItem = parseJson(json)
                        navigationStack.clear()
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } else if (path == "/shark_state") {
                val jsonStr = map.getString("state_json") ?: return
                scope.launch {
                    try {
                        val json = JSONObject(jsonStr)
                        val connectedAddress = json.optString("connectedAddress", null).takeIf { it != "null" }
                        val connectionStatus = json.optInt("connectionStatus", 0)
                        val activeLayout = json.optString("activeLayout", "pc")
                        val devicesArray = json.optJSONArray("bondedDevices")
                        val devices = mutableListOf<BluetoothDeviceItem>()
                        if (devicesArray != null) {
                            for (i in 0 until devicesArray.length()) {
                                val d = devicesArray.getJSONObject(i)
                                devices.add(BluetoothDeviceItem(d.getString("name"), d.getString("address")))
                            }
                        }
                        sharkState = SharkState(connectedAddress, connectionStatus, activeLayout, devices)
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
                    executionProgress = if (p >= 1.0f) null else p
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
        // Set initial progress to show the bar
        executionProgress = 0.01f
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
    onLayoutChange: (String) -> Unit
) {
    val currentItem = navStack.lastOrNull() ?: rootItem
    val sharkBlue = Color(0xFF00B0FF)
    
    // Virtual infinite pager, starting roughly at the middle so user can swipe both ways forever
    val pageCount = Int.MAX_VALUE
    val startPage = pageCount / 2
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })

    MaterialTheme {
        Scaffold(
            timeText = { if (progress == null) TimeText() },
            pageIndicator = {
                if (progress == null) {
                    HorizontalPageIndicator(
                        pageIndicatorState = object : PageIndicatorState {
                            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
                            override val selectedPage: Int get() = pagerState.currentPage % 3
                            override val pageCount: Int get() = 3
                        }
                    )
                }
            }
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (progress != null) {
                    ProgressOverlay(progress, sharkBlue)
                } else {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().background(Color.Black)) { page ->
                        val virtualPage = page % 3
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
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceList(state: SharkState, onConnect: (String) -> Unit, sharkBlue: Color) {
    val listState = rememberScalingLazyListState()
    val context = LocalContext.current
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    
    // Keep track of the device we recently tapped
    var connectingAddress by remember { mutableStateOf<String?>(null) }
    
    // Auto-clear connectingAddress if state updates to Connected or Disconnected
    LaunchedEffect(state.connectionStatus, state.connectedAddress) {
        if (state.connectionStatus == 1 || state.connectionStatus == 0) {
            connectingAddress = null
        }
    }
    
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
    ) {
        item {
            Text("Dispositivi", style = MaterialTheme.typography.caption1, color = sharkBlue, modifier = Modifier.padding(bottom = 8.dp))
        }
        
        items(state.bondedDevices) { device ->
            val isConnected = state.connectionStatus == 1 && state.connectedAddress == device.address
            val isConnecting = connectingAddress == device.address
            
            val subtitleStr = when {
                isConnected -> "✓ Connesso"
                isConnecting -> "⏳ Connessione in corso..."
                else -> ""
            }
            
            Chip(
                onClick = {
                    vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                    connectingAddress = device.address
                    onConnect(device.address)
                },
                label = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = if (subtitleStr.isNotEmpty()) {
                    { Text(subtitleStr, color = if (isConnected) Color.Green else Color.LightGray) }
                } else null,
                colors = if (isConnected) ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF1B5E20)) 
                         else ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun LayoutList(state: SharkState, onLayoutChange: (String) -> Unit, sharkBlue: Color) {
    val listState = rememberScalingLazyListState()
    
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
    ) {
        item {
            Text("Layout Tastiera", style = MaterialTheme.typography.caption1, color = sharkBlue, modifier = Modifier.padding(bottom = 8.dp))
        }
        val layouts = listOf("pc" to "PC (IT)", "android" to "Android (US)", "androidIt" to "Android (IT)")
        items(layouts) { (layoutKey, layoutName) ->
            val isActive = state.activeLayout == layoutKey
            Chip(
                onClick = { onLayoutChange(layoutKey) },
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
    item: LibraryItem,
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
        contentPadding = PaddingValues(top = 32.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
    ) {
        item {
            Text(
                text = item.name,
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

        items(item.children) { child ->
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
        
        if (item.children.isEmpty() && !hasBack) {
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
fun ProgressOverlay(progress: Float, sharkBlue: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize().padding(12.dp),
            startAngle = 270f,
            indicatorColor = sharkBlue,
            trackColor = Color.DarkGray,
            strokeWidth = 6.dp
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Esecuzione...", style = MaterialTheme.typography.caption1)
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.title2, color = sharkBlue)
        }
    }
}
