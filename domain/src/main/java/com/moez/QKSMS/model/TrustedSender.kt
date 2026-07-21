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
package dev.octoshrimpy.quik.model

import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

/**
 * A sender that always gets classified as [dev.octoshrimpy.quik.classifier.Category.PERSONAL],
 * regardless of message content. [locked] entries (e.g. the user's own number) require a forced
 * removal to delete.
 */
open class TrustedSender(
    @PrimaryKey var id: Long = 0,
    var address: String = "",
    var locked: Boolean = false
) : RealmObject()
