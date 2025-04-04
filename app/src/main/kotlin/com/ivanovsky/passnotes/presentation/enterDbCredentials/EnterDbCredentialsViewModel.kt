package com.ivanovsky.passnotes.presentation.enterDbCredentials

import android.view.inputmethod.EditorInfo
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.terrakok.cicerone.Router
import com.ivanovsky.passnotes.R
import com.ivanovsky.passnotes.data.entity.FileDescriptor
import com.ivanovsky.passnotes.data.repository.encdb.EncryptedDatabaseKey
import com.ivanovsky.passnotes.data.repository.keepass.FileKeepassKey
import com.ivanovsky.passnotes.data.repository.keepass.PasswordKeepassKey
import com.ivanovsky.passnotes.injection.GlobalInjector
import com.ivanovsky.passnotes.presentation.Screens.EnterDbCredentialsScreen.Companion.RESULT_KEY
import com.ivanovsky.passnotes.presentation.Screens.StorageListScreen
import com.ivanovsky.passnotes.presentation.core.BaseScreenViewModel
import com.ivanovsky.passnotes.presentation.core.DefaultScreenVisibilityHandler
import com.ivanovsky.passnotes.presentation.core.ScreenState
import com.ivanovsky.passnotes.presentation.core.event.SingleLiveEvent
import com.ivanovsky.passnotes.presentation.storagelist.Action
import com.ivanovsky.passnotes.presentation.storagelist.StorageListArgs
import com.ivanovsky.passnotes.util.StringUtils.EMPTY
import kotlinx.coroutines.launch
import org.koin.core.parameter.parametersOf

class EnterDbCredentialsViewModel(
    private val interactor: EnterDbCredentialsInteractor,
    private val router: Router,
    private val args: EnterDbCredentialsScreenArgs
) : BaseScreenViewModel(
    initialState = ScreenState.data()
) {

    val screenVisibilityHandler = DefaultScreenVisibilityHandler()
    val filename = MutableLiveData(args.file.name)
    val keyFilename = MutableLiveData(EMPTY)
    val isAddKeyButtonVisible = MutableLiveData(true)
    val password = MutableLiveData(EMPTY)
    val unlockIconResId = R.drawable.ic_lock_open_24dp
    val isKeyboardVisibleEvent = SingleLiveEvent<Boolean>()

    private var selectedKeyFile: FileDescriptor? = null

    fun start() {
        isKeyboardVisibleEvent.call(true)
    }

    fun onUnlockButtonClicked() {
        isKeyboardVisibleEvent.call(false)

        setScreenState(ScreenState.loading())

        val key = createKey()

        viewModelScope.launch {
            val isValidKey = interactor.isValidKey(
                key = key,
                file = args.file
            )

            if (isValidKey.isSucceededOrDeferred) {
                router.exit()
                router.sendResult(RESULT_KEY, key)
            } else {
                setErrorPanelState(isValidKey.error)
                isKeyboardVisibleEvent.call(true)
            }
        }
    }

    fun onAddKeyFileButtonClicked() {
        val resultKey = StorageListScreen.newResultKey()

        router.setResultListener(resultKey) { keyFile ->
            if (keyFile is FileDescriptor) {
                checkAndSetSelectedKeyFile(keyFile)
            }
        }

        router.navigateTo(
            StorageListScreen(
                StorageListArgs(
                    action = Action.PICK_FILE,
                    resultKey = resultKey
                )
            )
        )
    }

    fun onRemoveKeyFileButtonClicked() {
        selectedKeyFile = null
        isAddKeyButtonVisible.value = true
    }

    fun onEditorAction(actionId: Int) {
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            onUnlockButtonClicked()
        }
    }

    private fun checkAndSetSelectedKeyFile(keyFile: FileDescriptor) {
        setScreenState(ScreenState.loading())

        viewModelScope.launch {
            val getFileResult = interactor.getFile(keyFile.path, keyFile.fsAuthority)
            if (getFileResult.isSucceeded) {
                val file = getFileResult.obj

                selectedKeyFile = file
                keyFilename.value = file.name

                setScreenState(ScreenState.data())
            } else {
                selectedKeyFile = null
                keyFilename.value = EMPTY

                setErrorPanelState(getFileResult.error)
            }
        }
    }

    private fun createKey(): EncryptedDatabaseKey {
        val keyFile = selectedKeyFile
        val password = password.value ?: EMPTY

        return when {
            keyFile != null -> {
                FileKeepassKey(
                    file = keyFile,
                    password = password.ifEmpty { null }
                )
            }

            else -> PasswordKeepassKey(password)
        }
    }

    override fun setScreenState(state: ScreenState) {
        super.setScreenState(state)
        isAddKeyButtonVisible.value = (selectedKeyFile == null)
    }

    class Factory(
        private val args: EnterDbCredentialsScreenArgs
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GlobalInjector.get<EnterDbCredentialsViewModel>(
                parametersOf(args)
            ) as T
        }
    }
}