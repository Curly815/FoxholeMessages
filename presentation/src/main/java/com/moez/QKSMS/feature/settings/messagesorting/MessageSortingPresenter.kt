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
package dev.octoshrimpy.quik.feature.settings.messagesorting

import android.content.Context
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.classifier.Category
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.worker.ClassifyExistingMessagesWorker
import io.reactivex.rxkotlin.plusAssign
import javax.inject.Inject

class MessageSortingPresenter @Inject constructor(
    private val context: Context,
    private val navigator: Navigator,
    private val prefs: Preferences
) : QkPresenter<MessageSortingView, MessageSortingState>(
    MessageSortingState(prefs.autoSortEnabled.get())
) {

    init {
        disposables += prefs.autoSortEnabled.asObservable()
            .subscribe { enabled -> newState { copy(autoSortEnabled = enabled) } }
    }

    override fun bindIntents(view: MessageSortingView) {
        super.bindIntents(view)

        view.preferenceClicks()
            .autoDisposable(view.scope())
            .subscribe { preference ->
                when (preference.id) {
                    R.id.autoSort -> prefs.autoSortEnabled.set(!prefs.autoSortEnabled.get())
                    R.id.notificationsPersonal -> navigator.showCategoryNotificationSettings(Category.PERSONAL.name)
                    R.id.notificationsPromotional -> navigator.showCategoryNotificationSettings(Category.PROMOTIONAL.name)
                    R.id.notificationsTransactional -> navigator.showCategoryNotificationSettings(Category.TRANSACTIONAL.name)
                    R.id.senderRules -> view.showSenderRules()
                    R.id.sortExisting -> view.showSortExistingConfirmDialog()
                    R.id.trustedSenders -> view.showTrustedSenders()
                }
            }

        view.confirmSortExistingIntent()
            .autoDisposable(view.scope())
            .subscribe { ClassifyExistingMessagesWorker.trigger(context) }
    }

}
