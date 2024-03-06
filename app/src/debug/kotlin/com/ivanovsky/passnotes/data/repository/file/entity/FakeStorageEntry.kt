package com.ivanovsky.passnotes.data.repository.file.entity

import com.ivanovsky.passnotes.data.entity.FileDescriptor
import com.ivanovsky.passnotes.data.entity.SyncStatus
import com.ivanovsky.passnotes.data.repository.file.DatabaseContentFactory

data class FakeStorageEntry(
    val localFile: FileDescriptor,
    val remoteFile: FileDescriptor,
    val syncStatus: SyncStatus = SyncStatus.NO_CHANGES,
    val localContentFactory: DatabaseContentFactory?,
    val remoteContentFactory: DatabaseContentFactory?
)