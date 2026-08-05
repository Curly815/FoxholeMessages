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
import android.view.ViewGroup
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.databinding.TrashListItemBinding
import dev.octoshrimpy.quik.interactor.PurgeTrash
import dev.octoshrimpy.quik.model.Message
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TrashAdapter @Inject constructor() : QkRealmAdapter<Message, QkBindingViewHolder<TrashListItemBinding>>() {

    val restoreClicks: PublishSubject<Long> = PublishSubject.create()
    val deleteForeverClicks: PublishSubject<Long> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<TrashListItemBinding> {
        val binding = TrashListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding).apply {
            binding.restore.setOnClickListener {
                getItem(adapterPosition)?.let { message -> restoreClicks.onNext(message.id) }
            }
            binding.deleteForever.setOnClickListener {
                getItem(adapterPosition)?.let { message -> deleteForeverClicks.onNext(message.id) }
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<TrashListItemBinding>, position: Int) {
        val message = getItem(position) ?: return
        val binding = holder.binding

        binding.address.text = message.address
        binding.snippet.text = message.getSummary()

        val deletedAt = message.deletedAt ?: 0
        val daysElapsed = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - deletedAt)
        val daysLeft = (PurgeTrash.TRASH_RETENTION_DAYS - daysElapsed).coerceAtLeast(0)
        binding.daysLeft.text = when (daysLeft) {
            0L -> binding.root.context.getString(R.string.trash_last_day)
            else -> binding.root.context.getString(R.string.trash_days_left, daysLeft.toInt())
        }
    }

}
