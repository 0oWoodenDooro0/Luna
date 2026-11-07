package luna.config

import java.io.File

/**
 * Traversal strategy to discover .env files across project roots and submodule directories.
 */
object ConfigFinder {
    fun findDefaultEnvFile(baseDir: File = File(".")): File? {
        val direct = File(baseDir, ".env")
        if (direct.exists() && direct.isFile) return direct
        val parent = File(baseDir, "../.env")
        if (parent.exists() && parent.isFile) return parent
        return null
    }
}
