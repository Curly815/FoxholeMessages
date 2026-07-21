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
import dev.octoshrimpy.quik.repository.SenderCategoryRuleRepository
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject

class SenderRulesPresenter @Inject constructor(
    private val senderRuleRepo: SenderCategoryRuleRepository
) : QkPresenter<SenderRulesView, SenderRulesState>(
    SenderRulesState(rules = senderRuleRepo.getRules())
) {

    override fun bindIntents(view: SenderRulesView) {
        super.bindIntents(view)

        view.removeRule()
            .map { it.id }
            .observeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { id -> senderRuleRepo.removeRule(id) }

        view.editRule()
            .autoDisposable(view.scope())
            .subscribe { rule -> view.showEditDialog(rule) }

        view.categorySelected()
            .withLatestFrom(view.editRule()) { category, rule -> rule.address to category }
            .observeOn(Schedulers.io())
            .autoDisposable(view.scope())
            .subscribe { (address, category) -> senderRuleRepo.setRule(address, category) }
    }

}
