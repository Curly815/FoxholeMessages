/*
 * Copyright (C) 2026 Foxhole Messages contributors
 *
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
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.classifier.MessageCategoryBackfill
import dev.octoshrimpy.quik.interactor.DeleteOldOtps
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class ClassifyExistingMessagesWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 4000
        // Literal rather than simpleName - see HousekeepingWorker for why.
        private const val WORKER_TAG = "ClassifyExistingMessagesWorker"

        fun trigger(context: Context) {
            val request = OneTimeWorkRequest.Builder(ClassifyExistingMessagesWorker::class.java)
                .addTag(WORKER_TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            // REPLACE, not KEEP: KEEP silently discards the new request whenever a previous one
            // under this name is still unfinished, and a run that was interrupted (or whose stored
            // worker class no longer resolves after an obfuscated rebuild) stays unfinished
            // indefinitely - which makes the button permanently dead with no feedback at all. This
            // is explicitly user-initiated, so the newest request should always be the one that runs.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORKER_TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }

    @Inject lateinit var backfill: MessageCategoryBackfill
    @Inject lateinit var deleteOldOtps: DeleteOldOtps
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var prefs: Preferences

    // Everything here is wrapped so failures reach the file log. WorkManager swallows exceptions
    // thrown out of doWork() into its own logcat output, which is invisible to anyone diagnosing
    // this from a device - it looks identical to the worker simply never finishing.
    override fun doWork(): Result = try {
        Timber.v("started")

        val notificationManagerCompat = NotificationManagerCompat.from(applicationContext)

        Timber.v("running backfill")
        val total = backfill.run()

        if (total > 0) {
            notificationManagerCompat.cancel(NOTIFICATION_ID)
            Timber.v("backfill finished. processed $total messages")
        } else {
            Timber.v("backfill finished. nothing to process")
        }

        // The backfill can newly tag messages as OTPs that were never checked before, but OTP
        // retention otherwise only runs on its own daily schedule - so without this, asking for a
        // re-sort appears to do nothing at all for up to a day. Run synchronously rather than via
        // execute(), which subscribes asynchronously and would be cut off when doWork() returns.
        Timber.v("applying otp retention (retention set to ${prefs.otpRetentionDays.get()} days)")
        deleteOldOtps.buildObservable(Unit).blockingSubscribe(
            {},
            { error -> Timber.e(error, "otp retention failed") }
        )

        prefs.initialClassificationDone.set(true)

        Timber.v("finished")
        Result.success()
    } catch (e: Throwable) {
        Timber.e(e, "failed")
        Result.failure()
    }

    override fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notificationManager.getNotificationForClassification().build())

}
