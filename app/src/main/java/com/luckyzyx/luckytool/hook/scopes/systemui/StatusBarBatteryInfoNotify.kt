package com.luckyzyx.luckytool.hook.scopes.systemui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.RemoteViews
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.constructor
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.type.android.BroadcastReceiverClass
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.highcapable.yukihookapi.hook.type.java.StringClass
import com.luckyzyx.luckytool.R
import com.luckyzyx.luckytool.hook.scope.ScopeSystemUI
import com.luckyzyx.luckytool.hook.utils.DensityUtils
import com.luckyzyx.luckytool.hook.utils.NotifyUtils
import com.luckyzyx.luckytool.hook.utils.ModulePrefs
import com.luckyzyx.luckytool.hook.utils.VariousClass
import com.luckyzyx.luckytool.hook.utils.battery.BatteryControllerUtils
import com.luckyzyx.luckytool.hook.utils.battery.IChargerUtils
import java.io.StringReader
import java.util.Properties

object StatusBarBatteryInfoNotify : YukiBaseHooker() {

    private var hostApplication = ScopeSystemUI.hostApplication
    private var hostClassLoader = ScopeSystemUI.hostClassLoader
    private var hostResources = ScopeSystemUI.hostResources
    private var moduleResources = ScopeSystemUI.moduleResources

    private const val NOTIFY_ID = 112233
    private val chargeInfo: Properties by lazy { getChargeInfo() }
    private var oplusCharger: Any? = null
    private var batteryController: Any? = null

    override fun onHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!ModulePrefs.getBoolean("battery_information_display", false)) return
        val displayMode = ModulePrefs.getString("battery_information_display_mode", "0")
        if (displayMode == "0") return

        val chargingAnimationImpl = VariousClass(
            mapOf(
                "C11-C12" to "com.oplusos.systemui.keyguard.charginganim.OplusChargingAnimationImpl",
                "C13-C14" to "com.oplus.systemui.keyguard.charginganim.ChargingAnimationImpl"
            )
        ).toClassOrNull(hostClassLoader) ?: return

        chargingAnimationImpl.apply {
            constructor().hook {
                after {
                    val receiver = object : Any() {}
                    BroadcastReceiverClass.method {
                        name = "onReceive"
                        param(Context::class.java, Intent::class.java)
                    }.hook {
                        before {
                            initSend()
                        }
                    }.onNoSuchMethod {
                        YLog.error("StatusBarBatteryInfoNotify -> BroadcastReceiver.onReceive not found")
                    }

                    try {
                        hostApplication.registerReceiver(
                            receiver as android.content.BroadcastReceiver,
                            IntentFilter().apply {
                                addAction(Intent.ACTION_BATTERY_CHANGED)
                                addAction(Intent.ACTION_POWER_CONNECTED)
                                addAction(Intent.ACTION_POWER_DISCONNECTED)
                            }
                        )
                    } catch (e: Exception) {
                        YLog.error("StatusBarBatteryInfoNotify -> registerReceiver", e)
                    }
                }
            }

            method {
                name = "show"
                param(IntType)
            }.hook {
                after {
                    initSend()
                }
            }

            method {
                name = "dismiss"
            }.hook {
                after {
                    clearNotification(hostApplication)
                }
            }
        }

        BatteryControllerUtils(hostClassLoader!!).apply {
            val cls = getBatteryControllerImplClass()
            cls?.constructor()?.hook {
                after {
                    batteryController = instance
                }
            }

            cls?.method {
                name = "fireBatteryLevelChanged"
            }?.hook {
                after {
                    initSend()
                }
            }
        }
    }

    private fun initSend() {
        val displayMode = ModulePrefs.getString("battery_information_display_mode", "0")

        try {
            chargeInfo
            if (!::chargeInfo.isInitialized) {
                YLog.warn("StatusBarBatteryInfoNotify -> chargeInfo not initialized, skipping notification")
                return
            }

            val plugType = getPlugType(chargeInfo)
            val batteryLevel = BatteryControllerUtils(hostClassLoader!!).let {
                it.getLevel(batteryController)
            }

            when (displayMode) {
                "1" -> {
                    if (plugType != 0) {
                        sendNotification(hostApplication, chargeInfo, plugType, batteryLevel)
                    } else {
                        clearNotification(hostApplication)
                    }
                }
                "2" -> {
                    sendNotification(hostApplication, chargeInfo, plugType, batteryLevel)
                }
                else -> {
                    clearNotification(hostApplication)
                }
            }
        } catch (e: Exception) {
            YLog.error("StatusBarBatteryInfoNotify -> initSend", e)
        }
    }

    private fun sendNotification(
        context: Context,
        properties: Properties,
        plugType: Int,
        batteryLevel: Int
    ) {
        val channelId = "battery_information_notify_channel"
        val channelName = moduleResources.getString(R.string.scope_systemui_battery_info_notify_title)
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
        NotifyUtils.createNotificationChannel(context, channel)

        val remoteViews = RemoteViews(context.packageName, R.layout.layout_battery_notification)
        remoteViews.apply {
            setTextViewText(
                R.id.title,
                "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_title)}：${batteryLevel}%"
            )

            val temp = properties.getProperty("battery_temp", "0").toInt() / 10.0
            val voltage = properties.getProperty("battery_voltage_now", "0").toInt() / 1000
            val currentNow = properties.getProperty("battery_current_now", "0").toInt() / 1000

            val text = when {
                plugType != 0 -> {
                    val chargeTech = properties.getProperty("battery_technology", "Unknown")
                    formatStringInfoLine(
                        "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_temp)}：${temp}℃",
                        "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_voltage)}：${voltage}mV",
                        "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_current)}：${currentNow}mA",
                        "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_charge_tech)}：$chargeTech"
                    )
                }
                else -> {
                    formatStringInfoLine(
                        "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_temp)}：${temp}℃",
                        "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_voltage)}：${voltage}mV",
                        "${moduleResources.getString(R.string.scope_systemui_battery_info_notify_current)}：${currentNow}mA"
                    )
                }
            }

            setTextViewText(R.id.content, text)

            val padding = DensityUtils.dip2px(context, 12f)
            setViewPadding(R.id.content, padding, 0, padding, 0)
        }

        val notify = Notification.Builder(context, channelId).apply {
            setSmallIcon(R.drawable.ic_baseline_battery_alert_24)
            setShowWhen(false)
            setOngoing(true)
            setAutoCancel(false)
            setCustomContentView(remoteViews)
            setCustomBigContentView(remoteViews)
            setStyle(Notification.DecoratedCustomViewStyle())
            customBigContentView = remoteViews
            autoCancel(false)
            ongoing(true)
        }
        NotifyUtils.sendNotification(context, NOTIFY_ID, notify.instance)
    }

    private fun clearNotification(context: Context) {
        NotifyUtils.clearNotification(context, NOTIFY_ID)
    }

    private fun getChargeInfo(): Properties {
        return try {
            val queryChargeInfo = IChargerUtils(hostClassLoader!!).let {
                if (oplusCharger == null) oplusCharger = it.getInstance()
                it.queryChargeInfo(oplusCharger)
            } ?: ""
//            YLog.d("getChargeInfo -> queryChargeInfo : $queryChargeInfo")
            val properties = Properties()
            if (queryChargeInfo.isNotBlank()) {
                properties.load(StringReader(queryChargeInfo))
            } else {
                // Fallback to BatteryManager for Android 17+ / ColorOS 17+
                YLog.warn("StatusBarBatteryInfoNotify -> ICharger returned empty, using BatteryManager fallback")
                return getChargeInfoFromBatteryManager()
            }
            properties
        } catch (e: Exception) {
            YLog.error("StatusBarBatteryInfoNotify -> getChargeInfo from ICharger failed", e)
            // Fallback to BatteryManager
            try {
                getChargeInfoFromBatteryManager()
            } catch (e2: Exception) {
                YLog.error("StatusBarBatteryInfoNotify -> BatteryManager fallback also failed", e2)
                Properties()
            }
        }
    }

    private fun getChargeInfoFromBatteryManager(): Properties {
        val intent = hostApplication.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val properties = Properties()
        intent?.let {
            // Basic battery info
            properties.setProperty("battery_status", it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 1).toString())
            properties.setProperty("battery_health", it.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 1).toString())
            properties.setProperty("battery_present", it.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, true).toString())
            properties.setProperty("battery_capacity", it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0).toString())
            properties.setProperty("battery_scale", it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).toString())
            properties.setProperty("battery_voltage_now", it.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0).toString())
            properties.setProperty("battery_temp", it.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0).toString())
            properties.setProperty("battery_technology", it.getStringExtra(android.os.BatteryManager.EXTRA_TECHNOLOGY) ?: "Li-ion")
            
            // Plugged status
            val plugged = it.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
            properties.setProperty("chargerAcOnline", (plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC).toString())
            properties.setProperty("chargerUSBOnline", (plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB).toString())
            properties.setProperty("chargerWirelessOnline", (plugged == android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS).toString())
            
            // Try to get current from BatteryManager
            try {
                val batteryManager = hostApplication.getSystemService(android.content.Context.BATTERY_SERVICE) as? android.os.BatteryManager
                batteryManager?.let { bm ->
                    val currentNow = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                    val chargeCounter = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    properties.setProperty("battery_current_now", currentNow.toString())
                    properties.setProperty("battery_charge_counter", chargeCounter.toString())
                }
            } catch (e: Exception) {
                YLog.warn("StatusBarBatteryInfoNotify -> Failed to get current from BatteryManager", e)
            }
            
            // Set default values for missing OPLUS-specific fields
            properties.setProperty("sub_soc", "0")
            properties.setProperty("battery_temp_not_plug", properties.getProperty("battery_temp", "0"))
            properties.setProperty("battery_voltage_min", properties.getProperty("battery_voltage_now", "0"))
            properties.setProperty("sub_voltage", "0")
            properties.setProperty("battery_charge_now", "0")
            properties.setProperty("charger_type", "")
            properties.setProperty("usb_fast_chg_type", "0")
            properties.setProperty("wireless_enable_tx", "0")
            properties.setProperty("wireless_current_now", "0")
            properties.setProperty("wireless_voltage_now", "0")
        }
        return properties
    }

    private fun getPlugType(properties: Properties): Int {
        if (properties.getBooleanProperty("chargerAcOnline")) {
            return 1
        }
        if (properties.getBooleanProperty("chargerUSBOnline")) {
            return 2
        }
        if (properties.getBooleanProperty("chargerWirelessOnline")) {
            return 4
        }
        return 0
    }

    private fun formatStringInfoSpace(vararg info: String) = formatStringInfo(info.toList(), " ")
    private fun formatStringInfoLine(vararg info: String) = formatStringInfo(info.toList(), "\n")
    private fun formatStringInfo(infos: List<String>, text: String): String {
        var finalText = ""
        infos.forEachIndexed { index, it ->
            if (it != "\n") {
                if (it.isBlank()) return@forEachIndexed
                if (index > 0 && infos[index - 1] != "\n") finalText += text
            }
            finalText += it
        }
        return finalText
    }

    private fun Properties.getBooleanProperty(key: String): Boolean {
        return getProperty(key, "false").toBoolean()
    }
}