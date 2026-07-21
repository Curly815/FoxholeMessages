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
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.model.SenderCategoryRule
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.realm.Realm
import io.realm.RealmResults
import javax.inject.Inject

class SenderCategoryRuleRepositoryImpl @Inject constructor(
    private val phoneNumberUtils: PhoneNumberUtils
) : SenderCategoryRuleRepository {

    override fun getRules(): RealmResults<SenderCategoryRule> =
        Realm.getDefaultInstance().where(SenderCategoryRule::class.java).findAllAsync()

    override fun getRule(address: String): SenderCategoryRule? =
        Realm.getDefaultInstance().use { realm ->
            realm.where(SenderCategoryRule::class.java).findAll()
                .firstOrNull { phoneNumberUtils.compare(it.address, address) }
                ?.let { realm.copyFromRealm(it) }
        }

    override fun setRule(address: String, category: String) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val existing = realm.where(SenderCategoryRule::class.java).findAll()
                .filter { phoneNumberUtils.compare(it.address, address) }

            val nextId = (realm.where(SenderCategoryRule::class.java).max("id")?.toLong() ?: -1) + 1
            realm.executeTransaction {
                existing.forEach { rule -> rule.deleteFromRealm() }
                realm.insert(SenderCategoryRule(nextId, address, category))
            }
        }
    }

    override fun removeRule(id: Long) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(SenderCategoryRule::class.java).equalTo("id", id).findAll()
                    .deleteAllFromRealm()
            }
        }
    }

}
