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
package dev.octoshrimpy.quik.classifier

import dev.octoshrimpy.quik.repository.MessageRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * Classifies every message that doesn't have a category yet, and separately tags any message
 * that looks like an OTP but was never checked (in batches of 500), for when the user turns on
 * auto-sort after already having messages, or re-runs it manually.
 */
class MessageCategoryBackfill @Inject constructor(
    private val messageRepo: MessageRepository,
    private val messageCategorizer: MessageCategorizer,
    private val otpDetector: OtpDetector
) {

    fun run(onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val categorizer = messageCategorizer.bulkCategorizer()
        var failures = 0

        // Per-message isolation, matching what ReceiveSmsWorker already does on the live path: one
        // message that can't be classified must not abandon the whole run, or a single bad row
        // leaves every remaining message uncategorized and every conversation stuck in Personal.
        val total = messageRepo.categorizeUnclassifiedMessages(
            categorize = { address, body ->
                try {
                    categorizer(address, body).name
                } catch (e: Exception) {
                    failures++
                    Category.UNCLASSIFIED.name
                }
            },
            onProgress = onProgress
        )
        Timber.d("Categorized $total messages ($failures failed, left unclassified)")

        // Live receipt (ReceiveSmsWorker/ReceiveMmsWorker) tags isOtp as each message arrives, but
        // this backfill only ever set category - any message that got its category here rather
        // than from live receipt never had isOtp checked at all, leaving it permanently invisible
        // to OTP retention regardless of the retention setting.
        val tagged = messageRepo.tagOtpMessages(otpDetector::isOtp)
        Timber.d("Tagged $tagged previously-unchecked messages as OTPs")

        return total + tagged
    }

}
