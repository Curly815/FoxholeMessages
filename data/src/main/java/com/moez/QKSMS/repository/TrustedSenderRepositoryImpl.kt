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

import dev.octoshrimpy.quik.model.TrustedSender
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.realm.Realm
import io.realm.RealmResults
import javax.inject.Inject

class TrustedSenderRepositoryImpl @Inject constructor(
    private val phoneNumberUtils: PhoneNumberUtils
) : TrustedSenderRepository {

    override fun getTrustedSenders(): RealmResults<TrustedSender> =
        Realm.getDefaultInstance().where(TrustedSender::class.java).findAllAsync()

    override fun isTrusted(address: String): Boolean =
        Realm.getDefaultInstance().use { realm ->
            realm.where(TrustedSender::class.java).findAll()
                .any { phoneNumberUtils.compare(it.address, address) }
        }

    override fun addTrustedSender(address: String, locked: Boolean) {
        Realm.getDefaultInstance().use { realm ->
            realm.refresh()

            val alreadyTrusted = realm.where(TrustedSender::class.java).findAll()
                .any { phoneNumberUtils.compare(it.address, address) }
            if (alreadyTrusted) return@use

            val nextId = (realm.where(TrustedSender::class.java).max("id")?.toLong() ?: -1) + 1
            realm.executeTransaction {
                realm.insert(TrustedSender(nextId, address, locked))
            }
        }
    }

    override fun removeTrustedSender(id: Long, force: Boolean) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                realm.where(TrustedSender::class.java).equalTo("id", id).findAll()
                    .filter { sender -> force || !sender.locked }
                    .forEach { sender -> sender.deleteFromRealm() }
            }
        }
    }

}
