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
package dev.octoshrimpy.quik.classifier

import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.SenderCategoryRuleRepository
import dev.octoshrimpy.quik.repository.TrustedSenderRepository
import javax.inject.Inject

/**
 * Decides which [Category] a message belongs in, in priority order:
 * 1. Trusted senders always go to [Category.PERSONAL].
 * 2. A manual [dev.octoshrimpy.quik.model.SenderCategoryRule] for this sender wins next.
 * 3. Otherwise fall back to keyword classification, with a contact bump: known contacts default
 *    to [Category.PERSONAL] unless the message content is clearly transactional.
 */
class MessageCategorizer @Inject constructor(
    private val trustedSenderRepo: TrustedSenderRepository,
    private val senderRuleRepo: SenderCategoryRuleRepository,
    private val contactsRepo: ContactRepository,
    private val classifier: MessageClassifier
) {

    fun categorize(address: String, body: String): Category {
        if (trustedSenderRepo.isTrusted(address)) {
            return Category.PERSONAL
        }

        val rule = senderRuleRepo.getRule(address)
        if (rule != null) {
            return try {
                Category.valueOf(rule.category)
            } catch (e: IllegalArgumentException) {
                Category.UNCLASSIFIED
            }
        }

        val category = classifier.classify(body)
        return if (contactsRepo.isContact(address) && category != Category.TRANSACTIONAL) {
            Category.PERSONAL
        } else {
            category
        }
    }

}
