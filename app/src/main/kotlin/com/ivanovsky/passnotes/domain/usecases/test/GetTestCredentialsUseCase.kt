package com.ivanovsky.passnotes.domain.usecases.test

import com.ivanovsky.passnotes.BuildConfig
import com.ivanovsky.passnotes.data.entity.FSCredentials
import com.ivanovsky.passnotes.data.repository.settings.Settings
import java.util.UUID

class GetTestCredentialsUseCase(
    private val settings: Settings
) {

    fun getDebugWebDavCredentials(): FSCredentials.BasicCredentials? {
        if (!BuildConfig.DEBUG) return null

        val data = settings.testAutofillData ?: return null

        return if (data.webdavUrl.isNotEmpty() ||
            data.webdavUsername.isNotEmpty() ||
            data.webdavPassword.isNotEmpty()
        ) {
            FSCredentials.BasicCredentials(
                url = data.webdavUrl,
                username = data.webdavUsername,
                password = data.webdavPassword,
                isIgnoreSslValidation = false
            )
        } else {
            null
        }
    }

    fun getDebugGitCredentials(): FSCredentials.GitCredentials? {
        if (!BuildConfig.DEBUG) return null

        val data = settings.testAutofillData ?: return null

        return if (data.gitUrl.isNotEmpty()) {
            FSCredentials.GitCredentials(
                url = data.gitUrl,
                isSecretUrl = false,
                salt = UUID.randomUUID().toString()
            )
        } else {
            null
        }
    }

    fun getDebugFakeCredentials(): FSCredentials.BasicCredentials? {
        if (!BuildConfig.DEBUG) return null

        val data = settings.testAutofillData ?: return null

        return if (data.fakeFsUrl.isNotEmpty() ||
            data.fakeFsUsername.isNotEmpty() ||
            data.fakeFsPassword.isNotEmpty()
        ) {
            FSCredentials.BasicCredentials(
                url = data.fakeFsUrl,
                username = data.fakeFsUsername,
                password = data.fakeFsPassword,
                isIgnoreSslValidation = false
            )
        } else {
            null
        }
    }
}