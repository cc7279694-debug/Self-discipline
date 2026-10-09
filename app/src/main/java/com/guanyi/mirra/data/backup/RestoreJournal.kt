package com.guanyi.mirra.data.backup

import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class RestoreResources(val database: File, val preferences: File, val images: File)
class RestoreSafetyException(message: String, cause: Throwable? = null) : IOException(message, cause)
/** Only emitted after the entire old generation and its durable rollback were verified. */
class RestoreRolledBackException(cause: Throwable) : IOException("Restore failed; complete old generation verified", cause)
/** Every write/rename reports failure. Unlike best-effort AtomicFile, sync errors propagate. */
class DurableFiles(val syncDirectory: (File) -> Unit,
    val atomicReplace: (File, File) -> Unit = { source, target ->
        if (!source.renameTo(target)) throw IOException("Atomic replacement failed")
    },
) {
    fun directory(file: File) {
        if (!file.exists()) {
            val parent = file.parentFile ?: throw IOException("Missing directory parent")
            directory(parent)
            if (!file.mkdir()) throw IOException("Cannot create directory")
            syncDirectory(parent)
        }
        if (!file.isDirectory || !isDirectEntry(file)) throw IOException("Unsafe directory")
    }
    fun write(file: File, bytes: ByteArray) {
        directory(checkNotNull(file.parentFile))
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
        syncDirectory(checkNotNull(file.parentFile))
    }
    fun copy(source: File, target: File) {
        if (!isDirectEntry(source)) throw IOException("Symbolic entries are not supported")
        if (source.isDirectory) {
            directory(target)
            (source.listFiles() ?: throw IOException("Cannot enumerate directory")).sortedBy { it.name }.forEach { copy(it, File(target, it.name)) }
            syncDirectory(target)
        } else if (source.isFile) {
            directory(checkNotNull(target.parentFile))
            FileInputStream(source).use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.fd.sync() } }
            syncDirectory(checkNotNull(target.parentFile))
        } else throw IOException("Missing resource")
    }
    fun move(source: File, target: File) {
        directory(checkNotNull(target.parentFile))
        if (target.exists() || !source.renameTo(target)) throw IOException("Resource rename failed")
        syncDirectory(checkNotNull(source.parentFile))
        if (source.parentFile != target.parentFile) syncDirectory(checkNotNull(target.parentFile))
    }
    fun deleteOwned(file: File, allowedRoot: File) {
        val target = file.canonicalFile
        val base = allowedRoot.canonicalFile
        require(target != base && target.path.startsWith(base.path + File.separator)) { "Deletion outside operation" }
        if (!isDirectEntry(file)) throw IOException("Unsafe cleanup path")
        if (file.isDirectory) (file.listFiles() ?: throw IOException("Cannot enumerate cleanup")).forEach { deleteOwned(it, allowedRoot) }
        if (file.exists() && !file.delete()) throw IOException("Cleanup failed")
    }
    private fun isDirectEntry(file: File): Boolean {
        val parent = file.absoluteFile.parentFile ?: return file.canonicalFile == file.absoluteFile
        // Android Context paths may have OS-owned ancestor aliases (/data/user/0).
        // Do not confuse those with a symbolic entry inside our private operation tree.
        return File(parent.canonicalFile, file.name) == file.canonicalFile
    }
}
class RestoreJournal(
    private val root: File,
    private val live: RestoreResources,
    private val files: DurableFiles,
    private val verify: (RestoreResources) -> Unit = {},
    private val effect: (String) -> Unit = {},
) {
    class Plan internal constructor(internal val id: String)
    private val pointer = File(root, "restore.journal")
    private enum class State { PREPARED, SWITCHING, COMMITTED, CLEANUP_PENDING, ROLLING_BACK, ROLLED_BACK }
    private data class Record(val id: String, val state: State, val old: String, val new: String, val sequence: Long = 0)
    private fun RestoreResources.members() = listOf("database" to database, "preferences" to preferences, "images" to images)
    private fun resources(folder: File) = RestoreResources(File(folder, "database.sqlite"), File(folder, "preferences.preferences_pb"), File(folder, "images"))

    /** Caller has already drained writers and CLOSED Room/DataStore and reader owners. */
    @Synchronized fun prepare(candidate: RestoreResources): Plan {
        if (hasPendingRecovery()) throw RestoreSafetyException("Unresolved restore already exists")
        require(candidate.database.isFile && candidate.images.isDirectory)
        files.directory(root)
        val id = UUID.randomUUID().toString()
        val operation = File(root, id)
        files.directory(operation)
        val old = resources(File(operation, "old"))
        val next = resources(File(operation, "new"))
        copyResources(live, old)
        copyResources(candidate, next)
        val oldFingerprint = fingerprint(old)
        val newFingerprint = fingerprint(next)
        require(fingerprint(live) == oldFingerprint && fingerprint(candidate) == newFingerprint) { "Snapshot changed while capturing" }
        verify(next)
        writeRecord(Record(id, State.PREPARED, oldFingerprint, newFingerprint))
        effect("PREPARED")
        return Plan(id)
    }

    @Synchronized fun apply(plan: Plan) {
        var record = readRecord() ?: throw RestoreSafetyException("Restore journal missing")
        require(record.id == plan.id && record.state == State.PREPARED)
        try {
            assertFingerprint(live, record.old)
            val next = resources(File(File(root, record.id), "new"))
            assertFingerprint(next, record.new)
            record = record.copy(state = State.SWITCHING)
            writeRecord(record)
            effect("SWITCHING")
            publish(next, record.id)
            assertFingerprint(live, record.new)
            verify(live)
            effect("VERIFIED")
            record = record.copy(state = State.COMMITTED)
            writeRecord(record)
            effect("COMMITTED")
        } catch (failure: Exception) {
            // A write may have succeeded before its sync reported failure. Re-read the decision;
            // never blindly undo a durable/possibly durable COMMITTED generation.
            val actual = try { readRecord() } catch (uncertain: Exception) {
                throw RestoreSafetyException("Restore decision is uncertain; stores remain closed", uncertain)
            } ?: throw RestoreSafetyException("Restore decision disappeared", failure)
            if (actual.state == State.COMMITTED || actual.state == State.CLEANUP_PENDING) {
                throw RestoreSafetyException("Commit durability requires startup recovery", failure)
            }
            try { rollback(actual) } catch (rollbackFailure: Exception) {
                rollbackFailure.addSuppressed(failure)
                throw RestoreSafetyException("Rollback could not be proven; stores remain closed", rollbackFailure)
            }
            throw RestoreRolledBackException(failure)
        }
        cleanup(record)
    }

    /** Invoked before constructing Room, DataStore, image cleanup, providers or runtime owners. */
    @Synchronized fun recoverBeforeOpeningStores() {
        val record = readRecord() ?: return
        try {
            when (record.state) {
                State.COMMITTED, State.CLEANUP_PENDING -> { assertFingerprint(live, record.new); verify(live); cleanup(record) }
                State.ROLLED_BACK -> { assertFingerprint(live, record.old); cleanup(record) }
                else -> rollback(record)
            }
        } catch (failure: Exception) {
            throw RestoreSafetyException("Restore recovery is unresolved; stores must stay closed", failure)
        }
    }

    private fun rollback(record: Record) {
        val old = resources(File(File(root, record.id), "old"))
        // Prove the ENTIRE safety snapshot exists before touching even one current resource.
        assertFingerprint(old, record.old)
        writeRecord(record.copy(state = State.ROLLING_BACK))
        publish(old, record.id)
        assertFingerprint(live, record.old)
        val restored = record.copy(state = State.ROLLED_BACK)
        writeRecord(restored)
        effect("ROLLED_BACK")
        cleanup(restored)
    }

    private fun publish(source: RestoreResources, id: String) {
        val operation = File(root, id)
        val attempt = File(operation, "publish-${UUID.randomUUID()}")
        files.directory(attempt)
        val staged = resources(File(attempt, "staged"))
        copyResources(source, staged) // Safety/candidate snapshots are never consumed by rename.
        source.members().zip(live.members()).zip(staged.members()).forEach { (pair, stagedMember) ->
            val (sourceMember, targetMember) = pair
            val (name, target) = targetMember
            if (target.exists()) files.move(target, File(attempt, "displaced-$name"))
            effect("$name:removed")
            if (sourceMember.second.exists()) files.move(stagedMember.second, target)
            effect("$name:published")
        }
    }

    private fun cleanup(record: Record) {
        if (record.state != State.ROLLED_BACK) {
            writeRecord(record.copy(state = State.CLEANUP_PENDING))
            effect("CLEANUP_PENDING")
        }
        // Clear and sync the pointer BEFORE deleting payloads. A crash leaves only harmless
        // private orphans, never a live journal whose required snapshot has been deleted.
        if (!pointer.delete()) throw IOException("Cannot clear restore journal")
        files.syncDirectory(root)
        try { files.deleteOwned(File(root, record.id), root); files.syncDirectory(root) }
        catch (_: IOException) { /* Selected live generation is complete; retained private cleanup is harmless. */ }
    }

    private fun copyResources(from: RestoreResources, to: RestoreResources) {
        files.directory(checkNotNull(to.database.parentFile))
        from.members().zip(to.members()).forEach { (source, target) -> if (source.second.exists()) files.copy(source.second, target.second) }
    }

    private fun fingerprint(resources: RestoreResources): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0) }
        fun visit(file: File, path: String) {
            if (file.absoluteFile.parentFile?.let { File(it.canonicalFile, file.name) } != file.canonicalFile)
                throw IOException("Symbolic resource entry")
            update(path)
            when {
                !file.exists() -> update("absent")
                file.isDirectory -> {
                    update("directory")
                    (file.listFiles() ?: throw IOException("Unreadable resource directory")).sortedBy { it.name }.forEach { visit(it, "$path/${it.name}") }
                }
                file.isFile -> {
                    update("file:${file.length()}")
                    FileInputStream(file).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    }
                }
                else -> throw IOException("Unsupported resource kind")
            }
        }
        resources.members().forEach { (name, file) -> visit(file, name) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun assertFingerprint(resources: RestoreResources, expected: String) {
        if (fingerprint(resources) != expected) throw RestoreSafetyException("Resource generation hash mismatch")
    }

    private fun writeRecord(record: Record) {
        val prior = readRecord()
        check(prior == null || prior.id == record.id) { "Foreign restore decision" }
        val next = record.copy(sequence = Math.addExact(prior?.sequence ?: 0, 1))
        val properties = Properties().apply {
            setProperty("version", "1"); setProperty("id", next.id); setProperty("state", next.state.name)
            setProperty("old", next.old); setProperty("new", next.new)
            setProperty("sequence", next.sequence.toString()); setProperty("checksum", decisionChecksum(next))
        }
        val output = java.io.ByteArrayOutputStream().also { properties.store(it, null) }.toByteArray()
        val temporary = File(root, "restore.journal.tmp")
        files.write(temporary, output)
        files.atomicReplace(temporary, pointer)
        files.syncDirectory(root)
    }
    private fun readRecord(): Record? {
        if (!pointer.exists()) return null
        try {
            require(pointer.isFile && pointer.length() in 1..4096 && File(root.canonicalFile, pointer.name) == pointer.canonicalFile)
            val values = Properties().apply { pointer.inputStream().use { load(it) } }
            require(values.keys == setOf("version", "id", "state", "old", "new", "sequence", "checksum") && values.getProperty("version") == "1")
            val id = values.getProperty("id")
            require(UUID.fromString(id).toString() == id)
            val old = values.getProperty("old"); val new = values.getProperty("new")
            require(old.matches(Regex("[0-9a-f]{64}")) && new.matches(Regex("[0-9a-f]{64}")))
            val sequence = values.getProperty("sequence").toLong()
            require(sequence > 0)
            val record = Record(id, State.valueOf(values.getProperty("state")), old, new, sequence)
            require(values.getProperty("checksum") == decisionChecksum(record)) { "Restore decision checksum mismatch" }
            return record
        } catch (failure: Exception) { throw RestoreSafetyException("Invalid restore journal", failure) }
    }
    private fun decisionChecksum(record: Record): String {
        val canonical = "1\n${record.id}\n${record.sequence}\n${record.state.name}\n${record.old}\n${record.new}\n"
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    fun hasPendingRecovery(): Boolean = File(root, "restore.journal").exists()
}
