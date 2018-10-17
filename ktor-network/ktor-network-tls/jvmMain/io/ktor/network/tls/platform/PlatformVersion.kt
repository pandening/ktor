package io.ktor.network.tls.platform

internal class PlatformVersion(val major: String, val minor: Int) {

    companion object {
        operator fun invoke(rawVersion: String): PlatformVersion {
            try {
                val versionString = rawVersion.split('-', '_')
                if (versionString.size == 2) {
                    val (major, minor) = versionString
                    return PlatformVersion(major, minor.toInt())
                }

                return PlatformVersion(major = rawVersion, minor = -1)
            } catch (cause: Throwable) {
                return MINIMAL_SUPPORTED
            }
        }

        private val MINIMAL_SUPPORTED: PlatformVersion = PlatformVersion("1.6.0", 0)
    }
}

internal val platformVersion: PlatformVersion by lazy {
    PlatformVersion(System.getProperty("java.version"))
}
