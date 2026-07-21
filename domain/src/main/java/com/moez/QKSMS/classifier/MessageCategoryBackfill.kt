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

import dev.octoshrimpy.quik.repository.MessageRepository
import javax.inject.Inject

/**
 * Classifies every message that doesn't have a category yet (in batches of 500), for when the
 * user turns on auto-sort after already having messages, or re-runs it manually.
 */
class MessageCategoryBackfill @Inject constructor(
    private val messageRepo: MessageRepository,
    private val messageCategorizer: MessageCategorizer
) {

    fun run(onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val messageIds = messageRepo.getUnclassifiedMessages().map { it.id }
        val total = messageIds.size
        if (total == 0) return 0

        var processed = 0
        messageIds.chunked(500).forEach { chunk ->
            val categories = chunk.mapNotNull { messageId ->
                messageRepo.getMessage(messageId)?.let { message ->
                    messageId to messageCategorizer.categorize(message.address, message.getText()).name
                }
            }.toMap()

            if (categories.isNotEmpty()) {
                messageRepo.updateMessageCategories(categories)
            }

            processed += chunk.size
            onProgress(processed, total)
        }

        return total
    }

}
