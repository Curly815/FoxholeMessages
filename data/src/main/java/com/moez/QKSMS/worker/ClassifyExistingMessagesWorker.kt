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
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.util.Preferences
import timber.log.Timber
import javax.inject.Inject

class ClassifyExistingMessagesWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {

    companion object {
        private const val NOTIFICATION_ID = 4000
        private val WORKER_TAG = ClassifyExistingMessagesWorker::class.java.simpleName

        fun trigger(context: Context) {
            val request = OneTimeWorkRequest.Builder(ClassifyExistingMessagesWorker::class.java)
                .addTag(WORKER_TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORKER_TAG, ExistingWorkPolicy.KEEP, request)
        }
    }

    @Inject lateinit var backfill: MessageCategoryBackfill
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var prefs: Preferences

    override fun doWork(): Result {
        Timber.v("started")

        val notificationManagerCompat = NotificationManagerCompat.from(applicationContext)
        val total = backfill.run()

        if (total > 0) {
            notificationManagerCompat.cancel(NOTIFICATION_ID)
            Timber.v("finished. classified $total messages")
        } else {
            Timber.v("no unclassified messages")
        }

        prefs.initialClassificationDone.set(true)

        return Result.success()
    }

    override fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notificationManager.getNotificationForClassification().build())

}
