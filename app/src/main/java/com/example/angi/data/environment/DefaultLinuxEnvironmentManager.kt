package com.example.angi.data.environment

import android.content.Context
import com.example.angi.domain.environment.LinuxEnvironment
import com.example.angi.domain.environment.LinuxEnvironmentDefinition
import com.example.angi.domain.environment.LinuxEnvironmentManager
import com.example.angi.domain.environment.LinuxEnvironmentStatus
import com.example.angi.domain.environment.LinuxRwSelfTestResult
import com.example.angi.domain.environment.PinnedLinuxEnvironments
import com.example.angi.runtime.linux.LinuxPathResolver
import com.example.angi.runtime.proot.LinuxSandboxManager
import kotlinx.coroutines.flow.Flow

/**
 * Unified delegate to LinuxSandboxManager.
 * Enforces single PRoot/Debian environment implementation and single storage tree:
 * filesDir/linux-sandbox/{rootfs, workspace, tmp}.
 */
class DefaultLinuxEnvironmentManager(
    context: Context,
    private val sandboxManager: LinuxSandboxManager = LinuxSandboxManager(context)
) : LinuxEnvironmentManager by sandboxManager {

    fun getPathResolver(environmentId: String = PinnedLinuxEnvironments.DEBIAN_12_ARM64.id): LinuxPathResolver {
        return sandboxManager.getPathResolver()
    }

    fun getPathResolver(): LinuxPathResolver {
        return sandboxManager.getPathResolver()
    }
}
