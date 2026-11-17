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

import dev.octoshrimpy.quik.extensions.anyOf
import dev.octoshrimpy.quik.model.BlockedNumber
import dev.octoshrimpy.quik.model.MessageContentFilter
import dev.octoshrimpy.quik.model.MessageContentFilterData
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.realm.Realm
import io.realm.RealmResults
import javax.inject.Inject

class BlockingRepositoryImpl @Inject constructor(
    private val phoneNumberUtils: PhoneNumberUtils
) : BlockingRepository {

    override fun blockNumber(vararg addresses: String) {
        Realm.getDefaultInstance().use { realm ->
            // Read the existing numbers and the next id inside the write transaction: it holds the
            // write lock and sees the latest data, so a concurrent insert (e.g. the notification
            // Block action) can't take the same primary key between the read and the insert.
            realm.executeTransaction {
                val blockedNumbers = realm.where(BlockedNumber::class.java).findAll()
                val newAddresses = addresses.filter { address ->
                    blockedNumbers.none { number -> phoneNumberUtils.compare(number.address, address) }
                }

                val maxId = realm.where(BlockedNumber::class.java)
                        .max("id")?.toLong() ?: -1

                realm.insert(newAddresses.mapIndexed { index, address ->
                    BlockedNumber(maxId + 1 + index, address)
                })
            }
        }
    }

    override fun getBlockedNumbers(): RealmResults<BlockedNumber> {
        return Realm.getDefaultInstance()
                .where(BlockedNumber::class.java)
                .findAllAsync()
    }

    override fun exportBlockedAddresses(): List<String> = Realm.getDefaultInstance().use { realm ->
        // A background thread can be handed a cached instance pinned to an old version (some getters
        // never close theirs), so refresh to include rules added moments ago on another thread.
        realm.refresh()
        realm.where(BlockedNumber::class.java).findAll().map { it.address }
    }

    override fun importBlockingRules(
        addresses: List<String>,
        filters: List<MessageContentFilterData>
    ): Pair<Int, Int> = Realm.getDefaultInstance().use { realm ->
        var result = Pair(0, 0)
        // One transaction for numbers and filters, so a failure part-way leaves nothing behind, and
        // existing rows and next ids are read under the write lock (see blockNumber).
        realm.executeTransaction {
            val existingNumbers = realm.where(BlockedNumber::class.java).findAll().map { it.address }
            // Fuzzy-compare against the saved list (usually short); within the file, dedupe on the
            // normalized form only, so a large file doesn't cost a full pairwise phone-number match.
            val seenInFile = HashSet<String>()
            val newAddresses = addresses.filter { address ->
                seenInFile.add(phoneNumberUtils.normalizeNumber(address).lowercase()) &&
                        existingNumbers.none { phoneNumberUtils.compare(it, address) }
            }
            val maxNumberId = realm.where(BlockedNumber::class.java).max("id")?.toLong() ?: -1
            realm.insert(newAddresses.mapIndexed { index, address -> BlockedNumber(maxNumberId + 1 + index, address) })

            val existingFilters = realm.where(MessageContentFilter::class.java).findAll()
                .map { MessageContentFilterData(it.value, it.caseSensitive, it.isRegex, it.includeContacts) }
                .toSet()
            val newFilters = filters.distinct().filter { it !in existingFilters }
            val maxFilterId = realm.where(MessageContentFilter::class.java).max("id")?.toLong() ?: -1
            realm.insert(newFilters.mapIndexed { index, filter ->
                MessageContentFilter(maxFilterId + 1 + index, filter.value, filter.caseSensitive, filter.isRegex, filter.includeContacts)
            })

            result = Pair(newAddresses.size, newFilters.size)
        }
        result
    }

    override fun getBlockedNumber(id: Long): BlockedNumber? {
        return Realm.getDefaultInstance()
                .where(BlockedNumber::class.java)
                .equalTo("id", id)
                .findFirst()
    }

    override fun isBlocked(address: String): Boolean {
        return Realm.getDefaultInstance().use { realm ->
            realm.where(BlockedNumber::class.java)
                    .findAll()
                    .any { number -> phoneNumberUtils.compare(number.address, address) }
        }
    }

    override fun unblockNumber(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(BlockedNumber::class.java)
                        .equalTo("id", id)
                        .findAll()
                        .deleteAllFromRealm()
            }
        }
    }

    override fun unblockNumbers(vararg addresses: String) {
        Realm.getDefaultInstance().use { realm ->
            val ids = realm.where(BlockedNumber::class.java)
                    .findAll()
                    .filter { number ->
                        addresses.any { address -> phoneNumberUtils.compare(number.address, address) }
                    }
                    .map { number -> number.id }
                    .toLongArray()

            realm.executeTransaction {
                realm.where(BlockedNumber::class.java)
                        .anyOf("id", ids)
                        .findAll()
                        .deleteAllFromRealm()
            }
        }
    }

}
