package com.pocketrealm.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.core.content.IntentCompat
import com.pocketrealm.log.AppLog

/**
 * F6: PackageInstaller status sink. The session commit's PendingIntent
 * targets this activity, so the system itself launches it when a status
 * arrives (reviving the process if Android killed the app mid-flow); the
 * confirmation UI is then started from this foreground activity. A
 * receiver-context startActivity is silently blocked as a background
 * activity launch whenever the app is not visible at commit time, which
 * stranded completed downloads behind a dialog that never appeared.
 *
 * Forward-compat contract: an in-flight install's status delivery
 * resolves this component by name after the package is replaced, so the
 * class name must never change.
 */
class AppUpdateInstallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The confirmation UI is delivered as EXTRA_INTENT; without
                // starting it the session stalls with no dialog.
                val confirmIntent = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java,
                )
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        startActivity(confirmIntent)
                    } catch (e: ActivityNotFoundException) {
                        AppLog.e(TAG, "confirmation UI could not be started: ${e.message}")
                    }
                } else {
                    AppLog.e(TAG, "pending user action carried no confirmation intent")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.i(TAG, "update install committed; the new version starts on next launch")
                // Runs in the newly installed process (the commit's
                // explicit component resolves against the new APK) with
                // data preserved, so the staged download is reclaimable.
                runCatching { AppUpdateCoordinator.clearDownloadedApk(this) }
            }
            else -> AppLog.e(
                TAG,
                "update install failed: status=$status message=$message " +
                    "(a signature mismatch is refused here — the installed app and its data " +
                    "are untouched)",
            )
        }
        finish()
    }

    private companion object {
        const val TAG = "AppUpdate"
    }
}
