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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.resolve
import xyz.sakulik.comic.utils.toUiText
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

    // 协程与 withContext 内无法调用 stringResource，先在 Composable 作用域取出
    val msgApiKeySaved = stringResource(R.string.settings_api_key_saved)
    val msgSavedCleartext = stringResource(R.string.settings_saved_cleartext)
    val msgRemoteSaved = stringResource(R.string.settings_remote_saved)
    val msgUrlRequired = stringResource(R.string.settings_url_required)
    val msgTestUrlFailed = stringResource(R.string.settings_test_url_failed)
    val msgBadFormat = stringResource(R.string.settings_conn_bad_format)
    val msgUnauthorized = stringResource(R.string.settings_conn_unauthorized)
    val msgUnknown = stringResource(R.string.common_unknown)
    val msgCacheCleared = stringResource(R.string.settings_cache_cleared)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
            SettingsSectionTitle(stringResource(R.string.settings_section_security))
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
                                snackbarHostState.showSnackbar(msgApiKeySaved)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_save_credentials))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Group 1: 云端流媒体库 ---
            SettingsSectionTitle(stringResource(R.string.settings_section_remote))
            SettingsSurface {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_remote_enable),
                        subtitle = stringResource(R.string.settings_remote_enable_hint),
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
                                placeholder = { Text(stringResource(R.string.settings_token_hint)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Text(
                                text = stringResource(R.string.settings_https_notice),
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
                                                msgSavedCleartext
                                            } else {
                                                msgRemoteSaved
                                            }
                                            snackbarHostState.showSnackbar(message)
                                        } catch (e: IllegalArgumentException) {
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    R.string.settings_error_prefix,
                                                    e.toUiText().resolve(context)
                                                )
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.settings_save_remote))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        if (apiUrlInput.isBlank()) {
                                            snackbarHostState.showSnackbar(msgUrlRequired)
                                            return@launch
                                        }
                                        val endpoint = try {
                                            ComixEndpointPolicy.parse(apiUrlInput)
                                        } catch (e: IllegalArgumentException) {
                                            snackbarHostState.showSnackbar(
                                                context.getString(
                                                    R.string.settings_error_prefix,
                                                    e.toUiText().resolve(context)
                                                )
                                            )
                                            return@launch
                                        }
                                        val testUrl = endpoint.baseUrl.resolve("api")?.toString()
                                        if (testUrl == null) {
                                            snackbarHostState.showSnackbar(msgTestUrlFailed)
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
                                                        val version = response.optString("apiVersion", msgUnknown)
                                                        val protocolVersion = response.optInt("protocolVersion", 1)
                                                        context.getString(R.string.settings_conn_ok, version, protocolVersion)
                                                    } else {
                                                        msgBadFormat
                                                    }
                                                } else if (code == 401) {
                                                    msgUnauthorized
                                                } else {
                                                    context.getString(R.string.settings_conn_failed_code, code)
                                                }
                                            } catch (e: java.lang.Exception) {
                                                context.getString(R.string.settings_conn_error, e.message ?: "")
                                            } finally {
                                                connection?.disconnect()
                                            }
                                        }
                                        snackbarHostState.showSnackbar(status)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.settings_test_connection))
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onClearRemoteLibrary,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.settings_clear_remote))
                            }
                            Text(stringResource(R.string.settings_clear_remote_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Group 2: 全局刮削器 ---
            SettingsSectionTitle(stringResource(R.string.settings_section_scraper))
            val metadataEnabled by SettingsDataStore.getMetadataEnabledFlow(context).collectAsState(initial = true)
            SettingsSurface {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_metadata_enable),
                        subtitle = stringResource(R.string.settings_metadata_enable_hint),
                        checked = metadataEnabled,
                        onCheckedChange = { scope.launch { SettingsDataStore.saveMetadataEnabled(context, it) } }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Group 3: 存储管理 ---
            SettingsSectionTitle(stringResource(R.string.settings_section_storage))
            val autoClearCovers by SettingsDataStore.getAutoClearCoversFlow(context).collectAsState(initial = false)
            SettingsSurface {
                Column {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_clear_covers),
                        subtitle = stringResource(R.string.settings_auto_clear_covers_hint),
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
                                    snackbarHostState.showSnackbar(msgCacheCleared)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.settings_clear_cache))
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
