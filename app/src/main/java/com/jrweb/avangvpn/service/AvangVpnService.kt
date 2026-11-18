package com.jrweb.avangvpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.core.app.NotificationCompat
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet
import org.json.JSONArray
import org.json.JSONObject

class AvangVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.jrweb.avangvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.jrweb.avangvpn.DISCONNECT"
        const val EXTRA_CONFIG_URI = "config_uri"
        var isRunning = false
        var activeConfigId: String? = null
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var v2rayPoint: V2RayPoint? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val configUri = intent.getStringExtra(EXTRA_CONFIG_URI) ?: return START_NOT_STICKY
                startVpn(configUri)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn(configUri: String) {
        stopVpn()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "avang_vpn_channel")
            .setContentTitle("آونگ وی‌پی‌ان")
            .setContentText("اتصال برقرار است")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
        startForeground(1, notification)

        try {
            val builder = Builder()
                .setSession("Avang VPN")
                .addAddress("10.1.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)

            vpnInterface = builder.establish()

            v2rayPoint = Libv2ray.newV2RayPoint(object : V2RayVPNServiceSupportsSet {
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
                override fun prepare(): Long = 0
                override fun protect(fd: Long): Boolean = this@AvangVpnService.protect(fd.toInt())
                override fun setup(p0: String?): Long = 0
                override fun shutdown(): Long {
                    stopVpn()
                    return 0
                }
            }, false)

            v2rayPoint?.configureFileContent = buildXrayJsonFromUri(configUri)
            v2rayPoint?.domainName = "127.0.0.1:10808"
            v2rayPoint?.runLoop(false)

            isRunning = true
        } catch (e: Exception) {
            e.printStackTrace()
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        activeConfigId = null
        try {
            v2rayPoint?.stopLoop()
            vpnInterface?.close()
        } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildXrayJsonFromUri(uriStr: String): String {
        val outbound = JSONObject()
        try {
            if (uriStr.startsWith("vless://")) {
                val uri = Uri.parse(uriStr)
                val uuid = uri.userInfo ?: ""
                val host = uri.host ?: ""
                val port = if (uri.port != -1) uri.port else 443
                val network = uri.getQueryParameter("type") ?: "tcp"
                val security = uri.getQueryParameter("security") ?: "none"
                val sni = uri.getQueryParameter("sni") ?: host
                val fp = uri.getQueryParameter("fp") ?: "chrome"
                val pbk = uri.getQueryParameter("pbk") ?: ""
                val sid = uri.getQueryParameter("sid") ?: ""
                val flow = uri.getQueryParameter("flow") ?: ""
                val path = uri.getQueryParameter("path") ?: "/"
                val hostHeader = uri.getQueryParameter("host") ?: host

                outbound.put("protocol", "vless")
                val userObj = JSONObject().apply {
                    put("id", uuid)
                    put("encryption", "none")
                    if (flow.isNotEmpty()) put("flow", flow)
                }
                val vnextObj = JSONObject().apply {
                    put("address", host)
                    put("port", port)
                    put("users", JSONArray().put(userObj))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))

                val streamSettings = JSONObject().apply {
                    put("network", network)
                    put("security", security)
                    if (security == "tls") {
                        put("tlsSettings", JSONObject().apply {
                            put("serverName", sni)
                            put("fingerprint", fp)
                            put("allowInsecure", true)
                        })
                    } else if (security == "reality") {
                        put("realitySettings", JSONObject().apply {
                            put("serverName", sni)
                            put("fingerprint", fp)
                            put("publicKey", pbk)
                            put("shortId", sid)
                        })
                    }
                    if (network == "ws") {
                        put("wsSettings", JSONObject().apply {
                            put("path", path)
                            put("headers", JSONObject().put("Host", hostHeader))
                        })
                    }
                }
                outbound.put("streamSettings", streamSettings)
            } else if (uriStr.startsWith("vmess://")) {
                val decoded = String(Base64.decode(uriStr.removePrefix("vmess://"), Base64.DEFAULT))
                val vmessJson = JSONObject(decoded)
                outbound.put("protocol", "vmess")
                val userObj = JSONObject().apply {
                    put("id", vmessJson.optString("id"))
                    put("alterId", vmessJson.optInt("aid", 0))
                    put("security", vmessJson.optString("scy", "auto"))
                }
                val vnextObj = JSONObject().apply {
                    put("address", vmessJson.optString("add"))
                    put("port", vmessJson.optInt("port", 443))
                    put("users", JSONArray().put(userObj))
                }
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))
                val net = vmessJson.optString("net", "tcp")
                val tls = vmessJson.optString("tls", "")
                val streamSettings = JSONObject().apply {
                    put("network", net)
                    if (tls == "tls") {
                        put("security", "tls")
                        put("tlsSettings", JSONObject().apply {
                            put("serverName", vmessJson.optString("sni", vmessJson.optString("host")))
                            put("allowInsecure", true)
                        })
                    }
                    if (net == "ws") {
                        put("wsSettings", JSONObject().apply {
                            put("path", vmessJson.optString("path", "/"))
                            put("headers", JSONObject().put("Host", vmessJson.optString("host", "")))
                        })
                    }
                }
                outbound.put("streamSettings", streamSettings)
            } else if (uriStr.startsWith("trojan://")) {
                val uri = Uri.parse(uriStr)
                outbound.put("protocol", "trojan")
                val serverObj = JSONObject().apply {
                    put("address", uri.host ?: "")
                    put("port", if (uri.port != -1) uri.port else 443)
                    put("password", uri.userInfo ?: "")
                }
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(serverObj)))
                outbound.put("streamSettings", JSONObject().apply {
                    put("network", uri.getQueryParameter("type") ?: "tcp")
                    put("security", "tls")
                    put("tlsSettings", JSONObject().apply {
                        put("serverName", uri.getQueryParameter("sni") ?: uri.host)
                        put("allowInsecure", true)
                    })
                })
            } else {
                outbound.put("protocol", "freedom")
            }
        } catch (e: Exception) {
            outbound.put("protocol", "freedom")
        }

        val root = JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("inbounds", JSONArray().put(JSONObject().apply {
                put("port", 10808)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().put("udp", true))
            }))
            put("outbounds", JSONArray().put(outbound))
        }
        return root.toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "avang_vpn_channel",
                "سرویس اتصال آونگ وی‌پی‌ان",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}