package xyz.sakulik.comic.ui.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import xyz.sakulik.comic.BuildConfig
import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.LocalizedThrowable
import xyz.sakulik.comic.utils.UiText
import xyz.sakulik.comic.utils.resolve
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASE_API_URL = "https://api.github.com/repos/sakulik2/comix/releases/latest"
private const val CHECK_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
private const val SNOOZE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
private const val MAX_RESPONSE_BYTES = 1024 * 1024
private const val MAX_RELEASE_NOTES_CHARS = 4_000
private const val UPDATE_PREFERENCES = "app_update_preferences"

private data class UpdateInfo(
    val version: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val releaseNotes: String
)

private data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

private object UpdatePreferences {
    private const val KEY_ENABLED = "automatic_checks_enabled"
    private const val KEY_LAST_CHECK = "last_check_time"
    private const val KEY_CACHED_VERSION = "cached_version"
    private const val KEY_CACHED_ASSET_NAME = "cached_asset_name"
    private const val KEY_CACHED_DOWNLOAD_URL = "cached_download_url"
    private const val KEY_CACHED_SIZE = "cached_size"
    private const val KEY_CACHED_NOTES = "cached_notes"
    private const val KEY_SNOOZED_VERSION = "snoozed_version"
    private const val KEY_SNOOZED_AT = "snoozed_at"

    fun isEnabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun lastCheck(context: Context): Long {
        return preferences(context).getLong(KEY_LAST_CHECK, 0L)
    }

    fun markChecked(context: Context, checkedAt: Long) {
        preferences(context).edit().putLong(KEY_LAST_CHECK, checkedAt).apply()
    }

    fun cache(context: Context, updateInfo: UpdateInfo) {
        preferences(context).edit()
            .putString(KEY_CACHED_VERSION, updateInfo.version)
            .putString(KEY_CACHED_ASSET_NAME, updateInfo.assetName)
            .putString(KEY_CACHED_DOWNLOAD_URL, updateInfo.downloadUrl)
            .putLong(KEY_CACHED_SIZE, updateInfo.sizeBytes)
            .putString(KEY_CACHED_NOTES, updateInfo.releaseNotes)
            .apply()
    }

    fun cached(context: Context): UpdateInfo? {
        val preferences = preferences(context)
        val version = preferences.getString(KEY_CACHED_VERSION, null) ?: return null
        val assetName = preferences.getString(KEY_CACHED_ASSET_NAME, null) ?: return null
        val downloadUrl = preferences.getString(KEY_CACHED_DOWNLOAD_URL, null) ?: return null
        return UpdateInfo(
            version = version,
            assetName = assetName,
            downloadUrl = downloadUrl,
            sizeBytes = preferences.getLong(KEY_CACHED_SIZE, 0L),
            releaseNotes = preferences.getString(KEY_CACHED_NOTES, "").orEmpty()
        )
    }

    fun clearCache(context: Context) {
        preferences(context).edit()
            .remove(KEY_CACHED_VERSION)
            .remove(KEY_CACHED_ASSET_NAME)
            .remove(KEY_CACHED_DOWNLOAD_URL)
            .remove(KEY_CACHED_SIZE)
            .remove(KEY_CACHED_NOTES)
            .apply()
    }

    fun snooze(context: Context, version: String) {
        preferences(context).edit()
            .putString(KEY_SNOOZED_VERSION, version)
            .putLong(KEY_SNOOZED_AT, System.currentTimeMillis())
            .apply()
    }

    fun isSnoozed(context: Context, version: String, now: Long): Boolean {
        val preferences = preferences(context)
        return preferences.getString(KEY_SNOOZED_VERSION, null) == version &&
            now - preferences.getLong(KEY_SNOOZED_AT, 0L) < SNOOZE_INTERVAL_MILLIS
    }

    private fun preferences(context: Context) = context.getSharedPreferences(
        UPDATE_PREFERENCES,
        Context.MODE_PRIVATE
    )
}

private object UpdateRepository {
    private val checkMutex = Mutex()

    suspend fun check(context: Context, force: Boolean): Result<UpdateInfo?> {
        return withContext(Dispatchers.IO) {
            runCatching<UpdateInfo?> {
                checkMutex.withLock {
                    val applicationContext = context.applicationContext
                    val now = System.currentTimeMillis()
                    if (!force && now - UpdatePreferences.lastCheck(applicationContext) < CHECK_INTERVAL_MILLIS) {
                        return@withLock usableCachedUpdate(applicationContext, now)
                    }

                    val latest = fetchLatestRelease()
                    UpdatePreferences.markChecked(applicationContext, now)
                    if (latest == null || !isNewerVersion(latest.version, BuildConfig.VERSION_NAME)) {
                        UpdatePreferences.clearCache(applicationContext)
                        return@withLock null
                    }

                    UpdatePreferences.cache(applicationContext, latest)
                    if (!force && UpdatePreferences.isSnoozed(applicationContext, latest.version, now)) {
                        null
                    } else {
                        latest
                    }
                }
            }
        }
    }

    private fun usableCachedUpdate(context: Context, now: Long): UpdateInfo? {
        val cached = UpdatePreferences.cached(context) ?: return null
        if (!isNewerVersion(cached.version, BuildConfig.VERSION_NAME)) return null
        if (UpdatePreferences.isSnoozed(context, cached.version, now)) return null
        return cached
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        var connection: HttpURLConnection? = null
        try {
            val activeConnection = URL(RELEASE_API_URL).openConnection() as HttpURLConnection
            connection = activeConnection
            activeConnection.requestMethod = "GET"
            activeConnection.connectTimeout = 5_000
            activeConnection.readTimeout = 10_000
            activeConnection.instanceFollowRedirects = false
            activeConnection.setRequestProperty("Accept", "application/vnd.github+json")
            activeConnection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            activeConnection.setRequestProperty("User-Agent", "comix-android/${BuildConfig.VERSION_NAME}")

            val responseCode = activeConnection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw UpdateCheckException(
                    UiText.Res(R.string.error_update_http, listOf(responseCode)),
                    "GitHub Releases returned HTTP $responseCode"
                )
            }

            val release = JSONObject(readLimited(activeConnection.inputStream))
            val version = release.optString("tag_name").trim().removePrefix("v")
            if (version.isBlank()) throw UpdateCheckException(
                UiText.Res(R.string.error_update_empty_version),
                "release tag_name is blank"
            )

            val assets = release.optJSONArray("assets") ?: return null
            val apkAssets = buildList {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name").trim()
                    val downloadUrl = asset.optString("browser_download_url").trim()
                    if (!name.endsWith(".apk", ignoreCase = true) || !isAllowedDownloadUrl(downloadUrl)) continue
                    add(
                        ReleaseAsset(
                            name = name,
                            downloadUrl = downloadUrl,
                            sizeBytes = asset.optLong("size", 0L).coerceAtLeast(0L)
                        )
                    )
                }
            }
            val selectedAsset = selectBestAsset(apkAssets) ?: return null
            return UpdateInfo(
                version = version,
                assetName = selectedAsset.name,
                downloadUrl = selectedAsset.downloadUrl,
                sizeBytes = selectedAsset.sizeBytes,
                releaseNotes = release.optString("body").trim().take(MAX_RELEASE_NOTES_CHARS)
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun selectBestAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
        if (assets.size <= 1) return assets.firstOrNull()
        val supportedAbis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        val prefersArm64 = supportedAbis.any { it.contains("arm64") || it.contains("aarch64") }
        val prefersArm32 = supportedAbis.any { it.contains("armeabi") || it.contains("armv7") }
        return assets.maxByOrNull { asset ->
            val name = asset.name.lowercase()
            var score = 0
            if (name.contains("universal") || name.contains("all")) score += 40
            if (prefersArm64 && listOf("arm64", "armv8", "aarch64").any(name::contains)) score += 100
            if (prefersArm32 && listOf("armeabi", "armv7").any(name::contains)) score += 80
            if (name.contains("x86")) score -= 200
            score
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = versionParts(latest)
        val currentParts = versionParts(current)
        if (latestParts.isEmpty() || currentParts.isEmpty()) return false
        val partCount = maxOf(latestParts.size, currentParts.size)
        for (index in 0 until partCount) {
            val latestPart = latestParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (latestPart != currentPart) return latestPart > currentPart
        }
        return false
    }

    private fun versionParts(version: String): List<Int> {
        return Regex("\\d+")
            .findAll(version.substringBefore('-'))
            .take(4)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }

    private fun readLimited(input: InputStream): String {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                totalBytes += read
                if (totalBytes > MAX_RESPONSE_BYTES) throw UpdateCheckException(
                    UiText.Res(R.string.error_update_response_limit),
                    "update response exceeds $MAX_RESPONSE_BYTES bytes"
                )
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}

/** message 只给 logcat 看，用户看到的是 uiText。 */
private class UpdateCheckException(
    override val uiText: UiText,
    technicalMessage: String
) : IOException(technicalMessage), LocalizedThrowable

@Composable
fun AppUpdatePrompt() {
    val context = LocalContext.current
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        if (!UpdatePreferences.isEnabled(context)) return@LaunchedEffect
        val result = UpdateRepository.check(context, force = false)
        if (UpdatePreferences.isEnabled(context)) {
            updateInfo = result.getOrNull()
        }
    }

    updateInfo?.let { availableUpdate ->
        UpdateDialog(
            updateInfo = availableUpdate,
            onDismiss = {
                UpdatePreferences.snooze(context, availableUpdate.version)
                updateInfo = null
            }
        )
    }
}

@Composable
fun AppUpdateSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var automaticChecksEnabled by remember { mutableStateOf(UpdatePreferences.isEnabled(context)) }
    var checking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    // 下面几句在 lambda / 协程里用，先在 Composable 作用域取出
    val msgAutoOn = stringResource(R.string.update_auto_check_on)
    val msgAutoOff = stringResource(R.string.update_auto_check_off)
    val msgUpToDate = stringResource(R.string.update_up_to_date)

    Column {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.update_section_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                        Text(
                            stringResource(R.string.update_auto_check),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(R.string.update_auto_check_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = automaticChecksEnabled,
                        onCheckedChange = { enabled ->
                            automaticChecksEnabled = enabled
                            UpdatePreferences.setEnabled(context, enabled)
                            statusText = if (enabled) msgAutoOn else msgAutoOff
                        }
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.update_current_version_label,
                            BuildConfig.VERSION_NAME
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                checking = true
                                statusText = null
                                val result = UpdateRepository.check(context, force = true)
                                checking = false
                                result.fold(
                                    onSuccess = { availableUpdate ->
                                        if (availableUpdate == null) {
                                            statusText = msgUpToDate
                                        } else {
                                            updateInfo = availableUpdate
                                        }
                                    },
                                    onFailure = { error ->
                                        val reason = (error as? LocalizedThrowable)?.uiText
                                            ?.resolve(context)
                                            ?: context.getString(R.string.update_network_error)
                                        statusText =
                                            context.getString(R.string.update_check_failed, reason)
                                    }
                                )
                            }
                        },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (checking) stringResource(R.string.update_checking)
                            else stringResource(R.string.update_check_now)
                        )
                    }
                    statusText?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }

    updateInfo?.let { availableUpdate ->
        UpdateDialog(
            updateInfo = availableUpdate,
            onDismiss = { updateInfo = null }
        )
    }
}

@Composable
private fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var enqueueing by remember(updateInfo.version) { mutableStateOf(false) }
    // Toast 在非 Composable lambda 里弹，先取好文案
    val msgQueued = stringResource(R.string.update_queued)
    val msgStartFailed = stringResource(R.string.update_download_start_failed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_found_title, updateInfo.version)) },
        text = {
            Column {
                Text(stringResource(R.string.update_current_version, BuildConfig.VERSION_NAME))
                if (updateInfo.sizeBytes > 0L) {
                    Text(
                        text = stringResource(
                            R.string.update_apk_size,
                            formatFileSize(updateInfo.sizeBytes)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (updateInfo.releaseNotes.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = updateInfo.releaseNotes,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    enqueueing = true
                    runCatching { enqueueDownload(context, updateInfo) }
                        .onSuccess {
                            Toast.makeText(context, msgQueued, Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                        .onFailure { error ->
                            enqueueing = false
                            val reason = (error as? LocalizedThrowable)?.uiText
                                ?.resolve(context)
                                ?: msgStartFailed
                            Toast.makeText(
                                context,
                                context.getString(R.string.update_download_failed, reason),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                },
                enabled = !enqueueing
            ) {
                Text(
                    if (enqueueing) stringResource(R.string.update_enqueueing)
                    else stringResource(R.string.update_download_apk)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_later))
            }
        }
    )
}

private fun enqueueDownload(context: Context, updateInfo: UpdateInfo): Long {
    // 下面两处 guard 正常不可能触发，保持英文技术串给 logcat；
    // 用户看到的是调用方 Toast 里的 update_download_start_failed。
    require(isAllowedDownloadUrl(updateInfo.downloadUrl)) { "untrusted download url" }
    val safeStem = updateInfo.assetName
        .removeSuffix(".apk")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(100)
        .ifBlank { "comix-${updateInfo.version}" }
    val targetName = "$safeStem-${System.currentTimeMillis()}.apk"
    val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
        .setTitle("comix ${updateInfo.version}")
        .setDescription(context.getString(R.string.update_notification_desc))
        .setMimeType("application/vnd.android.package-archive")
        .setAllowedNetworkTypes(
            DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
        )
        .setAllowedOverMetered(true)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, targetName)
    val downloadManager = context.getSystemService(DownloadManager::class.java)
        ?: throw IllegalStateException("DownloadManager service unavailable")
    return downloadManager.enqueue(request)
}

private fun isAllowedDownloadUrl(downloadUrl: String): Boolean {
    val uri = runCatching { Uri.parse(downloadUrl) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host == "github.com" ||
        host.endsWith(".github.com") ||
        host.endsWith(".githubusercontent.com")
}

private fun formatFileSize(sizeBytes: Long): String {
    val megabytes = sizeBytes.toDouble() / 1024.0 / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", megabytes)
}
