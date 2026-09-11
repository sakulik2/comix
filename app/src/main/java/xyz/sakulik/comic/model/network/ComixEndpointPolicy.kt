package xyz.sakulik.comic.model.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.LocalizedIllegalArgumentException
import xyz.sakulik.comic.utils.UiText

object ComixEndpointPolicy {
    data class Endpoint(
        val baseUrl: HttpUrl,
        val isCleartextLan: Boolean
    )

    fun parse(rawUrl: String): Endpoint {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) throw LocalizedIllegalArgumentException(
            UiText.Res(R.string.error_endpoint_empty),
            "endpoint url is empty"
        )

        val candidate = if (SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else "http://$trimmed"
        val parsed = candidate.toHttpUrlOrNull()
            ?: throw LocalizedIllegalArgumentException(
                UiText.Res(R.string.error_endpoint_invalid),
                "endpoint url is not parseable: $candidate"
            )
        if (parsed.query != null || parsed.fragment != null) throw LocalizedIllegalArgumentException(
            UiText.Res(R.string.error_endpoint_query),
            "endpoint url carries a query or fragment"
        )
        if (parsed.scheme != "https" && !isPrivateLanHost(parsed.host)) {
            throw LocalizedIllegalArgumentException(
                UiText.Res(R.string.error_endpoint_https_required),
                "refusing plaintext HTTP for public host ${parsed.host}"
            )
        }

        val normalized = if (parsed.encodedPath.endsWith('/')) {
            parsed
        } else {
            parsed.newBuilder().addPathSegment("").build()
        }
        return Endpoint(normalized, normalized.scheme == "http")
    }

    fun normalizeBaseUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        return parse(rawUrl).baseUrl.toString()
    }

    fun isComixApiRequest(requestUrl: HttpUrl, configuredBaseUrl: String?): Boolean {
        if (configuredBaseUrl.isNullOrBlank()) return false
        val configured = runCatching { parse(configuredBaseUrl).baseUrl }.getOrNull() ?: return false
        val sameOrigin = requestUrl.scheme == configured.scheme &&
            requestUrl.host == configured.host &&
            requestUrl.port == configured.port
        if (!sameOrigin) return false

        val apiRoot = configured.resolve("api/")?.encodedPath ?: return false
        return matchesPath(requestUrl.encodedPath, "${apiRoot}comics") ||
            matchesPath(requestUrl.encodedPath, "${apiRoot}scan")
    }

    fun isPrivateLanHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        if (normalized.endsWith(".local") || normalized.endsWith(".lan") || normalized.endsWith(".home.arpa")) return true
        if (!normalized.contains('.') && !normalized.contains(':')) return true

        parseIpv4(normalized)?.let { octets ->
            return octets[0] == 10 ||
                octets[0] == 127 ||
                octets[0] == 192 && octets[1] == 168 ||
                octets[0] == 172 && octets[1] in 16..31 ||
                octets[0] == 100 && octets[1] in 64..127 ||
                octets[0] == 169 && octets[1] == 254
        }

        if (normalized == "::1") return true
        val firstIpv6Group = normalized.substringBefore(':').toIntOrNull(16) ?: return false
        return (firstIpv6Group and 0xFE00) == 0xFC00 ||
            (firstIpv6Group and 0xFFC0) == 0xFE80
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }

    private fun matchesPath(requestPath: String, endpointPath: String): Boolean {
        return requestPath == endpointPath || requestPath.startsWith("$endpointPath/")
    }

    private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
