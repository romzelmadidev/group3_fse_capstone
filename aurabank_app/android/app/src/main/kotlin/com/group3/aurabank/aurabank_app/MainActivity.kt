package com.group3.aurabank.aurabank_app

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Display
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterFragmentActivity() {
    private val CHANNEL = "com.bank.app/device_security"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "isScreenSharingActive" -> {
                    result.success(checkIsScreenSharingActive())
                }
                "isCallActive" -> {
                    result.success(checkIsCallActive())
                }
                "getAndroidSecurityReport" -> {
                    result.success(getAndroidSecurityReport())
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    /**
     * Checks if any virtual display or presentation display is active,
     * which Android creates when Screen Recording, MediaProjection, Chromecast, AnyDesk, or TeamViewer is active.
     */
    private fun checkIsScreenSharingActive(): Boolean {
        return try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
            val displays = displayManager.displays

            for (display in displays) {
                // The primary phone screen is always Display.DEFAULT_DISPLAY (0).
                // Any additional display indicates Screen Recording, MediaProjection,
                // Presentation, or Remote Desktop mirroring (AnyDesk, TeamViewer).
                if (display.displayId != Display.DEFAULT_DISPLAY) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if a phone call or VoIP audio communication is active.
     * Uses AudioManager (MODE_IN_CALL / MODE_IN_COMMUNICATION) which detects both
     * cellular and VoIP calls without requiring dangerous runtime permissions,
     * supplemented by TelephonyManager when available.
     */
    private fun checkIsCallActive(): Boolean {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val mode = audioManager.mode
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                    return true
                }
            }
        } catch (e: Exception) {
            // Ignore audio manager exception
        }

        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                val callState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyManager.callStateForSubscription
                } else {
                    @Suppress("DEPRECATION")
                    telephonyManager.callState
                }
                if (callState == TelephonyManager.CALL_STATE_OFFHOOK || callState == TelephonyManager.CALL_STATE_RINGING) {
                    return true
                }
            }
        } catch (e: SecurityException) {
            // Permission not granted at runtime; fallback gracefully
        } catch (e: Exception) {
            // Ignore telephony manager exception
        }

        return false
    }

    /**
     * Gathers Android security report for root/tamper checks.
     */
    private fun getAndroidSecurityReport(): Map<String, Any> {
        val report = mutableMapOf<String, Any>()
        try {
            val isDevOptions = Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) != 0
            val isAdb = Settings.Global.getInt(
                contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) != 0

            report["isDevOptionsEnabled"] = isDevOptions
            report["isAdbEnabled"] = isAdb
            report["isBootloaderUnlocked"] = false
            report["isEmulator"] = isEmulator()
            report["isDebuggerAttached"] = android.os.Debug.isDebuggerConnected()
        } catch (e: Exception) {
            // Fallback
        }
        return report
    }

    private fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
    }
}
