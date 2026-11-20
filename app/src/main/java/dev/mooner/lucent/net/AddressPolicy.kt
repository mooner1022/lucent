package dev.mooner.lucent.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 서버 주소를 받아들이는 규칙(사양 2.1, 2.3, 13장).
 *
 * - 집 안(LAN·VPN) 주소는 평문(`http`)을 쓴다. 밖(공개 주소)은 반드시 `https`다.
 * - 그래서 평문은 사설·루프백·링크 로컬·CGNAT 주소와 집 안에서만 풀리는 이름에만 허용한다.
 * - 사용자가 `ws://`·`wss://`로 적어도 받아 `http`·`https`로 바꾼다. 경로(`/v1/ws` 등)는 지운다.
 */
object AddressPolicy {

    sealed interface Result {
        /** [base]는 끝 슬래시와 경로가 없는 `http(s)://host[:port]`. */
        data class Ok(val base: String, val cleartext: Boolean) : Result
        data class Rejected(val reason: String) : Result
    }

    fun parse(input: String): Result {
        var text = input.trim()
        if (text.isEmpty())
            return Result.Rejected("주소를 입력해 주세요")
        if (text.any { it.isWhitespace() })
            return Result.Rejected("주소에 공백이 있어요")

        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase()
        text = when (scheme) {
            null -> text
            "ws", "http" -> "http://" + text.substringAfter("://")
            "wss", "https" -> "https://" + text.substringAfter("://")
            else -> return Result.Rejected("지원하지 않는 주소 방식이에요(http, https, ws, wss)")
        }

        // 방식을 쓰지 않았으면 집 안 주소는 http, 그 밖은 https로 본다.
        val url: HttpUrl? = if (scheme != null) text.toHttpUrlOrNull() else {
            val host = hostOf(text)
            val guess = if (host != null && isHomeHost(host)) "http://" else "https://"
            (guess + text).toHttpUrlOrNull()
        }
        if (url == null)
            return Result.Rejected("주소 형식이 올바르지 않아요")

        val cleartext = url.scheme == "http"
        if (cleartext && !isHomeHost(url.host))
            return Result.Rejected("집 밖 주소는 https로만 연결할 수 있어요")

        val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
        val host = if (':' in url.host) "[${url.host}]" else url.host
        return Result.Ok("${url.scheme}://$host$port", cleartext)
    }

    /** `host`, `host:port`, `[v6]:port`를 가리지 않고 호스트만 뽑는다. 경로 앞까지만 본다. */
    private fun hostOf(text: String): String? {
        val authority = text.substringBefore('/').substringBefore('?')
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            return if (end > 0) authority.substring(1, end) else null
        }
        return authority.substringBefore(':').ifEmpty { null }
    }

    /** 평문으로 붙어도 되는, 집 안에서만 닿는 호스트인가. */
    fun isHomeHost(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (HOME_SUFFIXES.any { host.endsWith(it) }) return true
        ipv4(host)?.let { return isHomeV4(it) }
        if (':' in host) return isHomeV6(host)
        return false
    }

    private fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return if (nums.all { it in 0..255 }) nums.toIntArray() else null
    }

    private fun isHomeV4(a: IntArray): Boolean = when {
        a[0] == 10 -> true
        a[0] == 127 -> true
        a[0] == 172 && a[1] in 16..31 -> true
        a[0] == 192 && a[1] == 168 -> true
        a[0] == 169 && a[1] == 254 -> true
        a[0] == 100 && a[1] in 64..127 -> true // CGNAT: Tailscale 등
        else -> false
    }

    private fun isHomeV6(host: String): Boolean {
        val h = host.substringBefore('%')
        return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") ||
            h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
    }

    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")
    private val HOME_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".ts.net", ".internal")

    /** 화면에 보일 짧은 주소. */
    fun display(base: String) = base.substringAfter("://")
}
