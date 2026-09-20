package com.example.angi.data.saf

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.example.angi.domain.saf.AndroidSharedResource
import com.example.angi.domain.saf.AndroidSharedResourceRegistry
import com.example.angi.domain.saf.SafCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class DefaultAndroidSharedResourceRegistry(
    private val context: Context
) : AndroidSharedResourceRegistry {

    private val prefs: SharedPreferences = context.getSharedPreferences("angi_saf_resources", Context.MODE_PRIVATE)
    private val _resourcesFlow = MutableStateFlow<List<AndroidSharedResource>>(loadFromPrefs())

    override fun getResources(): Flow<List<AndroidSharedResource>> = _resourcesFlow.asStateFlow()

    override suspend fun getAll(): List<AndroidSharedResource> = _resourcesFlow.value

    override suspend fun getResource(resourceId: String): AndroidSharedResource? {
        return _resourcesFlow.value.find { it.resourceId == resourceId }
    }

    override suspend fun register(resource: AndroidSharedResource) {
        // Verify and take persistable permission if not already retained
        val uri = Uri.parse(resource.treeUriString)
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Throwable) {
            // Might already be retained or fail on non-tree uri; continue
        }

        val current = _resourcesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.resourceId == resource.resourceId }
        if (index >= 0) {
            current[index] = resource
        } else {
            current.add(resource)
        }
        saveToPrefs(current)
        _resourcesFlow.value = current
    }

    override suspend fun unregister(resourceId: String) {
        val target = _resourcesFlow.value.find { it.resourceId == resourceId }
        if (target != null) {
            try {
                val uri = Uri.parse(target.treeUriString)
                val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.releasePersistableUriPermission(uri, releaseFlags)
            } catch (_: Throwable) {
                // Ignore if already released or system error
            }
        }
        val current = _resourcesFlow.value.filter { it.resourceId != resourceId }
        saveToPrefs(current)
        _resourcesFlow.value = current
    }

    private fun loadFromPrefs(): List<AndroidSharedResource> {
        val jsonStr = prefs.getString("resources_json", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<AndroidSharedResource>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    AndroidSharedResource(
                        resourceId = obj.getString("resourceId"),
                        displayName = obj.getString("displayName"),
                        treeUriString = obj.getString("treeUriString"),
                        capability = runCatching {
                            SafCapability.valueOf(obj.getString("capability"))
                        }.getOrDefault(SafCapability.READ_WRITE),
                        grantedTimestamp = obj.optLong("grantedTimestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    private fun saveToPrefs(list: List<AndroidSharedResource>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("resourceId", item.resourceId)
                put("displayName", item.displayName)
                put("treeUriString", item.treeUriString)
                put("capability", item.capability.name)
                put("grantedTimestamp", item.grantedTimestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString("resources_json", array.toString()).apply()
    }
}
