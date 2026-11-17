/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
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
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.MessageContentFilterData
import io.realm.Realm
import io.realm.RealmResults
import timber.log.Timber
import javax.inject.Inject

class MessageContentFilterRepositoryImpl @Inject constructor() : MessageContentFilterRepository {
    override fun exportFilters(): List<MessageContentFilterData> = Realm.getDefaultInstance().use { realm ->
        // See BlockingRepositoryImpl.exportBlockedAddresses: the instance may be pinned to an old version.
        realm.refresh()
        realm.where(MessageContentFilter::class.java).findAll().map {
            MessageContentFilterData(it.value, it.caseSensitive, it.isRegex, it.includeContacts)
        }
    }

    override fun createFilter(data: MessageContentFilterData) {
        Realm.getDefaultInstance().use { realm ->
            // Next id read inside the write transaction so a concurrent insert can't take it first.
            realm.executeTransaction {
                val maxId = realm.where(MessageContentFilter::class.java)
                    .max("id")?.toLong() ?: -1
                realm.insert(MessageContentFilter(maxId + 1, data.value, data.caseSensitive, data.isRegex, data.includeContacts))
            }
        }
    }

    override fun getMessageContentFilters(): RealmResults<MessageContentFilter> {
        return Realm.getDefaultInstance()
            .where(MessageContentFilter::class.java)
            .findAllAsync()
    }

    override fun getMessageContentFilter(id: Long): MessageContentFilter? {
        return Realm.getDefaultInstance()
            .where(MessageContentFilter::class.java)
            .equalTo("id", id)
            .findFirst()
    }

    override fun isBlocked(messageBody: String, address: String, contactsRepo: ContactRepository): Boolean {
        val isContact = contactsRepo.isContact(address)

        return Realm.getDefaultInstance().use { realm ->
            realm.where(MessageContentFilter::class.java)
                .findAll()
                .any { filter ->
                    if (isContact && !filter.includeContacts) {
                        false
                    } else if (filter.isRegex) {
                        // An invalid saved pattern must not throw here: this runs on the receive
                        // path, and an exception stops the incoming message from being processed.
                        try {
                            Regex(filter.value).matches(messageBody)
                        } catch (e: IllegalArgumentException) {
                            Timber.w(e, "Skipping invalid regex content filter id=${filter.id}")
                            false
                        }
                    } else if (filter.caseSensitive) {
                        val regexp = "[\\s\\S]*\\b" + Regex.escape(filter.value) + "\\b[\\s\\S]*"
                        Regex(regexp).matches(messageBody)
                    } else {
                        val regexp = "[\\s\\S]*\\b" + Regex.escape(filter.value.lowercase()) + "\\b[\\s\\S]*"
                        Regex(regexp).matches(messageBody.lowercase())
                    }
                }
        }
    }

    override fun removeFilter(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(MessageContentFilter::class.java)
                    .equalTo("id", id)
                    .findAll()
                    .deleteAllFromRealm()
            }
        }
    }

}
