package xyz.sakulik.comic.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.sakulik.comic.model.preferences.SettingsDataStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearRemoteLibrary: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedApiKey by SettingsDataStore.getComicVineApiKeyFlow(context).collectAsState(initial = "")
    var apiKeyInput by remember(savedApiKey) { mutableStateOf(savedApiKey ?: "") }

    val savedApiUrl by SettingsDataStore.getComicApiBaseUrlFlow(context).collectAsState(initial = "https://comix.sakulik.xyz/")
    var apiUrlInput by remember(savedApiUrl) { mutableStateOf(savedApiUrl ?: "https://comix.sakulik.xyz/") }

    val remoteEnabled by SettingsDataStore.getRemoteEnabledFlow(context).collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Text("安全认证核心配置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it },
                label = { Text("ComicVine API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            Text("云端同步核心控制", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("开启云端同步功能", style = MaterialTheme.typography.bodyLarge)
                    Text("关闭后将隐藏同步按钮及所有云端漫画", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = remoteEnabled,
                    onCheckedChange = { 
                        scope.launch { SettingsDataStore.saveRemoteEnabled(context, it) }
                    }
                )
            }
            
            if (remoteEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("云端流媒体服务配置", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = apiUrlInput,
                    onValueChange = { apiUrlInput = it },
                    label = { Text("Comix API Base URL") },
                    placeholder = { Text("https://comix.sakulik.xyz/") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        SettingsDataStore.saveComicVineApiKey(context, apiKeyInput)
                        if (remoteEnabled) {
                            SettingsDataStore.saveComicApiBaseUrl(context, apiUrlInput)
                        }
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存基础配置")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("危险区域", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClearRemoteLibrary,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Text("清除本地全量云端数据索引")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "注意：此操作不可逆，将从数据库中移除所有云端漫画记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("个性化体验", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            
            val metadataEnabled by SettingsDataStore.getMetadataEnabledFlow(context).collectAsState(initial = true)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用智能元数据", style = MaterialTheme.typography.bodyLarge)
                    Text("关闭后将停止系列聚合并显示原始文件名", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = metadataEnabled,
                    onCheckedChange = { 
                        scope.launch { SettingsDataStore.saveMetadataEnabled(context, it) }
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            Text("存储管理", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))

            val autoClearCovers by SettingsDataStore.getAutoClearCoversFlow(context).collectAsState(initial = false)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启动时自动清理封面", style = MaterialTheme.typography.bodyLarge)
                    Text("极致瘦身模式。开启后每次重启应用都会重置封面库占用的空间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoClearCovers,
                    onCheckedChange = { 
                        scope.launch { SettingsDataStore.saveAutoClearCovers(context, it) }
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        // 1. 清理全量缓存目录 (Reader 残留)
                        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        // 2. 清理持久化封面
                        val coverDir = java.io.File(context.filesDir, "covers")
                        if (coverDir.exists()) coverDir.deleteRecursively()
                        
                        snackbarHostState.showSnackbar("✅ 所有缓存与封面已清理完毕")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("手动一键清理全量数据")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
