package com.guanyi.mirra

import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID

/** Test-owned files only; nesting under camera keeps the real FileProvider contract unchanged. */
class TestImageStorageSandbox(targetContext: Context) : AutoCloseable {
    private val parent = targetContext.filesDir.canonicalFile
        .resolve("image-work/camera/mirra-test-sandboxes")
    val root: File = parent.resolve(UUID.randomUUID().toString())
    val context: Context = object : ContextWrapper(targetContext) {
        override fun getFilesDir(): File = root
        override fun getApplicationContext(): Context = this
    }

    init {
        requireOwnedRoot(root, parent)
        // FileProvider caches its strategy per authority. Initialize the unchanged production
        // path with the native context, not the first sandbox wrapper. The probe is never written.
        val nativeContext = InstrumentationRegistry.getInstrumentation().targetContext
        require(targetContext.packageName == nativeContext.packageName)
        FileProvider.getUriForFile(nativeContext, "${nativeContext.packageName}.fileprovider",
            nativeContext.filesDir.resolve("image-work/camera/mirra-provider-root-probe.jpg"))
        check(root.mkdirs()) { "Cannot create test image sandbox" }
    }

    fun resolve(relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute)
        val file = root.resolve(relativePath).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) {
            "Test image path must stay inside its sandbox"
        }
        return file
    }

    override fun close() {
        requireOwnedRoot(root, parent)
        check(!root.exists() || root.deleteRecursively()) { "Cannot remove owned test image sandbox" }
    }

    internal companion object {
        fun requireOwnedRoot(root: File, parent: File) {
            // Reject symlink redirection as well as parent/sibling/outside paths before deletion.
            require(parent.canonicalFile == parent.absoluteFile && root.canonicalFile == root.absoluteFile)
            require(root.canonicalPath.startsWith(parent.canonicalPath + File.separator)) {
                "Refusing cleanup outside the owned image sandbox subtree"
            }
        }
    }
}
