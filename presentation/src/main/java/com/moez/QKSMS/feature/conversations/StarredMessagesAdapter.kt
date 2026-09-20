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
package dev.octoshrimpy.quik.feature.conversations

import android.view.LayoutInflater
import android.view.ViewGroup
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.databinding.StarredMessageListItemBinding
import dev.octoshrimpy.quik.model.Contact
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import javax.inject.Inject

/**
 * Backs the Starred inbox tab, which lists the starred messages themselves rather than the
 * conversations containing them - starring is a per-message action, so pulling in the whole thread
 * would defeat the point. Tapping one opens its conversation.
 */
class StarredMessagesAdapter @Inject constructor(
    private val contactRepo: ContactRepository,
    private val dateFormatter: DateFormatter,
    private val navigator: Navigator,
    private val phoneNumberUtils: PhoneNumberUtils
) : QkRealmAdapter<Message, QkBindingViewHolder<StarredMessageListItemBinding>>() {

    private val contacts by lazy { contactRepo.getContacts() }
    private val contactCache = ContactCache()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): QkBindingViewHolder<StarredMessageListItemBinding> {
        val binding = StarredMessageListItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )

        return QkBindingViewHolder(binding).apply {
            binding.root.setOnClickListener {
                val message = getItem(adapterPosition) ?: return@setOnClickListener
                navigator.showConversation(message.threadId)
            }
        }
    }

    override fun onBindViewHolder(
        holder: QkBindingViewHolder<StarredMessageListItemBinding>,
        position: Int
    ) {
        val message = getItem(position) ?: return
        val binding = holder.binding

        binding.title.text = contactCache[message.address]?.name?.takeIf { it.isNotBlank() }
            ?: message.address
        binding.date.text = dateFormatter.getConversationTimestamp(message.date)
        binding.snippet.text = message.getSummary()
    }

    override fun getItemId(position: Int): Long = getItem(position)?.id ?: -1

    /**
     * Messages don't carry a reference to their contact, and this list spans every conversation,
     * so resolve each address once and keep it. Same approach as ScheduledMessageAdapter.
     */
    private inner class ContactCache : HashMap<String, Contact?>() {

        override fun get(key: String): Contact? {
            if (super.get(key)?.isValid != true) {
                set(key, contacts.firstOrNull { contact ->
                    contact.numbers.any { phoneNumberUtils.compare(it.address, key) }
                })
            }
            return super.get(key)?.takeIf { it.isValid }
        }
    }
}
