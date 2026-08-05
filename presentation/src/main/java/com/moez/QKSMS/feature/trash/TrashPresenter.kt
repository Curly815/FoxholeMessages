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
package dev.octoshrimpy.quik.feature.trash

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.interactor.RestoreMessages
import dev.octoshrimpy.quik.repository.MessageRepository
import javax.inject.Inject

class TrashPresenter @Inject constructor(
    private val messageRepo: MessageRepository,
    private val restoreMessages: RestoreMessages
) : QkPresenter<TrashView, TrashState>(TrashState(
        data = messageRepo.getDeletedMessages()
)) {

    override fun bindIntents(view: TrashView) {
        super.bindIntents(view)

        view.restoreClicks
                .autoDisposable(view.scope())
                .subscribe { messageId -> restoreMessages.execute(listOf(messageId)) }

        // Manual permanent-delete, in addition to the automatic 30-day purge - the user
        // shouldn't have to wait if they want a message gone for good right away
        view.deleteForeverClicks
                .autoDisposable(view.scope())
                .subscribe { messageId -> view.showDeleteForeverDialog(messageId) }

        view.confirmDeleteForeverIntent
                .autoDisposable(view.scope())
                .subscribe { messageId -> messageRepo.deleteMessages(listOf(messageId)) }

        view.backClicked
                .autoDisposable(view.scope())
                .subscribe { view.goBack() }
    }

}
