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

import javax.inject.Inject

/**
 * Keyword/regex based classifier: counts matches against transactional and promotional patterns
 * and picks whichever scores higher (ties favor transactional). No matches from either set means
 * [Category.UNCLASSIFIED].
 */
class MessageClassifier @Inject constructor() {

    private val transactionalPatterns = listOf(
        Regex("\\$\\s?\\d+(\\.\\d{2})?"),
        Regex("\\botp\\b", RegexOption.IGNORE_CASE),
        Regex("\\bone[- ]time (code|password|passcode)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bverification code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsecurity code\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(account|acct|card)\\s+(ending|number)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bavailable balance\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwas (debited|credited|charged)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpayment (of|received|due)\\b", RegexOption.IGNORE_CASE),
        Regex("\\byou paid\\b", RegexOption.IGNORE_CASE),
        Regex("\\btransaction (of|alert)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpurchase of\\b", RegexOption.IGNORE_CASE),
        Regex("\\bstatement is ready\\b", RegexOption.IGNORE_CASE),
        Regex("\\bautopay\\b", RegexOption.IGNORE_CASE),
        Regex("\\bminimum payment\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdue date\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdeposit of\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfraud alert\\b", RegexOption.IGNORE_CASE)
    )

    private val promotionalPatterns = listOf(
        Regex("\\d+%\\s?off"),
        Regex("\\bsale\\b", RegexOption.IGNORE_CASE),
        Regex("\\boffer ends\\b", RegexOption.IGNORE_CASE),
        Regex("\\blimited time\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdiscount\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcoupon\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpromo\\s?code\\b", RegexOption.IGNORE_CASE),
        Regex("\\bclearance\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(buy|shop) now\\b", RegexOption.IGNORE_CASE),
        Regex("\\bfree shipping\\b", RegexOption.IGNORE_CASE),
        Regex("\\breply stop\\b", RegexOption.IGNORE_CASE),
        Regex("\\btxt stop\\b", RegexOption.IGNORE_CASE),
        Regex("\\bunsubscribe\\b", RegexOption.IGNORE_CASE),
        Regex("\\bexclusive deal\\b", RegexOption.IGNORE_CASE),
        Regex("\\bflash sale\\b", RegexOption.IGNORE_CASE)
    )

    fun classify(body: String): Category {
        val transactionalScore = transactionalPatterns.count { it.containsMatchIn(body) }
        val promotionalScore = promotionalPatterns.count { it.containsMatchIn(body) }

        return when {
            transactionalScore == 0 && promotionalScore == 0 -> Category.UNCLASSIFIED
            transactionalScore < promotionalScore -> Category.PROMOTIONAL
            else -> Category.TRANSACTIONAL
        }
    }

}
