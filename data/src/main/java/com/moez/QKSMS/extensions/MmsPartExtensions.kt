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
package dev.octoshrimpy.quik.extensions

import com.google.android.mms.ContentType
import dev.octoshrimpy.quik.model.MmsPart

fun MmsPart.isSmil() = ContentType.APP_SMIL.lowercase() == type.lowercase()

// Deliberately not calling ContentType.isImageType/isVideoType/isAudioType(): on some
// devices the OS ships its own internal com.google.android.mms.ContentType class in
// framework.jar with the exact same fully-qualified name as this app's vendored one, and
// due to parent-first classloader delegation the app's calls resolve to the framework's
// (different, incompatible) version instead of the app's own, throwing NoSuchMethodError.
// The String constants (APP_SMIL/TEXT_PLAIN/TEXT_VCARD below) are safe since Kotlin inlines
// compile-time-constant Java static final fields at compile time, never touching the class
// at runtime — only the method calls are affected, so only those are reimplemented inline.
fun MmsPart.isImage() = type.lowercase().startsWith("image/")

fun MmsPart.isVideo() = type.lowercase().startsWith("video/")

fun MmsPart.isAudio() = type.lowercase().startsWith("audio/")

fun MmsPart.isText() = ContentType.TEXT_PLAIN.lowercase() == type.lowercase()

fun MmsPart.isVCard() = ContentType.TEXT_VCARD.lowercase() == type.lowercase()
