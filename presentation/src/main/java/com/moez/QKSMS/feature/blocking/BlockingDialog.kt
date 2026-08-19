/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
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
package dev.octoshrimpy.quik.feature.blocking

import android.app.Activity
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.interactor.MarkBlocked
import dev.octoshrimpy.quik.interactor.MarkUnblocked
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.util.Preferences
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.inject.Inject

// TODO: Once we have a custom dialog based on conductor, turn this into a controller
class BlockingDialog @Inject constructor(
    private val blockingManager: BlockingClient,
    private val conversationRepo: ConversationRepository,
    private val prefs: Preferences,
    private val markBlocked: MarkBlocked,
    private val markUnblocked: MarkUnblocked
) {

    fun show(activity: Activity, conversationIds: List<Long>, block: Boolean) = GlobalScope.launch {
        val addresses = conversationIds.toLongArray()
                .let { conversationRepo.getConversations(*it) }
                .flatMap { conversation -> conversation.recipients }
                .map { it.address }
                .distinct()

        if (addresses.isEmpty()) {
            return@launch
        }

        if (block) {
            markBlocked.execute(MarkBlocked.Params(conversationIds, prefs.blockingManager.get(), null))
            blockingManager.block(addresses).subscribe()
        } else {
            markUnblocked.execute(conversationIds)
            blockingManager.unblock(addresses).subscribe()
        }
    }

}
