package com.jrweb.avangvpn.ui

import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.jrweb.avangvpn.model.SubscriptionInfo
import com.jrweb.avangvpn.model.VpnConfig
import com.jrweb.avangvpn.network.SubParser
import com.jrweb.avangvpn.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Composable
fun MainScreen(
    onConnectRequest: (VpnConfig) -> Unit,
    onDisconnectRequest: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var subInfo by remember { mutableStateOf<SubscriptionInfo?>(null) }
    var configs by remember { mutableStateOf<List<VpnConfig>>(emptyList()) }
    var activeConfigId by remember { mutableStateOf<String?>(null) }
    var isConnected by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("آماده اتصال") }
    var isBusy by remember { mutableStateOf(false) }
    var showConfigList by remember { mutableStateOf(true) }

    fun importNewSubscription(rawInput: String) {
        scope.launch {
            isBusy = true
            statusText = "در حال دریافت و جایگزینی کانفیگ‌ها..."
            try {
                if (isConnected) {
                    onDisconnectRequest()
                    isConnected = false
                    activeConfigId = null
                }
                val (info, newConfigs) = SubParser.fetchSubscription(rawInput)
                subInfo = info
                configs = newConfigs
                showConfigList = true
                statusText = "${newConfigs.size} کانفیگ جدید بارگذاری شد"
            } catch (e: Exception) {
                statusText = "خطا: ${e.localizedMessage ?: "لینک نامعتبر"}"
            } finally {
                isBusy = false
            }
        }
    }

    fun runAllInOneConnect() {
        if (isConnected) {
            onDisconnectRequest()
            isConnected = false
            activeConfigId = null
            statusText = "اتصال قطع شد"
            return
        }

        scope.launch {
            isBusy = true
            showConfigList = true

            val currentUrl = subInfo?.url ?: ""
            if (currentUrl.isNotEmpty()) {
                statusText = "۱/۳ در حال بروزرسانی ساب‌لینک..."
                try {
                    val (updatedInfo, updatedConfigs) = SubParser.fetchSubscription(currentUrl)
                    subInfo = updatedInfo
                    configs = updatedConfigs
                } catch (_: Exception) {
                    statusText = "خطا در آپدیت، استفاده از لیست فعلی..."
                }
            }

            if (configs.isEmpty()) {
                statusText = "هیچ کانفیگی وجود ندارد! لینک را اضافه کنید."
                isBusy = false
                return@launch
            }

            statusText = "۲/۳ در حال تست پینگ سرورها..."
            val pingedList = configs.map { cfg ->
                async {
                    val p = SubParser.measurePing(cfg)
                    cfg.copy(ping = p)
                }
            }.awaitAll().sortedBy { if (it.ping > 0) it.ping else Int.MAX_VALUE }

            configs = pingedList

            val bestConfig = pingedList.firstOrNull { it.ping > 0 }
            if (bestConfig != null) {
                statusText = "۳/۳ اتصال به ${bestConfig.name} (${bestConfig.ping}ms)"
                activeConfigId = bestConfig.id
                isConnected = true
                onConnectRequest(bestConfig)
            } else {
                statusText = "هیچ سروری پاسخ نداد!"
            }
            isBusy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "آونگ وی‌پی‌ان",
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) {
                            importNewSubscription(clipText)
                        } else {
                            statusText = "کلیپ‌بورد خالی است!"
                        }
                    },
                    modifier = Modifier.background(CardBg, CircleShape)
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = "کلیپ‌بورد", tint = AccentCyan)
                }

                IconButton(
                    onClick = {
                        val scanner = GmsBarcodeScanning.getClient(context)
                        scanner.startScan().addOnSuccessListener { barcode ->
                            barcode.rawValue?.let { importNewSubscription(it) }
                        }
                    },
                    modifier = Modifier.background(CardBg, CircleShape)
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = "اسکن QR", tint = AccentCyan)
                }

                if (configs.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (isConnected) onDisconnectRequest()
                            isConnected = false
                            activeConfigId = null
                            configs = emptyList()
                            subInfo = null
                            statusText = "کانفیگ‌ها پاک شدند"
                        },
                        modifier = Modifier.background(CardBg, CircleShape)
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "پاک کردن", tint = DangerRed)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        subInfo?.let { info ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = info.title,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        val daysLeft = if (info.expireTimestamp > 0) {
                            val diff = info.expireTimestamp - (System.currentTimeMillis() / 1000)
                            if (diff > 0) "${TimeUnit.SECONDS.toDays(diff)} روز باقی‌مانده" else "منقضی شده"
                        } else "نامحدود"
                        Text(text = daysLeft, color = AccentCyan, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val usedStr = Formatter.formatShortFileSize(context, info.usedBytes)
                    val totalStr = if (info.totalBytes > 0) Formatter.formatShortFileSize(context, info.totalBytes) else "∞"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "مصرف‌شده: $usedStr", color = TextSecondary, fontSize = 12.sp)
                        Text(text = "حجم کل: $totalStr", color = TextSecondary, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(DarkBg)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(info.remainingRatio)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (info.remainingRatio > 0.2f) AccentCyan else DangerRed)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        val buttonColor by animateColorAsState(
            targetValue = when {
                isConnected -> ConnectedGreen
                isBusy -> Color(0xFFFFB300)
                else -> AccentCyan
            }, label = "btnColor"
        )

        Box(
            modifier = Modifier
                .size(135.dp)
                .clip(CircleShape)
                .background(buttonColor.copy(alpha = 0.15f))
                .border(3.dp, buttonColor, CircleShape)
                .clickable(enabled = !isBusy) { runAllInOneConnect() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Bolt,
                    contentDescription = "اتصال هوشمند",
                    tint = buttonColor,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isConnected) "متصل" else if (isBusy) "صبر کنید" else "اتصال خودکار",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = statusText,
            color = TextSecondary,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (showConfigList && configs.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(configs, key = { it.id }) { config ->
                    val isSelected = config.id == activeConfigId && isConnected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) ConnectedGreen.copy(alpha = 0.15f) else CardBg)
                            .border(
                                width = if (isSelected) 1.5.dp else 0.dp,
                                color = if (isSelected) ConnectedGreen else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                activeConfigId = config.id
                                isConnected = true
                                statusText = "متصل به ${config.name}"
                                onConnectRequest(config)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.name,
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = config.protocol,
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        val pingText = when {
                            config.ping > 0 -> "${config.ping} ms"
                            config.ping == -2 -> "تایم‌اوت"
                            else -> "---"
                        }
                        val pingColor = when {
                            config.ping in 1..400 -> ConnectedGreen
                            config.ping > 400 -> Color(0xFFFFB300)
                            config.ping == -2 -> DangerRed
                            else -> TextSecondary
                        }

                        Text(
                            text = pingText,
                            color = pingColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}