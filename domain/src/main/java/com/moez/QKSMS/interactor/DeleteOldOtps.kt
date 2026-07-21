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
package dev.octoshrimpy.quik.interactor

import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.Flowable
import timber.log.Timber
import javax.inject.Inject

class DeleteOldOtps @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val prefs: Preferences
) : Interactor<Unit>() {

    override fun buildObservable(params: Unit): Flowable<*> = Flowable.fromCallable {
        val maxAge = prefs.otpRetentionDays.get().takeIf { it > 0 } ?: return@fromCallable
        val counts = messageRepo.getOldOtpCounts(maxAge)

        Timber.d("Deleting ${counts.values.sum()} expired OTP messages from ${counts.keys.size} conversations")
        messageRepo.deleteOldOtps(maxAge)
        conversationRepo.updateConversations(counts.keys)
    }

}
