package com.ivanovsky.passnotes.domain.interactor.groups

import com.ivanovsky.passnotes.data.ObserverBus
import com.ivanovsky.passnotes.data.crypto.biometric.BiometricEncoder
import com.ivanovsky.passnotes.data.entity.EncryptedDatabaseEntry
import com.ivanovsky.passnotes.data.entity.Group
import com.ivanovsky.passnotes.data.entity.OperationResult
import com.ivanovsky.passnotes.data.entity.Template
import com.ivanovsky.passnotes.data.entity.UsedFile
import com.ivanovsky.passnotes.data.repository.encdb.EncryptedDatabaseKey
import com.ivanovsky.passnotes.domain.DispatcherProvider
import com.ivanovsky.passnotes.domain.entity.SelectionItem
import com.ivanovsky.passnotes.domain.entity.SelectionItemType
import com.ivanovsky.passnotes.domain.interactor.SelectionHolder
import com.ivanovsky.passnotes.domain.usecases.AddTemplatesUseCase
import com.ivanovsky.passnotes.domain.usecases.EncodePasswordWithBiometricUseCase
import com.ivanovsky.passnotes.domain.usecases.GetDatabaseUseCase
import com.ivanovsky.passnotes.domain.usecases.GetUsedFileUseCase
import com.ivanovsky.passnotes.domain.usecases.LockDatabaseUseCase
import com.ivanovsky.passnotes.domain.usecases.MoveGroupUseCase
import com.ivanovsky.passnotes.domain.usecases.MoveNoteUseCase
import com.ivanovsky.passnotes.domain.usecases.RemoveBiometricDataUseCase
import com.ivanovsky.passnotes.domain.usecases.SortGroupsAndNotesUseCase
import com.ivanovsky.passnotes.domain.usecases.UpdateUsedFileUseCase
import com.ivanovsky.passnotes.extensions.getOrThrow
import com.ivanovsky.passnotes.extensions.mapError
import com.ivanovsky.passnotes.extensions.mapWithObject
import java.util.UUID
import kotlinx.coroutines.withContext

class GroupsInteractor(
    private val observerBus: ObserverBus,
    private val dispatchers: DispatcherProvider,
    private val lockUseCase: LockDatabaseUseCase,
    private val addTemplatesUseCase: AddTemplatesUseCase,
    private val moveNoteUseCase: MoveNoteUseCase,
    private val moveGroupUseCae: MoveGroupUseCase,
    private val sortUseCase: SortGroupsAndNotesUseCase,
    private val getDbUseCase: GetDatabaseUseCase,
    private val getUsedFileUseCase: GetUsedFileUseCase,
    private val updateUsedFileUseCase: UpdateUsedFileUseCase,
    private val removeBiometricDataUseCase: RemoveBiometricDataUseCase,
    private val encodePasswordUseCase: EncodePasswordWithBiometricUseCase
) {

    suspend fun getTemplates(): OperationResult<List<Template>> =
        withContext(dispatchers.IO) {
            val getDbResult = getDbUseCase.getDatabase()
            if (getDbResult.isFailed) {
                return@withContext getDbResult.takeError()
            }

            val db = getDbResult.obj
            db.templateDao.getTemplates()
        }

    suspend fun getRootUid(): UUID? =
        withContext(dispatchers.IO) {
            val getDbResult = getDbUseCase.getDatabaseSynchronously()
            if (getDbResult.isFailed) {
                return@withContext null
            }

            val db = getDbResult.obj
            val rootResult = db.groupDao.rootGroup
            if (rootResult.isFailed) {
                return@withContext null
            }

            rootResult.getOrThrow().uid
        }

    suspend fun getRootGroupData(): OperationResult<List<EncryptedDatabaseEntry>> =
        withContext(dispatchers.IO) {
            val getDbResult = getDbUseCase.getDatabaseSynchronously()
            if (getDbResult.isFailed) {
                return@withContext getDbResult.takeError()
            }

            val db = getDbResult.obj

            val rootGroupResult = db.groupDao.rootGroup
            if (rootGroupResult.isFailed) {
                return@withContext rootGroupResult.takeError()
            }

            val groupUid = rootGroupResult.obj.uid

            getGroupData(groupUid)
        }

    suspend fun getGroupData(groupUid: UUID): OperationResult<List<EncryptedDatabaseEntry>> =
        withContext(dispatchers.IO) {
            val getDbResult = getDbUseCase.getDatabaseSynchronously()
            if (getDbResult.isFailed) {
                return@withContext getDbResult.takeError()
            }

            val db = getDbResult.obj
            val groupsResult = db.groupDao.getChildGroups(groupUid)
            if (groupsResult.isFailed) {
                return@withContext groupsResult.takeError()
            }

            val notesResult = db.noteDao.getNotesByGroupUid(groupUid)
            if (notesResult.isFailed) {
                return@withContext groupsResult.takeError()
            }

            val groups = groupsResult.obj
            val notes = notesResult.obj

            OperationResult.success(groups + notes)
        }

    suspend fun sortData(
        data: List<EncryptedDatabaseEntry>
    ): List<EncryptedDatabaseEntry> =
        sortUseCase.sortGroupsAndNotesAccordingToSettings(data)

    fun removeGroup(groupUid: UUID): OperationResult<Unit> {
        val getDbResult = getDbUseCase.getDatabaseSynchronously()
        if (getDbResult.isFailed) {
            return getDbResult.takeError()
        }

        val db = getDbResult.obj
        val removeResult = db.groupDao.remove(groupUid)

        observerBus.notifyGroupDataSetChanged()

        return removeResult.takeStatusWith(Unit)
    }

    fun removeNote(groupUid: UUID, noteUid: UUID): OperationResult<Unit> {
        val getDbResult = getDbUseCase.getDatabaseSynchronously()
        if (getDbResult.isFailed) {
            return getDbResult.takeError()
        }

        val db = getDbResult.obj
        val removeResult = db.noteDao.remove(noteUid)

        observerBus.notifyNoteDataSetChanged(groupUid)

        return removeResult.takeStatusWith(Unit)
    }

    suspend fun getGroup(groupUid: UUID): OperationResult<Group> {
        return withContext(dispatchers.IO) {
            val getDbResult = getDbUseCase.getDatabase()
            if (getDbResult.isFailed) {
                return@withContext getDbResult.takeError()
            }

            val db = getDbResult.obj
            db.groupDao.getGroupByUid(groupUid)
        }
    }

    fun lockDatabase() {
        lockUseCase.lockIfNeed()
    }

    suspend fun addTemplates(): OperationResult<Boolean> =
        addTemplatesUseCase.addTemplates()

    suspend fun removeBiometricData(usedFileId: Int): OperationResult<Unit> =
        removeBiometricDataUseCase.removeBiometricData(usedFileId)

    suspend fun encodePasswordAndStoreData(
        encoder: BiometricEncoder,
        password: String,
        usedFileId: Int
    ): OperationResult<Unit> =
        withContext(dispatchers.IO) {
            val encodeResult = encodePasswordUseCase.encodePassword(encoder, password)
            if (encodeResult.isFailed) {
                return@withContext encodeResult.mapError()
            }

            val getUsedFileResult = getUsedFileUseCase.getUsedFile(usedFileId)
            if (getUsedFileResult.isFailed) {
                return@withContext getUsedFileResult.mapError()
            }

            val biometricData = encodeResult.obj
            val usedFile = getUsedFileResult.obj.copy(
                biometricData = biometricData
            )

            val updateResult = updateUsedFileUseCase.updateUsedFile(usedFile)
            if (updateResult.isFailed) {
                return@withContext updateResult.mapError()
            }

            updateResult.mapWithObject(Unit)
        }

    suspend fun getDatabaseUsedFile(): OperationResult<UsedFile> =
        withContext(dispatchers.IO) {
            val getDbResult = getDbUseCase.getDatabaseSynchronously()
            if (getDbResult.isFailed) {
                return@withContext getDbResult.takeError()
            }

            val dbFile = getDbResult.obj.file
            val getUsedFileResult = getUsedFileUseCase.getUsedFile(dbFile.uid, dbFile.fsAuthority)
            if (getUsedFileResult.isFailed) {
                return@withContext getUsedFileResult.takeError()
            }

            OperationResult.success(getUsedFileResult.obj)
        }

    suspend fun getDatabaseKey(): OperationResult<EncryptedDatabaseKey> =
        withContext(dispatchers.IO) {
            val getDbResult = getDbUseCase.getDatabaseSynchronously()
            if (getDbResult.isFailed) {
                return@withContext getDbResult.takeError()
            }

            OperationResult.success(getDbResult.obj.key)
        }

    suspend fun doActionOnSelection(
        selectedGroupUid: UUID,
        action: SelectionHolder.ActionType,
        selection: SelectionItem
    ): OperationResult<Boolean> {
        return when (action) {
            SelectionHolder.ActionType.CUT -> {
                when (selection.type) {
                    SelectionItemType.NOTE_UID -> moveNoteUseCase.moveNote(
                        selection.uid,
                        selectedGroupUid
                    )

                    SelectionItemType.GROUP_UID -> moveGroupUseCae.moveGroup(
                        selection.uid,
                        selectedGroupUid
                    )
                }
            }
        }
    }
}