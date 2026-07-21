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
import android.view.ViewGroup
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.databinding.TrustedSenderListItemBinding
import dev.octoshrimpy.quik.model.TrustedSender
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject

class TrustedSendersAdapter : QkRealmAdapter<TrustedSender, QkBindingViewHolder<TrustedSenderListItemBinding>>() {

    val removeSender: Subject<TrustedSender> = PublishSubject.create()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<TrustedSenderListItemBinding> {
        val binding = TrustedSenderListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding).apply {
            binding.remove.setOnClickListener {
                val sender = getItem(adapterPosition) ?: return@setOnClickListener
                removeSender.onNext(sender)
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<TrustedSenderListItemBinding>, position: Int) {
        val item = getItem(position)!!
        holder.binding.address.text = item.address
        holder.binding.remove.setImageResource(
            if (item.locked) R.drawable.ic_lock_black_24dp else R.drawable.ic_close_black_24dp
        )
    }

}
