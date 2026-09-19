package com.example.angi.domain.environment

import kotlinx.coroutines.flow.Flow

enum class LinuxEnvironmentStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    INSTALLED,
    FAILED
}

data class LinuxEnvironmentDefinition(
    val id: String,
    val distribution: String,
    val version: String,
    val architecture: String,
    val sourceUrl: String,
    val expectedSha256: String,
    val description: String = ""
)

data class LinuxEnvironmentMetadata(
    val environmentId: String,
    val distribution: String,
    val version: String,
    val architecture: String,
    val sourceUrl: String,
    val expectedSha256: String,
    val actualSha256: String? = null,
    val archiveSizeBytes: Long = 0L,
    val installedSizeBytes: Long = 0L,
    val installTimestamp: Long = 0L,
    val lastError: String? = null
)

data class LinuxEnvironment(
    val definition: LinuxEnvironmentDefinition,
    val status: LinuxEnvironmentStatus,
    val metadata: LinuxEnvironmentMetadata? = null
)

data class LinuxRwSelfTestResult(
    val writeSuccess: Boolean,
    val readSuccess: Boolean,
    val modifySuccess: Boolean,
    val nonce1: String,
    val nonce2: String,
    val details: String
)

interface LinuxEnvironmentManager {
    fun environments(): Flow<List<LinuxEnvironment>>
    suspend fun download(definition: LinuxEnvironmentDefinition): Result<Unit>
    suspend fun install(environmentId: String): Result<Unit>
    suspend fun delete(environmentId: String): Result<Unit>
    suspend fun status(environmentId: String): LinuxEnvironmentStatus
    suspend fun runSelfTest(environmentId: String): LinuxRwSelfTestResult
}
