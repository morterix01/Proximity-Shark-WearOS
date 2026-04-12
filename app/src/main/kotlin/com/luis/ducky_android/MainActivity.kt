package com.luis.ducky_android

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
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
                onLayoutChange = { layout -> sendMessageToPhone("/set_layout", layout) },
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
                        val activeLayout = json.optString("activeLayout", "pc")
                        val devicesArray = json.optJSONArray("bondedDevices")
                        val devices = mutableListOf<BluetoothDeviceItem>()
                        if (devicesArray != null) {
                            for (i in 0 until devicesArray.length()) {
                                val d = devicesArray.getJSONObject(i)
                                devices.add(BluetoothDeviceItem(d.getString("name"), d.getString("address")))
                            }
                        }
                        withContext(Dispatchers.Main) {
                            sharkState = SharkState(connectedAddress, connectionStatus, activeLayout, devices)
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
    onExecutionFinished: () -> Unit
) {
    val currentItem = navStack.lastOrNull() ?: rootItem
    val sharkBlue = Color(0xFF00B0FF)

    // Auto-dismiss progress after 3 seconds if it's a final state (1.0 or -1.0)
    LaunchedEffect(progress) {
        if (progress == 1.0f || progress == -1.0f) {
            delay(3000)
            onExecutionFinished()
        }
    }
    
    // Virtual infinite pager, starting roughly at the middle so user can swipe both ways forever
    val pageCount = Int.MAX_VALUE
    val startPage = pageCount / 2
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pageCount })

    MaterialTheme {
        Scaffold(
            timeText = { if (progress != 1.0f && progress != -1.0f) TimeText() },
            pageIndicator = {
                if (progress != 1.0f && progress != -1.0f) {
                    val indicatorState = remember {
                        object : PageIndicatorState {
                            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
                            override val selectedPage: Int get() = pagerState.currentPage % 3
                            override val pageCount: Int get() = 3
                        }
                    }
                    HorizontalPageIndicator(pageIndicatorState = indicatorState)
                }
            }
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (progress == 1.0f || progress == -1.0f) {
                    ResultOverlay(progress, sharkBlue)
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
    val vibrator = remember { context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    
    // Keep track of the device we recently tapped
    var connectingAddress by remember { mutableStateOf<String?>(null) }
    
    // Auto-clear connectingAddress if state updates to Connected or Disconnected
    LaunchedEffect(state.connectionStatus, state.connectedAddress) {
        if (state.connectionStatus == 1 || state.connectionStatus == 0) {
            connectingAddress = null
        }
    }

    // Backup timeout: if no state change happens in 15s, clear the loading state
    LaunchedEffect(connectingAddress) {
        if (connectingAddress != null) {
            delay(15000)
            connectingAddress = null
        }
    }
    
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
            items = state.bondedDevices,
            key = { it.address }
        ) { device ->
            val isConnected = state.connectionStatus == 1 && state.connectedAddress == device.address
            val isConnecting = connectingAddress == device.address
            
            val subtitleStr = when {
                isConnected -> "✓ Connesso"
                isConnecting -> "⏳ Connessione in corso..."
                else -> ""
            }
            
            Chip(
                onClick = {
                    try {
                        if (vibrator?.hasVibrator() == true) {
                            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    } catch (e: Exception) {}
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
    val layouts = remember { 
        listOf(
            "pc" to "PC (IT)", 
            "android" to "Standard (US)",
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
            key = { it.path }
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
