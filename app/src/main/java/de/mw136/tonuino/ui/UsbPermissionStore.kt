package de.mw136.tonuino.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * Remembers the last USB document tree URI and validates that we still hold a persisted permission.
 */
class UsbPermissionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun rememberUri(uri: Uri) {
        prefs.edit().putString(KEY_URI, uri.toString()).apply()
    }

    fun hasSavedUri(): Boolean = prefs.contains(KEY_URI)

    fun getPersistedUriIfReadable(contentResolver: ContentResolver): Uri? {
        val raw = prefs.getString(KEY_URI, null) ?: return null
        val uri = Uri.parse(raw)

        val permission = contentResolver.persistedUriPermissions.firstOrNull {
            it.uri == uri && it.isReadPermission
        }
        if (permission == null) {
            Log.i(TAG, "Persisted permission for $uri not found; clearing saved URI")
            clear()
            return null
        }

        val doc = DocumentFile.fromTreeUri(context, uri)
        if (doc == null || !doc.canRead()) {
            Log.i(TAG, "Saved USB location $uri is not readable anymore; clearing saved URI")
            clear()
            return null
        }

        return uri
    }

    fun clear() {
        prefs.edit().remove(KEY_URI).apply()
    }

    private companion object {
        const val PREF_NAME = "usb_permissions"
        const val KEY_URI = "last_usb_uri"
        const val TAG = "UsbPermissionStore"
    }
}
