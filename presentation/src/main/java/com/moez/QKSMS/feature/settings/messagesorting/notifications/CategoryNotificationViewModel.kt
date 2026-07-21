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
package dev.octoshrimpy.quik.feature.settings.messagesorting.notifications

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.classifier.Category
import dev.octoshrimpy.quik.common.base.QkViewModel
import dev.octoshrimpy.quik.util.Preferences
import io.reactivex.rxkotlin.plusAssign
import javax.inject.Inject
import javax.inject.Named

class CategoryNotificationViewModel @Inject constructor(
    @Named("category") private val category: String,
    private val context: Context,
    private val prefs: Preferences
) : QkViewModel<CategoryNotificationView, CategoryNotificationState>(
    CategoryNotificationState(category = category, categoryTitle = categoryTitle(context, category))
) {

    private val notifications = prefs.categoryNotifications(category)
    private val previews = prefs.categoryPreviews(category)
    private val vibration = prefs.categoryVibration(category)
    private val ringtone = prefs.categoryRingtone(category)

    init {
        disposables += notifications.asObservable()
            .subscribe { enabled -> newState { copy(notificationsEnabled = enabled) } }

        disposables += previews.asObservable()
            .subscribe { enabled -> newState { copy(previewEnabled = enabled) } }

        disposables += vibration.asObservable()
            .subscribe { enabled -> newState { copy(vibrationEnabled = enabled) } }

        disposables += ringtone.asObservable()
            .map { uriString ->
                uriString.takeIf { it.isNotEmpty() }
                    ?.let(Uri::parse)
                    ?.let { uri -> RingtoneManager.getRingtone(context, uri) }?.getTitle(context)
                    ?: context.getString(R.string.settings_ringtone_none)
            }
            .subscribe { title -> newState { copy(ringtoneName = title) } }
    }

    override fun bindView(view: CategoryNotificationView) {
        super.bindView(view)

        view.preferenceClickIntent
            .autoDisposable(view.scope())
            .subscribe { preferenceView ->
                when (preferenceView.id) {
                    R.id.notifications -> notifications.set(!notifications.get())
                    R.id.previews -> previews.set(!previews.get())
                    R.id.vibration -> vibration.set(!vibration.get())
                    R.id.ringtone -> view.showRingtonePicker(
                        ringtone.get().takeIf { it.isNotEmpty() }?.let(Uri::parse)
                    )
                }
            }

        view.ringtoneSelectedIntent
            .autoDisposable(view.scope())
            .subscribe { uri -> ringtone.set(uri) }
    }

    companion object {
        fun categoryTitle(context: Context, category: String): String = when (category) {
            Category.PERSONAL.name -> context.getString(R.string.message_sorting_category_personal)
            Category.PROMOTIONAL.name -> context.getString(R.string.message_sorting_category_promotional)
            Category.TRANSACTIONAL.name -> context.getString(R.string.message_sorting_category_transactional)
            else -> context.getString(R.string.message_sorting_category_unclassified)
        }
    }

}
