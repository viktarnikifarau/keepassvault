package com.ivanovsky.passnotes.data.repository.file.remote

import java.util.*

data class ProcessingUnit(
    val processingUid: UUID,
    val status: ProcessingStatus,
    val fileUid: String,
    val remotePath: String
)