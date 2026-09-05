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

    fun categorize(address: String, body: String): Category = categorize(
        body,
        isTrusted = { trustedSenderRepo.isTrusted(address) },
        rule = { ruleOf(address) },
        isContact = { contactsRepo.isContact(address) }
    )

    /**
     * Returns a categorize function with the per-address lookups memoized, for classifying a large
     * batch at once. Only the body-dependent keyword classification then runs per message; the
     * sender lookups - one of which is a cross-process query to the contacts provider - run once
     * per distinct address instead of once per message. That matters because a whole thread's
     * worth of messages share one address, so a backfill over a full history would otherwise
     * repeat the same provider query thousands of times.
     *
     * The memo lives only as long as the returned function, so it cannot go stale against contact
     * or rule changes the way caching on this shared instance would.
     */
    fun bulkCategorizer(): (address: String, body: String) -> Category {
        val trusted = HashMap<String, Boolean>()
        val rules = HashMap<String, Category?>()
        val contacts = HashMap<String, Boolean>()

        return { address, body ->
            categorize(
                body,
                isTrusted = { trusted.getOrPut(address) { trustedSenderRepo.isTrusted(address) } },
                rule = {
                    if (rules.containsKey(address)) rules[address]
                    else ruleOf(address).also { rules[address] = it }
                },
                isContact = { contacts.getOrPut(address) { contactsRepo.isContact(address) } }
            )
        }
    }

    // Lookups stay behind lambdas so the short-circuits hold: a trusted sender never triggers a
    // rule or contacts lookup, and a sender with a rule never triggers a contacts lookup.
    private inline fun categorize(
        body: String,
        isTrusted: () -> Boolean,
        rule: () -> Category?,
        isContact: () -> Boolean
    ): Category {
        if (isTrusted()) {
            return Category.PERSONAL
        }

        rule()?.let { return it }

        val category = classifier.classify(body)
        return if (isContact() && category != Category.TRANSACTIONAL) {
            Category.PERSONAL
        } else {
            category
        }
    }

    private fun ruleOf(address: String): Category? =
        senderRuleRepo.getRule(address)?.let { rule ->
            try {
                Category.valueOf(rule.category)
            } catch (e: IllegalArgumentException) {
                Category.UNCLASSIFIED
            }
        }

}
