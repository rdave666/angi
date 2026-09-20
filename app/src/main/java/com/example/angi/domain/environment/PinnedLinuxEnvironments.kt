package com.example.angi.domain.environment

object PinnedLinuxEnvironments {
    /**
     * Debian Bookworm (12) Rootfs for ARM64 (Samsung Galaxy S23 Ultra / Snapdragon 8 Gen 2).
     * Official LXC image with full package ecosystem (apt, python, node, gcc, git).
     */
    val DEBIAN_12_ARM64 = LinuxEnvironmentDefinition(
        id = "debian-12-arm64",
        distribution = "Debian Linux",
        version = "12 (Bookworm)",
        architecture = "arm64",
        sourceUrl = "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/",
        expectedSha256 = "", // LXC images update regularly and are verified via index
        description = "Debian 12 Bookworm ARM64 full Linux userspace for Snapdragon 8 Gen 2."
    )

    /**
     * Alpine Linux AArch64 minirootfs v3.21.8 fallback.
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

    val DEFAULT_DEFINITIONS = listOf(DEBIAN_12_ARM64, ALPINE_3_21_AARCH64)
}
