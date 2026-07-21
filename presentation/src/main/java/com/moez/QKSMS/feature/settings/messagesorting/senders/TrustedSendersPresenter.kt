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
package dev.octoshrimpy.quik.feature.settings.messagesorting.senders

import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.repository.TrustedSenderRepository
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

class TrustedSendersPresenter @Inject constructor(
    private val trustedSenderRepo: TrustedSenderRepository
) : QkPresenter<TrustedSendersView, TrustedSendersState>(
    TrustedSendersState(senders = trustedSenderRepo.getTrustedSenders())
) {

    override fun bindIntents(view: TrustedSendersView) {
        super.bindIntents(view)

        val removeSenderShared = view.removeSender().share()

        removeSenderShared
            .filter { it.locked }
            .autoDisposable(view.scope())
            .subscribe { sender -> view.showRemoveLockedConfirmDialog(sender) }

        removeSenderShared
            .filter { !it.locked }
            .map { it.id }
            .observeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { id -> trustedSenderRepo.removeTrustedSender(id, false) }

        view.confirmRemoveLocked()
            .map { it.id }
            .observeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { id -> trustedSenderRepo.removeTrustedSender(id, true) }

        view.addAddress()
            .autoDisposable(view.scope())
            .subscribe { view.showAddDialog() }

        view.saveAddress()
            .filter { it.isNotBlank() }
            .observeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { address -> trustedSenderRepo.addTrustedSender(address, false) }
    }

}
