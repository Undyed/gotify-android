package com.github.gotify.init

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.login.LoginActivity
import com.github.gotify.messages.MessagesActivity
import com.github.gotify.service.WebSocketService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.livinglifetechway.quickpermissionskotlin.runWithPermissions
import com.livinglifetechway.quickpermissionskotlin.util.QuickPermissionsOptions
import com.livinglifetechway.quickpermissionskotlin.util.QuickPermissionsRequest
import org.tinylog.kotlin.Logger

internal class InitializationActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private var splashScreenActive = true

    @RequiresApi(Build.VERSION_CODES.S)
    private val activityResultLauncher =
        registerForActivityResult(StartActivityForResult()) {
            requestAlarmPermissionOrOpenMessages()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        Logger.info("Entering ${javaClass.simpleName}")

        installSplashScreen().setKeepOnScreenCondition { splashScreenActive }

        if (settings.tokenExists()) {
            runWithPostNotificationsPermission {
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
                    // Android 14 and above
                    requestAlarmPermissionOrOpenMessages()
                } else {
                    // Android 13 and below
                    openMessages()
                }
            }
        } else {
            showLogin()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestAlarmPermissionOrOpenMessages() {
        val manager = ContextCompat.getSystemService(this, AlarmManager::class.java)
        if (manager?.canScheduleExactAlarms() == true) {
            openMessages()
        } else {
            stopSlashScreen()
            alarmDialog()
        }
    }

    private fun showLogin() {
        splashScreenActive = false
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    // FORK-BEGIN: offline first startup. The session is validated by MessagesActivity in the
    // background, so a missing network connection no longer blocks the app.
    private fun openMessages() {
        splashScreenActive = false
        startActivity(Intent(this, MessagesActivity::class.java))
        startPushService()
        finish()
    }

    private fun startPushService() {
        val serviceIntent = Intent(this, WebSocketService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
    // FORK-END

    @RequiresApi(Build.VERSION_CODES.S)
    private fun alarmDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.permissions_alarm_prompt))
            .setPositiveButton(getString(R.string.permissions_dialog_grant)) { _, _ ->
                Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:$packageName".toUri()
                ).apply {
                    activityResultLauncher.launch(this)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun runWithPostNotificationsPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 and above
            val quickPermissionsOption = QuickPermissionsOptions(
                handleRationale = true,
                handlePermanentlyDenied = true,
                preRationaleAction = { stopSlashScreen() },
                rationaleMethod = { req -> processPermissionRationale(req) },
                permissionsDeniedMethod = { req -> processPermissionRationale(req) },
                permanentDeniedMethod = { req -> processPermissionsPermanentDenied(req) }
            )
            runWithPermissions(
                Manifest.permission.POST_NOTIFICATIONS,
                options = quickPermissionsOption,
                callback = action
            )
        } else {
            // Android 12 and below
            action()
        }
    }

    private fun stopSlashScreen() {
        splashScreenActive = false
        setContentView(R.layout.splash)
    }

    private fun processPermissionRationale(req: QuickPermissionsRequest) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.permissions_notification_denied_temp))
            .setPositiveButton(getString(R.string.permissions_dialog_grant)) { _, _ ->
                req.proceed()
            }
            .setCancelable(false)
            .show()
    }

    private fun processPermissionsPermanentDenied(req: QuickPermissionsRequest) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.permissions_notification_denied_permanent))
            .setPositiveButton(getString(R.string.permissions_dialog_grant)) { _, _ ->
                req.openAppSettings()
            }
            .setCancelable(false)
            .show()
    }
}
