package android.system

import java.io.File

class ErrnoException(val errno: Int) : Exception()
data class StructStat(val st_uid: Int, val st_mode: Int)
object OsConstants {
    const val EEXIST = 17
    const val S_IFMT = 0xf000
    const val S_IFDIR = 0x4000
}
object Os {
    var rejectDirectoryMode = false
    var statCalls = 0
    private val modes = mutableMapOf<String, Int>()
    fun reset() { rejectDirectoryMode = false; statCalls = 0; modes.clear() }
    fun mkdir(path: String, mode: Int) {
        if (!File(path).mkdir()) throw ErrnoException(OsConstants.EEXIST)
        modes[path] = mode
    }
    fun chmod(path: String, mode: Int) { modes[path] = if (rejectDirectoryMode && File(path).isDirectory) 0x1f9 else mode }
    fun lstat(path: String): StructStat {
        statCalls++
        check(statCalls < 10) { "Directory validation recursively invoked root authorization" }
        return StructStat(10048, (if (File(path).isDirectory) OsConstants.S_IFDIR else 0x8000) or (modes[path] ?: 0))
    }
}
