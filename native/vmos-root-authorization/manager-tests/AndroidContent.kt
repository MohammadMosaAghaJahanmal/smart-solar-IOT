package android.content

import java.io.File

open class Context(val filesDir: File) {
    val packageManager = android.content.pm.PackageManager()
    fun checkSelfPermission(permission: String): Int = -1
    fun startActivity(intent: Intent) = Unit
}

class Intent {
    fun addFlags(flags: Int): Intent = this
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 0x10000000 }
}
