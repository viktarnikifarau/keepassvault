package com.ivanovsky.passnotes.data.repository.keepass.kotpass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.modifyCredentials
import app.keemobile.kotpass.database.modifiers.modifyMeta
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group as RawGroup
import app.keemobile.kotpass.models.Meta
import com.ivanovsky.passnotes.data.entity.FileDescriptor
import com.ivanovsky.passnotes.data.entity.KeyType
import com.ivanovsky.passnotes.data.entity.Note
import com.ivanovsky.passnotes.data.entity.OperationError
import com.ivanovsky.passnotes.data.entity.OperationError.GENERIC_MESSAGE_FAILED_TO_FIND_ENTITY_BY_UID
import com.ivanovsky.passnotes.data.entity.OperationError.MESSAGE_FAILED_TO_FIND_GROUP
import com.ivanovsky.passnotes.data.entity.OperationError.MESSAGE_INVALID_KEY_FILE
import com.ivanovsky.passnotes.data.entity.OperationError.MESSAGE_INVALID_PASSWORD
import com.ivanovsky.passnotes.data.entity.OperationError.newAuthError
import com.ivanovsky.passnotes.data.entity.OperationError.newDbError
import com.ivanovsky.passnotes.data.entity.OperationError.newGenericIOError
import com.ivanovsky.passnotes.data.entity.OperationResult
import com.ivanovsky.passnotes.data.repository.TemplateDao
import com.ivanovsky.passnotes.data.repository.encdb.DatabaseWatcher
import com.ivanovsky.passnotes.data.repository.encdb.EncryptedDatabase
import com.ivanovsky.passnotes.data.repository.encdb.EncryptedDatabaseConfig
import com.ivanovsky.passnotes.data.repository.encdb.EncryptedDatabaseKey
import com.ivanovsky.passnotes.data.repository.encdb.MutableEncryptedDatabaseConfig
import com.ivanovsky.passnotes.data.repository.encdb.dao.GroupDao
import com.ivanovsky.passnotes.data.repository.encdb.dao.NoteDao
import com.ivanovsky.passnotes.data.repository.file.FSOptions
import com.ivanovsky.passnotes.data.repository.file.FileSystemResolver
import com.ivanovsky.passnotes.data.repository.file.OnConflictStrategy
import com.ivanovsky.passnotes.data.repository.keepass.TemplateDaoImpl
import com.ivanovsky.passnotes.data.repository.keepass.TemplateFactory
import com.ivanovsky.passnotes.data.repository.keepass.kotpass.model.InheritableOptions
import com.ivanovsky.passnotes.domain.entity.exception.Stacktrace
import com.ivanovsky.passnotes.extensions.getOrNull
import com.ivanovsky.passnotes.extensions.mapError
import com.ivanovsky.passnotes.util.InputOutputUtils
import java.io.IOException
import java.io.InputStream
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import timber.log.Timber

class KotpassDatabase(
    private val fsResolver: FileSystemResolver,
    private val fsOptions: FSOptions,
    file: FileDescriptor,
    key: EncryptedDatabaseKey,
    db: KeePassDatabase
) : EncryptedDatabase {

    private val lock = ReentrantLock()
    private val database = AtomicReference(db)
    private val key = AtomicReference(key)
    private val file = AtomicReference(file)
    private val autotypeOptionMap = AtomicReference(createInheritableOptionsMap())
    private val groupUidToParentMap = AtomicReference(createGroupUidToParentMap())
    private val groupDao = KotpassGroupDao(this)
    private val noteDao = KotpassNoteDao(this)
    private val templateDao = TemplateDaoImpl(groupDao, noteDao)
    private val dbWatcher = DatabaseWatcher()

    override fun getWatcher(): DatabaseWatcher = dbWatcher

    override fun getLock(): ReentrantLock = lock

    override fun getFile(): FileDescriptor = file.get()

    override fun getFSOptions(): FSOptions = fsOptions

    override fun getKey(): EncryptedDatabaseKey = key.get()

    override fun getGroupDao(): GroupDao = groupDao

    override fun getNoteDao(): NoteDao = noteDao

    override fun getTemplateDao(): TemplateDao = templateDao

    override fun getConfig(): OperationResult<EncryptedDatabaseConfig> {
        return lock.withLock {
            val rawDatabase = getRawDatabase()

            val config = MutableEncryptedDatabaseConfig(
                isRecycleBinEnabled = rawDatabase.content.meta.recycleBinEnabled,
                maxHistoryItems = rawDatabase.content.meta.historyMaxItems
            )

            OperationResult.success(config)
        }
    }

    override fun applyConfig(newConfig: EncryptedDatabaseConfig): OperationResult<Boolean> {
        return lock.withLock {
            val getOldConfigResult = config
            if (getOldConfigResult.isFailed) {
                return@withLock getOldConfigResult.mapError()
            }

            val oldConfig = getOldConfigResult.obj

            if (oldConfig != newConfig) {
                swapDatabase(
                    getRawDatabase().modifyMeta {
                        copy(
                            recycleBinEnabled = newConfig.isRecycleBinEnabled
                        )
                    }
                )
            }

            commit()
        }
    }

    override fun changeKey(
        oldKey: EncryptedDatabaseKey,
        newKey: EncryptedDatabaseKey
    ): OperationResult<Boolean> {
        return lock.withLock {
            val currentKey = key.get()
            if (oldKey != currentKey) {
                return@withLock OperationResult.error(
                    newAuthError(
                        if (currentKey.type == KeyType.PASSWORD) {
                            MESSAGE_INVALID_PASSWORD
                        } else {
                            MESSAGE_INVALID_KEY_FILE
                        },
                        Stacktrace()
                    )
                )
            }

            val getCredentialsResult = newKey.toCredentials(fsResolver)
            if (getCredentialsResult.isFailed) {
                return getCredentialsResult.mapError()
            }

            val newCredentials = getCredentialsResult.obj
            val newDb = database.get().modifyCredentials {
                newCredentials
            }
            swapDatabase(newDb)
            key.set(newKey)

            commit()
        }
    }

    override fun commit(): OperationResult<Boolean> {
        val updatedFile = file.get().copy(modified = System.currentTimeMillis())

        val result = commitTo(updatedFile, fsOptions)
        if (result.isSucceededOrDeferred) {
            file.set(updatedFile)
        }

        return result
    }

    override fun commitTo(
        output: FileDescriptor,
        fsOptions: FSOptions
    ): OperationResult<Boolean> {
        val fsProvider = fsResolver.resolveProvider(output.fsAuthority)

        val commitResult = lock.withLock {
            val outResult = fsProvider.openFileForWrite(
                output,
                OnConflictStrategy.CANCEL,
                fsOptions
            )
            if (outResult.isFailed) {
                return outResult.mapError()
            }

            val out = outResult.obj
            val db = database.get()
            try {
                db.encode(out)
                outResult.takeStatusWith(true)
            } catch (e: IOException) {
                InputOutputUtils.close(out)

                OperationResult.error(newGenericIOError(e))
            }
        }

        if (commitResult.isSucceededOrDeferred) {
            dbWatcher.notifyOnCommit(this, commitResult)
        }

        return commitResult
    }

    fun swapDatabase(db: KeePassDatabase) {
        lock.withLock {
            database.set(db)
            autotypeOptionMap.set(createInheritableOptionsMap())
            groupUidToParentMap.set(createGroupUidToParentMap())
        }
    }

    fun getRawDatabase(): KeePassDatabase = database.get()

    fun getRawRootGroup(): RawGroup = database.get().content.group

    fun getRawRootGroupOptions(): InheritableOptions {
        val root = getRawRootGroup()

        return InheritableOptions(
            autotypeEnabled = root.enableAutoType.convertToInheritableOption(
                parentValue = DEFAULT_ROOT_INHERITABLE_VALUE
            ),
            searchEnabled = root.enableSearching.convertToInheritableOption(
                parentValue = DEFAULT_ROOT_INHERITABLE_VALUE
            )
        )
    }

    fun getRawParentGroup(childUid: UUID): OperationResult<RawGroup> {
        val parentGroup = groupUidToParentMap.get()[childUid]
            ?: return OperationResult.error(
                newDbError(
                    MESSAGE_FAILED_TO_FIND_GROUP,
                    Stacktrace()
                )
            )

        return OperationResult.success(parentGroup)
    }

    fun getRawGroupByUid(uid: UUID): OperationResult<RawGroup> {
        val rootGroup = database.get().content.group
        if (rootGroup.uuid == uid) {
            return OperationResult.success(rootGroup)
        }

        val (_, parentGroup) = rootGroup.findChildGroup { it.uuid == uid }
            ?: return OperationResult.error(
                newDbError(
                    MESSAGE_FAILED_TO_FIND_GROUP,
                    Stacktrace()
                )
            )

        return OperationResult.success(parentGroup)
    }

    fun getRawEntryAndGroupByUid(noteUid: UUID): OperationResult<Pair<RawGroup, Entry>> {
        val result = database.get().content.group.findChildEntry { entry ->
            entry.uuid == noteUid
        }
            ?: return OperationResult.error(
                newDbError(
                    String.format(
                        GENERIC_MESSAGE_FAILED_TO_FIND_ENTITY_BY_UID,
                        Note::class.simpleName,
                        noteUid
                    ),
                    Stacktrace()
                )
            )

        return OperationResult.success(result)
    }

    fun getRawChildGroups(root: RawGroup): List<RawGroup> {
        val nextGroups = LinkedList<RawGroup>()
            .apply {
                add(root)
            }

        val allGroups = mutableListOf<RawGroup>()

        while (nextGroups.size > 0) {
            val currentGroup = nextGroups.removeFirst()

            nextGroups.addAll(currentGroup.groups)
            allGroups.addAll(currentGroup.groups)
        }

        return allGroups
    }

    fun getAllRawGroups(): List<RawGroup> = database.get().getAllGroups()

    fun getRawChildEntries(root: RawGroup): List<Entry> {
        val nextGroups = LinkedList<RawGroup>()
            .apply {
                add(root)
            }

        val allEntries = mutableListOf<Entry>()

        while (nextGroups.size > 0) {
            val currentGroup = nextGroups.pop()
            nextGroups.addAll(currentGroup.groups)
            allEntries.addAll(currentGroup.entries)
        }

        return allEntries
    }

    fun <T> collectEntries(
        root: RawGroup,
        transform: (group: RawGroup, groupEntries: List<Entry>) -> List<T>
    ): List<T> {
        val result = mutableListOf<T>()

        val nextGroups = LinkedList<RawGroup>()
            .apply {
                add(root)
            }

        while (nextGroups.size > 0) {
            val currentGroup = nextGroups.pop()
            nextGroups.addAll(currentGroup.groups)
            result.addAll(transform.invoke(currentGroup, currentGroup.entries))
        }

        return result
    }

    fun getInheritableOptions(groupUid: UUID): OperationResult<InheritableOptions> {
        val options = autotypeOptionMap.get()[groupUid]
        return options?.let { OperationResult.success(it) }
            ?: OperationResult.error(
                newDbError(
                    MESSAGE_FAILED_TO_FIND_GROUP,
                    Stacktrace()
                )
            )
    }

    private fun createInheritableOptionsMap(): Map<UUID, InheritableOptions> {
        val result = hashMapOf<UUID, InheritableOptions>()

        val root = getRawRootGroup()
        val rootOptions = getRawRootGroupOptions()
        result[root.uuid] = rootOptions

        val nextGroups = LinkedList<Pair<RawGroup, InheritableOptions>>()
            .apply {
                add(Pair(root, rootOptions))
            }

        while (nextGroups.size > 0) {
            val (group, parentOptions) = nextGroups.removeFirst()

            val options = InheritableOptions(
                autotypeEnabled = group.enableAutoType.convertToInheritableOption(
                    parentValue = parentOptions.autotypeEnabled.isEnabled
                ),
                searchEnabled = group.enableSearching.convertToInheritableOption(
                    parentValue = parentOptions.searchEnabled.isEnabled
                )
            )
            result[group.uuid] = options

            for (child in group.groups) {
                nextGroups.add(Pair(child, options))
            }
        }

        return result
    }

    private fun createGroupUidToParentMap(): Map<UUID, RawGroup> {
        val result = hashMapOf<UUID, RawGroup>()

        val nextGroups = LinkedList<RawGroup>()
            .apply {
                add(getRawRootGroup())
            }

        while (nextGroups.size > 0) {
            val group = nextGroups.removeFirst()

            for (child in group.groups) {
                result[child.uuid] = group
                nextGroups.add(child)
            }
        }

        return result
    }

    private fun setupRecycleBin(): OperationResult<Unit> {
        swapDatabase(
            getRawDatabase().modifyMeta {
                this.copy(
                    recycleBinEnabled = true,
                    recycleBinUuid = UUID.randomUUID()
                )
            }
        )

        return OperationResult.success(Unit)
    }

    private fun setupTemplates(doCommit: Boolean): OperationResult<Unit> {
        val addTemplatesResult = templateDao.addTemplates(
            templates = TemplateFactory.createDefaultTemplates(),
            doInterstitialCommits = doCommit
        )
        if (addTemplatesResult.isFailed) {
            return addTemplatesResult.mapError()
        }

        val getTemplateUidResult = templateDao.getTemplateGroupUid()
        if (getTemplateUidResult.isFailed) {
            return getTemplateUidResult.mapError()
        }

        val templateGroupUid = getTemplateUidResult.getOrNull()
            ?: return OperationResult.error(
                newDbError(
                    MESSAGE_FAILED_TO_FIND_GROUP,
                    Stacktrace()
                )
            )

        swapDatabase(
            getRawDatabase().modifyMeta {
                this.copy(
                    entryTemplatesGroup = templateGroupUid
                )
            }
        )

        return if (doCommit) {
            commit().takeStatusWith(Unit)
        } else {
            OperationResult.success(Unit)
        }
    }

    fun isEntryInsideGroupTree(
        entryUid: UUID,
        groupTreeRootUid: UUID
    ): OperationResult<Boolean> {
        val getTreeRootResult = getRawGroupByUid(groupTreeRootUid)
        if (getTreeRootResult.isFailed) {
            return getTreeRootResult.mapError()
        }

        val rawTreeRoot = getTreeRootResult.obj
        val tree = getRawChildEntries(rawTreeRoot)

        val isEntryInsideTree = tree.any { entry -> entry.uuid == entryUid }

        return OperationResult.success(isEntryInsideTree)
    }

    fun getRecycleBinGroup(): OperationResult<RawGroup?> {
        val rawDb = getRawDatabase()

        val isRecycleBinEnabled = rawDb.content.meta.recycleBinEnabled
        if (!isRecycleBinEnabled) {
            return OperationResult.success(null)
        }

        val recycleBinUid = rawDb.content.meta.recycleBinUuid
            ?: return OperationResult.success(null)

        val getRecycleBinResult = getRawGroupByUid(recycleBinUid)
        return OperationResult.success(getRecycleBinResult.getOrNull())
    }

    companion object {

        const val DEFAULT_ROOT_INHERITABLE_VALUE = true

        private const val DEFAULT_ROOT_GROUP_NAME = "Database"

        fun new(
            fsResolver: FileSystemResolver,
            fsOptions: FSOptions,
            file: FileDescriptor,
            key: EncryptedDatabaseKey,
            isAddTemplates: Boolean
        ): OperationResult<KotpassDatabase> {
            val getCredentialsResult = key.toCredentials(fsResolver)
            if (getCredentialsResult.isFailed) {
                return getCredentialsResult.mapError()
            }

            val credentials = getCredentialsResult.obj

            val rawDb = KeePassDatabase.Ver4x.create(
                rootName = DEFAULT_ROOT_GROUP_NAME,
                meta = Meta(
                    recycleBinEnabled = true
                ),
                credentials = credentials
            )

            val db = KotpassDatabase(
                fsResolver = fsResolver,
                fsOptions = fsOptions,
                file = file,
                key = key,
                db = rawDb
            )

            val setupRecycleBinResult = db.setupRecycleBin()
            if (setupRecycleBinResult.isFailed) {
                return setupRecycleBinResult.mapError()
            }

            if (isAddTemplates) {
                val setupTemplatesResult = db.setupTemplates(doCommit = false)
                if (setupTemplatesResult.isFailed) {
                    return setupTemplatesResult.mapError()
                }
            }

            val commitResult = db.commit()
            if (commitResult.isFailed) {
                return commitResult.mapError()
            }

            return OperationResult.success(db)
        }

        fun open(
            fsResolver: FileSystemResolver,
            fsOptions: FSOptions,
            file: FileDescriptor,
            content: OperationResult<InputStream>,
            key: EncryptedDatabaseKey
        ): OperationResult<KotpassDatabase> {
            if (content.isFailed) {
                return content.mapError()
            }

            val contentStream = content.obj
            val getCredentialsResult = key.toCredentials(fsResolver)
            if (getCredentialsResult.isFailed) {
                return getCredentialsResult.mapError()
            }

            val credentials = getCredentialsResult.obj

            try {
                val db = KeePassDatabase.decode(contentStream, credentials)

                return OperationResult.success(
                    KotpassDatabase(
                        fsResolver = fsResolver,
                        fsOptions = fsOptions,
                        file = file,
                        key = key,
                        db = db
                    )
                )
            } catch (e: Exception) {
                Timber.d(e)

                val message = if (!e.message.isNullOrEmpty()) {
                    e.message
                } else {
                    OperationError.MESSAGE_FAILED_TO_OPEN_DB_FILE
                }

                return if (e is IOException) {
                    OperationResult.error(newGenericIOError(message, e))
                } else {
                    OperationResult.error(newDbError(message, e))
                }
            } finally {
                InputOutputUtils.close(contentStream)
            }
        }
    }
}