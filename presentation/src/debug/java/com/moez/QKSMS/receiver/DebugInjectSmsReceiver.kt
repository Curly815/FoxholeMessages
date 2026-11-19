/*
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.model.MessageContentFilterData
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.worker.ReceiveSmsWorker
import dev.octoshrimpy.quik.worker.ReceiveSmsWorker.Companion.INPUT_DATA_KEY_MESSAGE_ID
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import javax.inject.Inject

/**
 * Test hook for devices that cannot receive real SMS. Lives in the debug source set, with its manifest
 * entry (src/debug/AndroidManifest.xml) and Dagger binding (BuildTypeReceiverModule) alongside, so
 * release and fdroid builds don't contain it at all. Non-exported: only the app's own uid can
 * trigger it, e.g.:
 *
 *   adb shell run-as com.foxhole.messages.debug am broadcast --user 0 \
 *     -n com.foxhole.messages.debug/dev.octoshrimpy.quik.receiver.DebugInjectSmsReceiver \
 *     --es address 5551234567 --es body "hello"
 *
 * Mirrors SmsReceivedReceiver after PDU parsing: insert the message, then run ReceiveSmsWorker,
 * so content filters, blocking, sorting, OTP tagging and notifications all run as for a real SMS.
 *
 * With `--es rawRegexFilter <pattern>` it instead saves a regex content filter WITHOUT validation,
 * to reproduce a bad filter saved by an older build.
 */
class DebugInjectSmsReceiver : BroadcastReceiver() {
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var filterRepo: MessageContentFilterRepository

    override fun onReceive(context: Context, intent: Intent) {
        AndroidInjection.inject(this, context)
        val pendingResult = goAsync()

        val rawRegexFilter = intent.getStringExtra("rawRegexFilter")
        if (rawRegexFilter != null) {
            Single.fromCallable { filterRepo.createFilter(MessageContentFilterData(rawRegexFilter, false, true, false)) }
                .subscribeOn(Schedulers.io())
                .doFinally { pendingResult.finish() }
                .subscribe({ Timber.i("debug: saved unvalidated regex filter") }, { Timber.e(it, "debug: filter insert failed") })
            return
        }

        val address = intent.getStringExtra("address") ?: "5550100199"
        val body = intent.getStringExtra("body") ?: "Debug test message"

        Single.fromCallable { messageRepo.insertReceivedSms(-1, address, body, System.currentTimeMillis()).id }
            .subscribeOn(Schedulers.io())
            .doFinally { pendingResult.finish() }
            .subscribe({ messageId ->
                Timber.i("debug: injected sms messageId=$messageId")
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<ReceiveSmsWorker>()
                        .setInputData(workDataOf(INPUT_DATA_KEY_MESSAGE_ID to messageId))
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build()
                )
            }, { Timber.e(it, "debug: sms inject failed") })
    }
}
