package com.example.angi.domain.environment

object PinnedLinuxEnvironments {
    /**
     * Official Alpine Linux AArch64 minirootfs v3.21.8.
     * Pinned version and official sha256 checksum from dl-cdn.alpinelinux.org.
     */
    val ALPINE_3_21_AARCH64 = LinuxEnvironmentDefinition(
        id = "alpine-3.21-aarch64",
        distribution = "Alpine Linux",
        version = "3.21.8",
        architecture = "aarch64",
        sourceUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.8-aarch64.tar.gz",
        expectedSha256 = "f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001",
        description = "Alpine Linux 3.21.8 Minirootfs (AArch64 / ARM64 for Snapdragon 8 Gen 2)"
    )

    val DEFAULT_DEFINITIONS = listOf(ALPINE_3_21_AARCH64)
}
