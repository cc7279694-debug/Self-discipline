package com.guanyi.mirra.data.backup

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Host filesystem adapter: Windows File.renameTo cannot replace an existing destination. */
internal fun jvmDurableFiles() = DurableFiles(syncDirectory = {}, atomicReplace = { source, target ->
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
})
