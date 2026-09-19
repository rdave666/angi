package com.example.angi.domain.saf

import kotlinx.coroutines.flow.Flow

enum class SafCapability {
    READ,
    WRITE,
    READ_WRITE
}

data class AndroidSharedResource(
    val resourceId: String,
    val displayName: String,
    val treeUriString: String,
    val capability: SafCapability,
    val grantedTimestamp: Long = System.currentTimeMillis()
)

interface AndroidSharedResourceRegistry {
    fun getResources(): Flow<List<AndroidSharedResource>>
    suspend fun getAll(): List<AndroidSharedResource>
    suspend fun getResource(resourceId: String): AndroidSharedResource?
    suspend fun register(resource: AndroidSharedResource)
    suspend fun unregister(resourceId: String)
}
