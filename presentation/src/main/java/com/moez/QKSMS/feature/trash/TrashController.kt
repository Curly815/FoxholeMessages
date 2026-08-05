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

import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.databinding.TrashControllerBinding
import dev.octoshrimpy.quik.injection.appComponent
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class TrashController : QkController<TrashControllerBinding, TrashView, TrashState, TrashPresenter>(),
    TrashView {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): TrashControllerBinding =
        TrashControllerBinding.inflate(inflater, container, false)

    override val restoreClicks by lazy { trashAdapter.restoreClicks }
    override val deleteForeverClicks by lazy { trashAdapter.deleteForeverClicks }
    override val confirmDeleteForeverIntent: Subject<Long> = PublishSubject.create()
    override val emptyTrashIntent: Subject<Unit> = PublishSubject.create()
    override val confirmEmptyTrashIntent: Subject<Unit> = PublishSubject.create()
    override val backClicked: Subject<Unit> = PublishSubject.create()

    @Inject lateinit var trashAdapter: TrashAdapter
    @Inject override lateinit var presenter: TrashPresenter

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun onViewCreated() {
        super.onViewCreated()
        trashAdapter.emptyView = binding.empty
        binding.messages.adapter = trashAdapter
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.trash_title)
        showBackButton(true)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.trash, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.emptyTrash -> emptyTrashIntent.onNext(Unit)
        }
        return true
    }

    override fun handleBack(): Boolean {
        backClicked.onNext(Unit)
        return true
    }

    override fun render(state: TrashState) {
        trashAdapter.updateData(state.data)
    }

    override fun showDeleteForeverDialog(messageId: Long) {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.trash_delete_forever_title)
                .setMessage(R.string.trash_delete_forever_message)
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteForeverIntent.onNext(messageId) }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun showEmptyTrashDialog() {
        AlertDialog.Builder(activity!!)
                .setTitle(R.string.trash_empty_all_title)
                .setMessage(R.string.trash_empty_all_message)
                .setPositiveButton(R.string.button_delete) { _, _ -> confirmEmptyTrashIntent.onNext(Unit) }
                .setNegativeButton(R.string.button_cancel, null)
                .show()
    }

    override fun goBack() {
        router.popCurrentController()
    }

}
