package com.jrweb.avangvpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.jrweb.avangvpn.model.VpnConfig
import com.jrweb.avangvpn.service.AvangVpnService
import com.jrweb.avangvpn.ui.MainScreen
import com.jrweb.avangvpn.ui.theme.AvangVpnTheme

class MainActivity : ComponentActivity() {

    private var pendingConfig: VpnConfig? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingConfig?.let { startVpnService(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AvangVpnTheme {
                MainScreen(
                    onConnectRequest = { config ->
                        prepareAndConnect(config)
                    },
                    onDisconnectRequest = {
                        val intent = Intent(this, AvangVpnService::class.java).apply {
                            action = AvangVpnService.ACTION_DISCONNECT
                        }
                        startService(intent)
                    }
                )
            }
        }
    }

    private fun prepareAndConnect(config: VpnConfig) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConfig = config
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService(config)
        }
    }

    private fun startVpnService(config: VpnConfig) {
        val intent = Intent(this, AvangVpnService::class.java).apply {
            action = AvangVpnService.ACTION_CONNECT
            putExtra(AvangVpnService.EXTRA_CONFIG_URI, config.rawUri)
        }
        startForegroundService(intent)
    }
}