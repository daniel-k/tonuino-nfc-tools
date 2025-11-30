package de.mw136.tonuino.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.util.Locale

/**
 * In-memory cache of folder summaries for a single removable USB volume.
 * Lives for the process lifetime and is cleared when the drive is detached.
 */
object UsbFolderCache {
    private var cached: CacheEntry? = null

    fun getCachedFolders(context: Context, uri: Uri): List<FolderSummary>? {
        val treeId = uriTreeId(uri) ?: return null
        if (!isRemovableVolume(context, treeId)) return null

        val entry = cached
        return if (entry != null && entry.treeId == treeId) entry.folders else null
    }

    fun save(context: Context, uri: Uri, folders: List<FolderSummary>) {
        val treeId = uriTreeId(uri) ?: return
        if (!isRemovableVolume(context, treeId)) return

        cached = CacheEntry(treeId, folders)
    }

    fun clear() {
        cached = null
    }

    private fun uriTreeId(uri: Uri): String? = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun isRemovableVolume(context: Context, treeId: String): Boolean {
        val volumeId = treeId.substringBefore(":")
        val storageManager = context.getSystemService(StorageManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && storageManager != null) {
            val volume = storageManager.storageVolumes.firstOrNull { volume ->
                val idMatches = volume.uuid == volumeId || (volumeId == "primary" && volume.isPrimary)
                idMatches
            }
            if (volume != null) {
                return volume.isRemovable
            }
        }

        return volumeId.lowercase(Locale.ROOT) != "primary"
    }

    private data class CacheEntry(val treeId: String, val folders: List<FolderSummary>)
}
