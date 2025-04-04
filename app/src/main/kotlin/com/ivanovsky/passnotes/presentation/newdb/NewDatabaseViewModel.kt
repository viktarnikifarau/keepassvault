package com.ivanovsky.passnotes.presentation.newdb

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import com.ivanovsky.passnotes.R
import com.ivanovsky.passnotes.data.entity.FSType
import com.ivanovsky.passnotes.data.entity.FileDescriptor
import com.ivanovsky.passnotes.data.entity.OperationError
import com.ivanovsky.passnotes.data.repository.keepass.PasswordKeepassKey
import com.ivanovsky.passnotes.domain.FileHelper
import com.ivanovsky.passnotes.domain.ResourceProvider
import com.ivanovsky.passnotes.domain.entity.exception.Stacktrace
import com.ivanovsky.passnotes.domain.interactor.newdb.NewDatabaseInteractor
import com.ivanovsky.passnotes.presentation.ApplicationLaunchMode
import com.ivanovsky.passnotes.presentation.Screens.GroupsScreen
import com.ivanovsky.passnotes.presentation.Screens.StorageListScreen
import com.ivanovsky.passnotes.presentation.core.BaseScreenViewModel
import com.ivanovsky.passnotes.presentation.core.DefaultScreenVisibilityHandler
import com.ivanovsky.passnotes.presentation.core.ScreenState
import com.ivanovsky.passnotes.presentation.core.event.SingleLiveEvent
import com.ivanovsky.passnotes.presentation.groups.GroupsScreenArgs
import com.ivanovsky.passnotes.presentation.storagelist.Action
import com.ivanovsky.passnotes.presentation.storagelist.StorageListArgs
import com.ivanovsky.passnotes.util.FileUtils
import com.ivanovsky.passnotes.util.FileUtils.createPath
import com.ivanovsky.passnotes.util.FileUtils.removeFileExtensionsIfNeed
import com.ivanovsky.passnotes.util.StringUtils.EMPTY
import java.io.File
import kotlinx.coroutines.launch

class NewDatabaseViewModel(
    private val interactor: NewDatabaseInteractor,
    private val fileHelper: FileHelper,
    private val resourceProvider: ResourceProvider,
    private val router: Router
) : BaseScreenViewModel(
    initialState = ScreenState.data()
) {

    val screenStateHandler = DefaultScreenVisibilityHandler()
    val filename = MutableLiveData(EMPTY)
    val password = MutableLiveData(EMPTY)
    val confirmation = MutableLiveData(EMPTY)
    val filenameError = MutableLiveData<String?>(null)
    val passwordError = MutableLiveData<String?>(null)
    val confirmationError = MutableLiveData<String?>(null)
    val storageType = MutableLiveData<String>()
    val storagePath = MutableLiveData(resourceProvider.getString(R.string.not_selected))
    val doneButtonVisibility = MutableLiveData(true)
    val isAddTemplates = MutableLiveData(true)
    val hideKeyboardEvent = SingleLiveEvent<Unit>()
    val showSnackBarEvent = SingleLiveEvent<String>()
    val isFilenameEnabled = MutableLiveData(true)

    private var selectedStorage: SelectedStorage? = null

    fun createNewDatabaseFile() {
        val filename = this.filename.value ?: return
        val password = this.password.value ?: return
        val confirmation = this.confirmation.value ?: return
        val isAddTemplates = this.isAddTemplates.value ?: false

        if (!isFieldsValid(filename, password, confirmation)) {
            return
        }

        if (selectedStorage == null) {
            val errorText = resourceProvider.getString(R.string.storage_is_not_selected)
            setErrorPanelState(OperationError.newErrorMessage(errorText, Stacktrace()))
            return
        }

        hideKeyboardEvent.call(Unit)
        setScreenState(ScreenState.loading())

        val dbKey = PasswordKeepassKey(password)
        val dbFile = createDbFile()

        viewModelScope.launch {
            val result = interactor.createNewDatabaseAndOpen(dbKey, dbFile, isAddTemplates)

            if (result.isSucceededOrDeferred) {
                val created = result.obj

                if (created) {
                    router.replaceScreen(
                        GroupsScreen(
                            GroupsScreenArgs(
                                appMode = ApplicationLaunchMode.NORMAL,
                                groupUid = null,
                                isCloseDatabaseOnExit = true,
                                isSearchModeEnabled = false
                            )
                        )
                    )
                } else {
                    val errorText = resourceProvider.getString(R.string.error_has_been_occurred)
                    setErrorPanelState(OperationError.newErrorMessage(errorText, Stacktrace()))
                }
            } else {
                setErrorPanelState(result.error)
            }
        }
    }

    private fun isFieldsValid(
        filename: String,
        password: String,
        confirmation: String
    ): Boolean {
        if (filename.isBlank() || password.isBlank() || confirmation.isBlank()) {
            filenameError.value = if (filename.isBlank()) {
                resourceProvider.getString(R.string.empty_field)
            } else {
                null
            }

            passwordError.value = if (password.isBlank()) {
                resourceProvider.getString(R.string.empty_field)
            } else {
                null
            }

            confirmationError.value = if (confirmation.isBlank()) {
                resourceProvider.getString(R.string.empty_field)
            } else {
                null
            }

            return false
        }

        filenameError.value = null
        passwordError.value = null

        if (password == confirmation) {
            confirmationError.value = null
        } else {
            confirmationError.value =
                resourceProvider.getString(R.string.this_field_should_match_password)
        }

        return filenameError.value == null &&
            passwordError.value == null &&
            confirmationError.value == null
    }

    fun onSelectStorageClicked() {
        val resultKey = StorageListScreen.newResultKey()

        router.setResultListener(resultKey) { file ->
            if (file is FileDescriptor) {
                onStorageSelected(file)
            }
        }
        router.navigateTo(
            StorageListScreen(
                StorageListArgs(
                    action = Action.PICK_STORAGE,
                    resultKey = resultKey
                )
            )
        )
    }

    fun onTemplatesInfoButtonClicked() {
        showSnackBarEvent.call(
            resourceProvider.getString(R.string.add_templates_info_message)
        )
    }

    fun navigateBack() = router.exit()

    private fun onStorageSelected(selectedFile: FileDescriptor) {
        when (selectedFile.fsAuthority.type) {
            FSType.INTERNAL_STORAGE, FSType.EXTERNAL_STORAGE -> {
                selectedStorage = SelectedStorage.ParentDir(selectedFile)

                if (fileHelper.isLocatedInInternalStorage(File(selectedFile.path))) {
                    storageType.value = resourceProvider.getString(R.string.private_storage)
                } else {
                    storageType.value = resourceProvider.getString(R.string.public_storage)
                }
            }

            FSType.WEBDAV -> {
                selectedStorage = SelectedStorage.ParentDir(selectedFile)
                storageType.value = resourceProvider.getString(R.string.webdav)
            }

            FSType.SAF -> {
                selectedStorage = SelectedStorage.File(selectedFile)
                storageType.value = resourceProvider.getString(R.string.public_storage)
                filename.value = removeFileExtensionsIfNeed(selectedFile.name)
            }

            FSType.FAKE -> {
                selectedStorage = SelectedStorage.ParentDir(selectedFile)
                storageType.value = resourceProvider.getString(R.string.fake_file_system)
            }

            FSType.UNDEFINED, FSType.GIT -> {} // TODO: Implement file creation for GIT
        }

        storagePath.value = selectedFile.path
        isFilenameEnabled.value = (selectedFile.fsAuthority.type != FSType.SAF)
    }

    private fun createDbFile(): FileDescriptor {
        val selectedStorage = selectedStorage ?: throw IllegalStateException()

        return when (selectedStorage) {
            is SelectedStorage.ParentDir -> {
                val name = this.filename.value ?: throw IllegalStateException()
                val path = createPath(
                    parentPath = selectedStorage.dir.path,
                    name = "$name.kdbx"
                )

                FileDescriptor(
                    fsAuthority = selectedStorage.dir.fsAuthority,
                    path = path,
                    uid = path,
                    name = FileUtils.getFileNameFromPath(path),
                    isDirectory = false,
                    isRoot = false,
                    modified = null
                )
            }

            is SelectedStorage.File -> {
                selectedStorage.file
            }
        }
    }

    override fun setScreenState(state: ScreenState) {
        super.setScreenState(state)
        doneButtonVisibility.value = state.isDisplayingData
    }

    private sealed interface SelectedStorage {
        data class ParentDir(val dir: FileDescriptor) : SelectedStorage
        data class File(val file: FileDescriptor) : SelectedStorage
    }
}