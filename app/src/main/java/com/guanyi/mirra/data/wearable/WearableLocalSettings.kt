package com.guanyi.mirra.data.wearable

import android.content.Context
import android.util.AtomicFile
import com.guanyi.mirra.domain.wearable.WearableSettingsStore
import java.io.File
import kotlinx.serialization.json.*
import com.guanyi.mirra.domain.wearable.WearableCatalogProtocol

/** A device-local preference. It is never one of Full Backup v1's four portable settings. */
class WearableLocalSettings(context: Context) : WearableSettingsStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "wearable-companion-enabled"))
    private val controlFile = AtomicFile(File(context.noBackupFilesDir, "wearable-control-enabled"))
    private val catalogFile = AtomicFile(File(context.noBackupFilesDir, "wearable-catalog-enabled"))
    private val catalogSelectionFile = AtomicFile(File(context.noBackupFilesDir, "wearable-catalog-books-v1"))
    @Volatile private var selected = try { file.openRead().use { it.read() == 1 } } catch (_: Exception) { false }
    @Volatile private var controlSelected = try { controlFile.openRead().use { it.readBytes().contentEquals(byteArrayOf(1)) } } catch (_: Exception) { false }
    @Volatile private var catalogSelected = try { catalogFile.openRead().use { it.readBytes().contentEquals(byteArrayOf(1)) } } catch (_: Exception) { false }
    @Volatile private var bookIds: List<String> = try {
        catalogSelectionFile.openRead().use { input ->
            val bytes = input.readBytes().also { require(it.size <= 2_048) }
            val values = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.map {
                require(it.jsonPrimitive.isString); it.jsonPrimitive.content
            }
            validateIds(values); values
        }
    } catch (_: Exception) { emptyList() }
    override val enabled get() = selected
    override val controlEnabled get() = controlSelected
    override val catalogEnabled get() = catalogSelected
    override val catalogSelectedIds get() = bookIds.toList()
    @Synchronized override fun setCatalogEnabled(value: Boolean) {
        if (!value) catalogSelected = false
        val output = catalogFile.startWrite()
        try { output.write(if (value) 1 else 0); catalogFile.finishWrite(output); catalogSelected = value }
        catch (failure: Exception) { catalogFile.failWrite(output); throw failure }
    }
    @Synchronized override fun setCatalogSelectedIds(value: List<String>) {
        validateIds(value)
        val bytes = buildJsonArray { value.sorted().forEach(::add) }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_048)
        val output = catalogSelectionFile.startWrite()
        try { output.write(bytes); catalogSelectionFile.finishWrite(output); bookIds = value.sorted().toList() }
        catch (failure: Exception) { catalogSelectionFile.failWrite(output); throw failure }
    }
    private fun validateIds(values: List<String>) {
        require(values.size <= WearableCatalogProtocol.MAX_BOOKS - 1 && values.distinct().size == values.size &&
            values.all(WearableCatalogProtocol::validBookId))
    }
    @Synchronized override fun setControlEnabled(value: Boolean) {
        // Disabling remains fail-closed even when persistence itself fails.
        if (!value) controlSelected = false
        val output = controlFile.startWrite()
        try { output.write(if (value) 1 else 0); controlFile.finishWrite(output); controlSelected = value }
        catch (failure: Exception) { controlFile.failWrite(output); throw failure }
    }
    @Synchronized override fun setEnabled(value: Boolean) {
        val output = file.startWrite()
        try { output.write(if (value) 1 else 0); file.finishWrite(output); selected = value }
        catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}
