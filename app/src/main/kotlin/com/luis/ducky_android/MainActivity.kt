package com.luis.ducky_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
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

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {

    private val sharkBlue = Color(0xFF00B0FF)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // State
    private var rootItem by mutableStateOf<LibraryItem?>(null)
    private var navigationStack = mutableStateListOf<LibraryItem>()
    private var executionProgress by mutableStateOf<Float?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        
        // Initial fetch
        scope.launch {
            try {
                val dataItems = Wearable.getDataClient(this@MainActivity).dataItems.await()
                for (item in dataItems) {
                    if (item.uri.path == "/library") {
                        updateLibrary(item)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        setContent {
            WearApp(
                rootItem = rootItem,
                navStack = navigationStack,
                progress = executionProgress,
                onFolderClick = { folder -> navigationStack.add(folder) },
                onFileClick = { file -> runScript(file.path) },
                onBack = { if (navigationStack.isNotEmpty()) navigationStack.removeLast() }
            )
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == "/library") {
                updateLibrary(event.dataItem)
            }
        }
    }

    override fun onMessageReceived(message: MessageEvent) {
        if (message.path == "/progress") {
            val progressStr = String(message.data, StandardCharsets.UTF_8)
            val p = progressStr.toFloatOrNull() ?: 0f
            executionProgress = if (p >= 1.0f) null else p
        }
    }

    private fun updateLibrary(dataItem: DataItem) {
        val map = DataMapItem.fromDataItem(dataItem).dataMap
        val jsonStr = map.getString("library_json") ?: return
        try {
            val json = JSONObject(jsonStr)
            rootItem = parseJson(json)
            // Reset nav stack to root if current breadcrumbs are invalid
            navigationStack.clear()
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
        scope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@MainActivity).sendMessage(
                        node.id, "/run_script", path.toByteArray(StandardCharsets.UTF_8)
                    ).await()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Set initial progress to show the bar
        executionProgress = 0.01f
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        scope.cancel()
    }
}

@Composable
fun WearApp(
    rootItem: LibraryItem?,
    navStack: List<LibraryItem>,
    progress: Float?,
    onFolderClick: (LibraryItem) -> Unit,
    onFileClick: (LibraryItem) -> Unit,
    onBack: () -> Unit
) {
    val currentItem = navStack.lastOrNull() ?: rootItem
    val sharkBlue = Color(0xFF00B0FF)

    MaterialTheme {
        Scaffold(
            timeText = { if (progress == null) TimeText() }
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                if (progress != null) {
                    ProgressOverlay(progress, sharkBlue)
                } else if (currentItem == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Sincronizzazione...", textAlign = TextAlign.Center)
                    }
                } else {
                    LibraryList(currentItem, navStack.isNotEmpty(), onFolderClick, onFileClick, onBack, sharkBlue)
                }
            }
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
                label = { Text(child.name) },
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
