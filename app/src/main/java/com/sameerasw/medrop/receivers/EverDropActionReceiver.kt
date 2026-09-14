package com.sameerasw.medrop.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sameerasw.medrop.utils.EverDropWifiDirectManager

/**
 * EverDropActionReceiver
 *
 * Dedicated BroadcastReceiver for handling notification actions:
 * - Accept incoming transfer
 * - Reject / Dismiss incoming transfer
 *
 * Executes instantly without starting foreground services, avoiding
 * ForegroundServiceDidNotStartInTimeException and background start restrictions.
 */
class EverDropActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACCEPT = "com.sameerasw.medrop.action.ACCEPT_TRANSFER"
        const val ACTION_REJECT = "com.sameerasw.medrop.action.REJECT_TRANSFER"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        when (intent.action) {
            ACTION_ACCEPT -> {
                EverDropWifiDirectManager.acceptIncomingTransfer(context)
            }
            ACTION_REJECT -> {
                EverDropWifiDirectManager.rejectIncomingTransfer(context)
            }
        }
    }
}
