package com.pocketrealm.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketrealm.log.AppLog

/**
 * F6: PackageInstaller status sink. The session commit's PendingIntent
 * delivers STATUS here; failures surface in Diagnostics via the log ring.
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstallerExtra.EXTRA_STATUS, Integer.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstallerExtra.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstallerStatus.PENDING_USER_ACTION -> {
                // The confirmation UI is delivered as EXTRA_INTENT; without
                // starting it the session stalls with no dialog.
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                } else {
                    AppLog.e(TAG, "pending user action carried no confirmation intent")
                }
            }
            PackageInstallerStatus.SUCCESS ->
                AppLog.i(TAG, "update install committed; the new version starts on next launch")
            else -> AppLog.e(
                TAG,
                "update install failed: status=$status message=$message " +
                    "(a signature mismatch is refused here — the installed app and its data " +
                    "are untouched)",
            )
        }
    }

    private object PackageInstallerExtra {
        const val EXTRA_STATUS = "android.content.pm.extra.STATUS"
        const val EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE"
    }

    private object PackageInstallerStatus {
        const val PENDING_USER_ACTION = 1
        const val SUCCESS = 0
    }

    companion object {
        private const val TAG = "AppUpdate"
        val RELEASES_PAGE_URL: String =
            "https://github.com/${AppUpdateCoordinator.UPDATES_REPO}/releases/latest"
    }
}
