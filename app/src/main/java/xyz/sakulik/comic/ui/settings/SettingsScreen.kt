package xyz.sakulik.comic.ui.settings

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.sakulik.comic.model.network.ComixEndpointPolicy
import xyz.sakulik.comic.model.preferences.SettingsDataStore
import xyz.sakulik.comic.ui.update.AppUpdateSettings

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

    val savedApiUrl by SettingsDataStore.getComicApiBaseUrlFlow(context).collectAsState(initial = "")
    var apiUrlInput by remember(savedApiUrl) { mutableStateOf(savedApiUrl ?: "") }

    val savedApiToken by SettingsDataStore.getComicApiTokenFlow(context).collectAsState(initial = "")
    var apiTokenInput by remember(savedApiToken) { mutableStateOf(savedApiToken ?: "") }

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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // --- Group 0: 安全与授权 ---
            SettingsSectionTitle("安全与授权")
            SettingsSurface {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("ComicVine API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                SettingsDataStore.saveComicVineApiKey(context, apiKeyInput)
                                snackbarHostState.showSnackbar("✅ API Key 已保存")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存认证配置")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Group 1: 云端流媒体库 ---
            SettingsSectionTitle("云端流媒体库")
            SettingsSurface {
                Column {
                    SettingsSwitchRow(
                        title = "开启云端同步功能",
                        subtitle = "关闭后将隐藏同步按钮及所有云端漫画",
                        checked = remoteEnabled,
                        onCheckedChange = { scope.launch { SettingsDataStore.saveRemoteEnabled(context, it) } }
                    )
                    if (remoteEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = apiUrlInput,
                                onValueChange = { apiUrlInput = it },
                                label = { Text("Comix API Base URL") },
                                placeholder = { Text("http://192.168.1.3:3000/") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiTokenInput,
                                onValueChange = { apiTokenInput = it },
                                label = { Text("Comix API Token") },
                                placeholder = { Text("留空表示服务端未加密") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Text(
                                text = "公网地址必须使用 HTTPS；局域网 HTTP 可用，但 Token 仍会以明文传输。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val endpoint = ComixEndpointPolicy.parse(apiUrlInput)
                                            apiUrlInput = endpoint.baseUrl.toString()
                                            SettingsDataStore.saveComicApiBaseUrl(context, apiUrlInput)
                                            SettingsDataStore.saveComicApiToken(context, apiTokenInput)
                                            val message = if (endpoint.isCleartextLan && apiTokenInput.isNotBlank()) {
                                                "⚠️ 配置已保存；局域网 HTTP 会明文传输 Token"
                                            } else {
                                                "✅ 云端配置已保存"
                                            }
                                            snackbarHostState.showSnackbar(message)
                                        } catch (e: IllegalArgumentException) {
                                            snackbarHostState.showSnackbar("❌ ${e.message}")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("保存云端配置")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        if (apiUrlInput.isBlank()) {
                                            snackbarHostState.showSnackbar("❌ 请先输入 API Base URL")
                                            return@launch
                                        }
                                        val endpoint = try {
                                            ComixEndpointPolicy.parse(apiUrlInput)
                                        } catch (e: IllegalArgumentException) {
                                            snackbarHostState.showSnackbar("❌ ${e.message}")
                                            return@launch
                                        }
                                        val testUrl = endpoint.baseUrl.resolve("api")?.toString()
                                        if (testUrl == null) {
                                            snackbarHostState.showSnackbar("❌ 无法生成测试地址")
                                            return@launch
                                        }
                                        val token = apiTokenInput.trim()

                                        val status = withContext(Dispatchers.IO) {
                                            var connection: HttpURLConnection? = null
                                            try {
                                                val activeConnection = URL(testUrl).openConnection() as HttpURLConnection
                                                connection = activeConnection
                                                activeConnection.instanceFollowRedirects = false
                                                activeConnection.requestMethod = "GET"
                                                activeConnection.connectTimeout = 3000
                                                activeConnection.readTimeout = 3000
                                                if (token.isNotEmpty()) {
                                                    activeConnection.setRequestProperty("x-comix-token", token)
                                                }
                                                val code = activeConnection.responseCode
                                                if (code == 200) {
                                                    val text = activeConnection.inputStream.bufferedReader().use { it.readText() }
                                                    val response = runCatching { org.json.JSONObject(text) }.getOrNull()
                                                    if (response?.optString("service") == "comix.js") {
                                                        val version = response.optString("apiVersion", "未知")
                                                        val protocolVersion = response.optInt("protocolVersion", 1)
                                                        "✅ 连接成功！服务端版本：$version，协议：v$protocolVersion"
                                                    } else {
                                                        "⚠️ 连接成功但响应格式不符"
                                                    }
                                                } else if (code == 401) {
                                                    "❌ 鉴权失败：API Token 错误或未配置"
                                                } else {
                                                    "❌ 连接失败，状态码：$code"
                                                }
                                            } catch (e: java.lang.Exception) {
                                                "❌ 无法连接到服务器: ${e.message}"
                                            } finally {
                                                connection?.disconnect()
                                            }
                                        }
                                        snackbarHostState.showSnackbar(status)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("测试服务器连接")
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text("危险区域", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onClearRemoteLibrary,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                            ) {
                                Text("清除本地全量云端数据索引")
                            }
                            Text("此操作不可逆，将从本地数据库中移除远程漫画记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Group 2: 全局刮削器 ---
            SettingsSectionTitle("全局漫库刮削器")
            val metadataEnabled by SettingsDataStore.getMetadataEnabledFlow(context).collectAsState(initial = true)
            SettingsSurface {
                Column {
                    SettingsSwitchRow(
                        title = "启用智能元数据",
                        subtitle = "关闭后将停止系列聚合并显示原始文件名",
                        checked = metadataEnabled,
                        onCheckedChange = { scope.launch { SettingsDataStore.saveMetadataEnabled(context, it) } }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Group 3: 存储管理 ---
            SettingsSectionTitle("本地存储管理")
            val autoClearCovers by SettingsDataStore.getAutoClearCoversFlow(context).collectAsState(initial = false)
            SettingsSurface {
                Column {
                    SettingsSwitchRow(
                        title = "启动时自动清理封面",
                        subtitle = "极致瘦身模式，每次重启应用都会重置封面缓存",
                        checked = autoClearCovers,
                        onCheckedChange = { scope.launch { SettingsDataStore.saveAutoClearCovers(context, it) } }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                                    val coverDir = java.io.File(context.filesDir, "covers")
                                    if (coverDir.exists()) coverDir.deleteRecursively()
                                    snackbarHostState.showSnackbar("✅ 所有缓存与封面已清理完毕")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("手动一键清理全量应用缓存")
                        }
                    }
                }
            }

            AppUpdateSettings()

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// 提取的通用 UI 组件
@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@Composable
private fun SettingsSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
