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

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.classifier.Category
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.databinding.SenderRulesControllerBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.SenderCategoryRule
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class SenderRulesController :
    QkController<SenderRulesControllerBinding, SenderRulesView, SenderRulesState, SenderRulesPresenter>(),
    SenderRulesView {

    @Inject override lateinit var presenter: SenderRulesPresenter
    @Inject lateinit var context: Context

    private val adapter = SenderRulesAdapter()
    private val categorySelectedSubject: Subject<String> = PublishSubject.create()

    private val categories = Category.values()
    private val categoryLabels = arrayOf(
        R.string.message_sorting_category_personal,
        R.string.message_sorting_category_promotional,
        R.string.message_sorting_category_transactional,
        R.string.message_sorting_category_unclassified
    )

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): SenderRulesControllerBinding =
        SenderRulesControllerBinding.inflate(inflater, container, false)

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        setTitle(R.string.message_sorting_sender_rules_title)
        showBackButton(true)
    }

    override fun onViewCreated() {
        super.onViewCreated()
        adapter.emptyView = binding.empty
        binding.rules.adapter = adapter
    }

    override fun render(state: SenderRulesState) {
        adapter.updateData(state.rules)
    }

    override fun editRule(): Observable<SenderCategoryRule> = adapter.editRule
    override fun removeRule(): Observable<SenderCategoryRule> = adapter.removeRule
    override fun categorySelected(): Observable<String> = categorySelectedSubject

    override fun showEditDialog(rule: SenderCategoryRule) {
        val checkedIndex = categories.indexOfFirst { it.name == rule.category }.coerceAtLeast(0)
        val labels: Array<CharSequence> = categoryLabels.map { context.getString(it) as CharSequence }.toTypedArray()

        AlertDialog.Builder(activity!!)
            .setTitle(rule.address)
            .setSingleChoiceItems(labels, checkedIndex, DialogInterface.OnClickListener { dialog, which ->
                categorySelectedSubject.onNext(categories[which].name)
                dialog.dismiss()
            })
            .setNegativeButton(R.string.button_cancel) { _, _ -> }
            .show()
    }

}
