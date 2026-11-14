package com.gainandshine.unloop

import com.gainandshine.unloop.bridge.AppsHandler
import com.gainandshine.unloop.bridge.BridgeHandler
import com.gainandshine.unloop.bridge.DataHandler
import com.gainandshine.unloop.bridge.PrefsHandler
import com.gainandshine.unloop.bridge.LimitsHandler
import com.gainandshine.unloop.bridge.PermissionsHandler
import com.gainandshine.unloop.bridge.SettingsHandler
import com.gainandshine.unloop.system.HealthScheduler
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private lateinit var perms: PermissionsHandler

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        perms = PermissionsHandler(this)
        val handlers: List<BridgeHandler> =
            listOf(perms, SettingsHandler(this), LimitsHandler(this), AppsHandler(this), PrefsHandler(this), DataHandler(this))
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "unloop/native").setMethodCallHandler { call, result ->
            if (handlers.none { it.handle(call, result) }) result.notImplemented()
        }
        runCatching { com.gainandshine.unloop.system.PaymentAside.restoreIfStuck(this) }
        runCatching { HealthScheduler.enqueue(this); HealthScheduler.planBoundary(this) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::perms.isInitialized) perms.onPermissionResult(requestCode)
    }

}
