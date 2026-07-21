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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.jakewharton.rxbinding2.view.clicks
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.databinding.TrustedSendersAddDialogBinding
import dev.octoshrimpy.quik.databinding.TrustedSendersControllerBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.TrustedSender
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class TrustedSendersController :
    QkController<TrustedSendersControllerBinding, TrustedSendersView, TrustedSendersState, TrustedSendersPresenter>(),
    TrustedSendersView {

    @Inject override lateinit var presenter: TrustedSendersPresenter
    @Inject lateinit var colors: Colors

    private val adapter = TrustedSendersAdapter()
    private val saveAddressSubject: Subject<String> = PublishSubject.create()
    private val confirmRemoveLockedSubject: Subject<TrustedSender> = PublishSubject.create()

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): TrustedSendersControllerBinding =
        TrustedSendersControllerBinding.inflate(inflater, container, false)

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.message_sorting_trusted_senders_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.add.setBackgroundTint(colors.theme().theme)
        binding.add.setTint(colors.theme().textPrimary)
        adapter.emptyView = binding.empty
        binding.senders.adapter = adapter
    }

    override fun render(state: TrustedSendersState) {
        adapter.updateData(state.senders)
    }

    override fun removeSender(): Observable<TrustedSender> = adapter.removeSender
    override fun confirmRemoveLocked(): Observable<TrustedSender> = confirmRemoveLockedSubject
    override fun addAddress(): Observable<*> = binding.add.clicks()
    override fun saveAddress(): Observable<String> = saveAddressSubject

    override fun showAddDialog() {
        val layout = TrustedSendersAddDialogBinding.inflate(LayoutInflater.from(activity))
        AlertDialog.Builder(activity!!)
            .setView(layout.root)
            .setPositiveButton(R.string.message_sorting_trusted_senders_dialog_add) { _, _ ->
                saveAddressSubject.onNext(layout.input.text.toString())
            }
            .setNegativeButton(R.string.button_cancel) { _, _ -> }
            .show()
    }

    override fun showRemoveLockedConfirmDialog(sender: TrustedSender) {
        AlertDialog.Builder(activity!!)
            .setTitle(R.string.message_sorting_trusted_senders_remove_locked_title)
            .setMessage(R.string.message_sorting_trusted_senders_remove_locked_message)
            .setPositiveButton(R.string.button_delete) { _, _ -> confirmRemoveLockedSubject.onNext(sender) }
            .setNegativeButton(R.string.button_cancel) { _, _ -> }
            .show()
    }

}
