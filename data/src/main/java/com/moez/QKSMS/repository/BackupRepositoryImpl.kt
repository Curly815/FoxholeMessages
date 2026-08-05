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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Telephony
import android.util.Base64
import androidx.core.content.contentValuesOf
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.android.mms.dom.smil.parser.SmilXmlSerializer
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import com.google.android.mms.pdu_alt.SendReq
import com.google.android.mms.smil.SmilHelper
import com.squareup.moshi.Moshi
import dev.octoshrimpy.quik.common.util.extensions.now
import dev.octoshrimpy.quik.model.BackupFile
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MmsPart
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.util.tryOrNull
import io.reactivex.Observable
import io.reactivex.subjects.BehaviorSubject
import io.reactivex.subjects.Subject
import io.realm.Realm
import okio.buffer
import okio.source
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Timer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.schedule

@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val context: Context,
    private val moshi: Moshi,
    private val prefs: Preferences,
    private val syncRepo: SyncRepository
) : BackupRepository {

    data class Backup(
        val messageCount: Int = 0,
        val messages: List<BackupMessage> = listOf()
    )

    /**
     * Simpler version of [Backup] which allows us to read certain fields from the backup without
     * needing to parse the entire file
     */
    data class BackupMetadata(
        val messageCount: Int = 0
    )

    data class BackupMessage(
        val type: Int,
        val address: String,
        val date: Long,
        val dateSent: Long,
        val read: Boolean,
        val status: Int,
        val body: String,
        val protocol: Int,
        val serviceCenter: String?,
        val locked: Boolean,
        val subId: Int,
        // MMS only - absent/empty for SMS, in which case the fields above are used as before.
        val isMms: Boolean = false,
        val mmsSubject: String? = null,
        val mmsRecipients: List<String> = listOf(),
        val mmsParts: List<BackupPart> = listOf()
    )

    /**
     * One MMS attachment/text part. Binary parts (images, video, audio) carry their bytes
     * base64-encoded in [data]; text/SMIL parts carry their content in [text] instead, so the
     * (usually much larger) [data] field is left null for them.
     */
    data class BackupPart(
        val contentType: String,
        val name: String?,
        val text: String?,
        val data: String?
    )

    // Subjects to emit our progress events to
    private val backupProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())
    private val restoreProgress: Subject<BackupRepository.Progress> =
            BehaviorSubject.createDefault(BackupRepository.Progress.Idle())

    @Volatile private var stopFlag: Boolean = false

    override fun getDefaultBackupPath(): String {
        return "${Environment.getExternalStorageDirectory()}/FoxholeMessages/Backups"
    }

    override fun getBackupDocumentTree(): DocumentFile? {
        return prefs.backupDirectory.get()
                .takeIf { uri -> uri != Uri.EMPTY }
                ?.let { uri -> DocumentFile.fromTreeUri(context, uri) }
                ?.takeIf { dir -> dir.exists() && dir.canRead() && dir.canWrite() }
    }

    override fun getBackupPathUriForPicker(): Uri {
        return prefs.backupDirectory.get().takeIf { uri -> uri != Uri.EMPTY }
                ?: getDefaultBackupPath().toUri()
    }

    override fun persistBackupDirectory(directory: Uri) {
        context.contentResolver.takePersistableUriPermission(directory,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.backupDirectory.set(directory)

        Timber.v("Updated backup directory: $directory")
    }

    override fun performBackup() {
        // If a backup or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        var messageCount: Int

        // Map all the messages into our object we'll use for the Json mapping
        val backupMessages = Realm.getDefaultInstance().use { realm ->
            // Get the messages from realm
            val messages = realm.where(Message::class.java).sort("date").findAll().createSnapshot()
            messageCount = messages.size

            // MMS doesn't track its own recipient list per-message (Message.address is just the
            // sender), so fall back to the parent conversation's current recipients - same as how
            // the rest of the app already treats "the conversation" as the group of people.
            val conversationRecipients = realm.where(Conversation::class.java).findAll()
                    .associate { conversation ->
                        conversation.id to conversation.recipients.map { recipient -> recipient.address }
                    }

            // Map the messages to the new format
            messages.mapIndexed { index, message ->
                // Update the progress
                backupProgress.onNext(BackupRepository.Progress.Running(messageCount, index))
                messageToBackupMessage(message, conversationRecipients[message.threadId] ?: listOf())
            }
        }

        // Update the status, and set the progress to be indeterminate since we can no longer calculate progress
        backupProgress.onNext(BackupRepository.Progress.Saving())

        // Convert the data to json
        val adapter = moshi.adapter(Backup::class.java).indent("\t")
        val json = adapter.toJson(Backup(messageCount, backupMessages)).toByteArray()

        try {
            val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(now())
            val inputStream = getBackupDocumentTree()
                    ?.createFile("application/json", "backup-$timestamp.json")
                    ?.let { file -> context.contentResolver.openOutputStream(file.uri) }
                    ?: throw Exception("Failed to open output stream")

            inputStream.use { stream ->
                stream.write(json)
            }
        } catch (e: Exception) {
            Timber.w(e)
        }

        // Mark the task finished, and set it as Idle a second later
        backupProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { backupProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    private fun messageToBackupMessage(message: Message, recipients: List<String>): BackupMessage = BackupMessage(
            type = message.boxId,
            address = message.address,
            date = message.date,
            dateSent = message.dateSent,
            read = message.read,
            status = message.deliveryStatus,
            body = message.body,
            protocol = 0,
            serviceCenter = null,
            locked = message.locked,
            subId = message.subId,
            isMms = message.isMms(),
            mmsSubject = message.subject.takeIf { it.isNotBlank() },
            mmsRecipients = recipients,
            mmsParts = message.parts
                    // The SMIL part is just a layout description for the parts below it - it's
                    // regenerated fresh from those parts on restore, same as when composing a
                    // new MMS, so there's no need to carry it through the backup file.
                    .filter { part -> part.type != ContentType.APP_SMIL }
                    .map(::mmsPartToBackupPart)
    )

    private fun mmsPartToBackupPart(part: MmsPart): BackupPart {
        val isBinary = part.type.startsWith("image")
                || part.type.startsWith("video")
                || part.type.startsWith("audio")

        val data = if (isBinary) {
            tryOrNull {
                context.contentResolver.openInputStream(part.getUri())?.use { stream ->
                    Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
                }
            }
        } else null

        return BackupPart(
                contentType = part.type,
                name = part.name,
                text = part.text,
                data = data
        )
    }

    override fun getBackupProgress(): Observable<BackupRepository.Progress> = backupProgress

    @SuppressLint("Recycle") // InputStream is closed by Okio BufferedSource
    override fun parseBackup(uri: Uri): BackupFile {
        val adapter = moshi.adapter(BackupMetadata::class.java)

        val file = DocumentFile.fromSingleUri(context, uri)
                ?: throw IllegalArgumentException("Couldn't open backup file")

        val metadata = context.contentResolver.openInputStream(file.uri)
                ?.source()
                ?.buffer()
                ?.use(adapter::fromJson)
                ?: throw IllegalArgumentException("Couldn't parse backup file")

        return BackupFile(file.lastModified(), metadata.messageCount)
    }

    override fun performRestore(uri: Uri) {
        // If a backupFile or restore is already running, don't do anything
        if (isBackupOrRestoreRunning()) return

        restoreProgress.onNext(BackupRepository.Progress.Parsing())

        val adapter = moshi.adapter(Backup::class.java)
        val backup = DocumentFile.fromSingleUri(context, uri)
                ?.let { file -> context.contentResolver.openInputStream(file.uri) }
                ?.source()
                ?.buffer()
                ?.use(adapter::fromJson)

        val messageCount = backup?.messages?.size ?: 0
        var errorCount = 0

        backup?.messages?.forEachIndexed { index, message ->
            if (stopFlag) {
                stopFlag = false
                restoreProgress.onNext(BackupRepository.Progress.Idle())
                return
            }

            // Update the progress
            restoreProgress.onNext(BackupRepository.Progress.Running(messageCount, index))

            try {
                if (message.isMms) {
                    restoreMmsMessage(message)
                } else {
                    val values = contentValuesOf(
                            Telephony.Sms.TYPE to message.type,
                            Telephony.Sms.ADDRESS to message.address,
                            Telephony.Sms.DATE to message.date,
                            Telephony.Sms.DATE_SENT to message.dateSent,
                            Telephony.Sms.READ to message.read,
                            Telephony.Sms.SEEN to 1,
                            Telephony.Sms.STATUS to message.status,
                            Telephony.Sms.BODY to message.body,
                            Telephony.Sms.PROTOCOL to message.protocol,
                            Telephony.Sms.SERVICE_CENTER to message.serviceCenter,
                            Telephony.Sms.LOCKED to message.locked
                    )

                    if (prefs.canUseSubId.get()) {
                        values.put(Telephony.Sms.SUBSCRIPTION_ID, message.subId)
                    }

                    context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                }
            } catch (e: Exception) {
                Timber.w(e)
                errorCount++
            }
        }

        if (errorCount > 0) {
            Timber.w(Exception("Failed to backup $errorCount/$messageCount messages"))
        }

        // Sync the messages
        restoreProgress.onNext(BackupRepository.Progress.Syncing())
        syncRepo.syncMessages()

        // Mark the task finished, and set it as Idle a second later
        restoreProgress.onNext(BackupRepository.Progress.Finished())
        Timer().schedule(1000) { restoreProgress.onNext(BackupRepository.Progress.Idle()) }
    }

    /**
     * Reconstructs and persists an MMS message via [PduPersister] - the same mechanism this app's
     * real incoming-MMS pipeline already relies on (see PushReceiver.java/DownloadManager.java) -
     * rather than hand-writing rows into the MMS content provider's tables directly.
     */
    private fun restoreMmsMessage(message: BackupMessage) {
        val subId = if (prefs.canUseSubId.get()) message.subId else -1
        val persister = PduPersister.getPduPersister(context)
        val body = buildPduBody(message.mmsParts)

        if (message.type == Telephony.Mms.MESSAGE_BOX_INBOX) {
            val pdu = RetrieveConf()
            pdu.setContentType(ContentType.MMS_MESSAGE.toByteArray())
            pdu.setFrom(EncodedStringValue(message.address))
            message.mmsRecipients.forEach { address -> pdu.addTo(EncodedStringValue(address)) }
            message.mmsSubject?.let { subject -> pdu.setSubject(EncodedStringValue(subject)) }
            pdu.setDate(message.date / 1000)
            pdu.setBody(body)

            persister.persist(pdu, Uri.parse("content://mms/inbox"), true, true, null, subId)
        } else {
            val pdu = SendReq()
            message.mmsRecipients.forEach { address -> pdu.addTo(EncodedStringValue(address)) }
            message.mmsSubject?.let { subject -> pdu.setSubject(EncodedStringValue(subject)) }
            pdu.setDate(message.date / 1000)
            pdu.setBody(body)

            val box = when (message.type) {
                Telephony.Mms.MESSAGE_BOX_DRAFTS -> "content://mms/drafts"
                Telephony.Mms.MESSAGE_BOX_OUTBOX -> "content://mms/outbox"
                else -> "content://mms/sent"
            }
            persister.persist(pdu, Uri.parse(box), true, true, null, subId)
        }
    }

    /**
     * Builds a fresh [PduBody] from backed-up parts, regenerating the SMIL layout part the same
     * way a new outgoing MMS is composed (see Transaction.java's buildPdu) rather than trying to
     * preserve the original SMIL, which isn't carried through the backup file.
     */
    private fun buildPduBody(parts: List<BackupPart>): PduBody {
        val body = PduBody()

        parts.forEachIndexed { index, part ->
            val pduPart = PduPart()
            pduPart.setContentType(part.contentType.toByteArray())

            val filename = part.name ?: "part$index"
            pduPart.setName(filename.toByteArray())
            pduPart.setContentLocation(filename.toByteArray())
            if (part.contentType.startsWith("text")) {
                pduPart.setCharset(CharacterSets.UTF_8)
            }

            val contentId = filename.substringBeforeLast(".", filename)
            pduPart.setContentId(contentId.toByteArray())

            val data = part.data
                    ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) }
                    ?: (part.text ?: "").toByteArray()
            pduPart.setData(data)

            body.addPart(pduPart)
        }

        val out = ByteArrayOutputStream()
        SmilXmlSerializer.serialize(SmilHelper.createSmilDocument(body), out)
        val smilPart = PduPart()
        smilPart.setContentId("smil".toByteArray())
        smilPart.setContentLocation("smil.xml".toByteArray())
        smilPart.setContentType(ContentType.APP_SMIL.toByteArray())
        smilPart.setData(out.toByteArray())
        body.addPart(0, smilPart)

        return body
    }

    override fun getRestoreProgress(): Observable<BackupRepository.Progress> = restoreProgress

    override fun stopRestore() {
        stopFlag = true
    }

    private fun isBackupOrRestoreRunning(): Boolean {
        return backupProgress.blockingFirst().running || restoreProgress.blockingFirst().running
    }

}