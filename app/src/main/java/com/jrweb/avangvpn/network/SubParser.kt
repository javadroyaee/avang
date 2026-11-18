package com.jrweb.avangvpn.network

import android.net.Uri
import android.util.Base64
import com.jrweb.avangvpn.model.SubscriptionInfo
import com.jrweb.avangvpn.model.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit

object SubParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSubscription(input: String): Pair<SubscriptionInfo, List<VpnConfig>> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "v2rayNG/1.8.19")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("خطا در دریافت اشتراک: کد ${response.code}")

                val body = response.body?.string() ?: ""
                val userInfoHeader = response.header("Subscription-Userinfo") ?: ""
                val profileTitleHeader = response.header("Profile-Title") ?: ""

                val subInfo = parseUserInfo(userInfoHeader, profileTitleHeader, trimmed)
                val configs = parseRawConfigs(body)
                return@withContext Pair(subInfo, configs)
            }
        } else {
            val configs = parseRawConfigs(trimmed)
            val subInfo = SubscriptionInfo(title = "کانفیگ مستقیم", url = "")
            return@withContext Pair(subInfo, configs)
        }
    }

    private fun parseUserInfo(header: String, titleHeader: String, url: String): SubscriptionInfo {
        var upload = 0L
        var download = 0L
        var total = 0L
        var expire = 0L

        header.split(";").forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "upload" -> upload = kv[1].toLongOrNull() ?: 0L
                    "download" -> download = kv[1].toLongOrNull() ?: 0L
                    "total" -> total = kv[1].toLongOrNull() ?: 0L
                    "expire" -> expire = kv[1].toLongOrNull() ?: 0L
                }
            }
        }

        val title = if (titleHeader.startsWith("base64:")) {
            try {
                String(Base64.decode(titleHeader.removePrefix("base64:"), Base64.DEFAULT))
            } catch (e: Exception) { "اشتراک فعال" }
        } else if (titleHeader.isNotEmpty()) {
            try { URLDecoder.decode(titleHeader, "UTF-8") } catch (e: Exception) { titleHeader }
        } else {
            "اشتراک آونگ"
        }

        return SubscriptionInfo(
            title = title,
            url = url,
            uploadBytes = upload,
            downloadBytes = download,
            totalBytes = total,
            expireTimestamp = expire
        )
    }

    fun parseRawConfigs(rawText: String): List<VpnConfig> {
        val decoded = try {
            if (!rawText.contains("://")) {
                String(Base64.decode(rawText.trim(), Base64.DEFAULT))
            } else rawText
        } catch (e: Exception) {
            rawText
        }

        return decoded.lines()
            .map { it.trim() }
            .filter { it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://") }
            .mapNotNull { parseSingleUri(it) }
    }

    private fun parseSingleUri(uriString: String): VpnConfig? {
        return try {
            if (uriString.startsWith("vmess://")) {
                val jsonStr = String(Base64.decode(uriString.removePrefix("vmess://"), Base64.DEFAULT))
                val json = JSONObject(jsonStr)
                VpnConfig(
                    id = UUID.randomUUID().toString(),
                    name = json.optString("ps", "سرور VMess"),
                    protocol = "VMess",
                    address = json.getString("add"),
                    port = json.getInt("port"),
                    rawUri = uriString
                )
            } else {
                val uri = Uri.parse(uriString)
                val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "سرور ${uri.scheme?.uppercase()}"
                VpnConfig(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    protocol = uri.scheme?.uppercase() ?: "VPN",
                    address = uri.host ?: return null,
                    port = if (uri.port != -1) uri.port else 443,
                    rawUri = uriString
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun measurePing(config: VpnConfig): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.address, config.port), 2500)
            }
            val elapsed = (System.currentTimeMillis() - start).toInt()
            if (elapsed == 0) 1 else elapsed
        } catch (e: Exception) {
            -2
        }
    }
}