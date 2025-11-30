package de.mw136.tonuino.ui

import android.app.Activity
import android.content.Intent
import android.nfc.Tag
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import de.mw136.tonuino.BuildConfig
import de.mw136.tonuino.R
import de.mw136.tonuino.byteArrayToHex
import de.mw136.tonuino.nfc.NfcIntentActivity
import de.mw136.tonuino.nfc.readFromTag
import de.mw136.tonuino.ui.enter.TagData

@ExperimentalUnsignedTypes
class MainActivity : NfcIntentActivity() {
    override val TAG = "MainActivity"

    private var usedDocumentTreeFallback = false
    private val usbPermissionStore by lazy { UsbPermissionStore(this) }

    private val usbStoragePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleUsbStorageResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onResume() {
        super.onResume()

        val version = BuildConfig.VERSION_NAME + "#" + BuildConfig.VERSION_CODE
        Log.i(TAG, "Version ${BuildConfig.VERSION_NAME} build #${BuildConfig.VERSION_CODE}")
        supportActionBar?.title =
            "${getString(R.string.app_name)} ${getString(R.string.app_version, version)}"

        val errorContainer = findViewById<View>(R.id.error_container)
        val errorView = findViewById<TextView>(R.id.error_text)
        val openNfcSettingsButton = findViewById<View>(R.id.nfc_settings)
        val enabledContainer = findViewById<View>(R.id.enabled_container)

        if (nfcAdapter == null) {
            errorContainer.visibility = View.VISIBLE
            errorView.text = getString(R.string.main_nfcadapter_is_null)
            openNfcSettingsButton.visibility = View.GONE
            enabledContainer.visibility = View.GONE

        } else if (!nfcAdapter!!.isEnabled) {
            errorContainer.visibility = View.VISIBLE
            errorView.text = getString(R.string.main_nfcadapter_disabled)
            openNfcSettingsButton.visibility = View.VISIBLE
            enabledContainer.visibility = View.GONE

        } else {
            enabledContainer.visibility = View.VISIBLE
            errorContainer.visibility = View.GONE
        }

        if (BuildConfig.DEBUG) {
            enabledContainer.visibility = View.VISIBLE
        }
    }

    fun listUsbFiles(@Suppress("UNUSED_PARAMETER") view: View) {
        startUsbFlow(usePersistedUri = true)
    }

    fun pickUsbLocation(@Suppress("UNUSED_PARAMETER") view: View) {
        startUsbFlow(usePersistedUri = false)
    }

    private fun startUsbFlow(usePersistedUri: Boolean) {
        val statusView = findViewById<TextView>(R.id.usb_result_text)

        val persistedUri =
            if (usePersistedUri) usbPermissionStore.getPersistedUriIfReadable(contentResolver) else null
        if (usePersistedUri) {
            if (persistedUri != null) {
                statusView.text = getString(R.string.main_usb_using_saved_access)
                proceedWithUsbUri(persistedUri, statusView, rememberSelection = false)
                return
            } else if (usbPermissionStore.hasSavedUri()) {
                statusView.text = getString(R.string.main_usb_saved_access_invalid)
            }
        }

        val storageManager = getSystemService(StorageManager::class.java)
        usedDocumentTreeFallback = false

        val removableIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageManager?.storageVolumes?.firstOrNull { it.isRemovable }?.createAccessIntent(null)
        } else {
            null
        }?.let { withCommonFlags(it) }

        if (removableIntent != null) {
            usbStoragePicker.launch(removableIntent)
            return
        }

        statusView.text = getString(R.string.main_usb_not_found)
        usedDocumentTreeFallback = true
        usbStoragePicker.launch(buildDocumentTreeIntent())
    }

    private fun handleUsbStorageResult(result: ActivityResult) {
        val statusView = findViewById<TextView>(R.id.usb_result_text)
        val resultData = result.data
        val uri = resultData?.data

        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            if (!usedDocumentTreeFallback) {
                statusView.text = getString(R.string.main_usb_not_found)
                usedDocumentTreeFallback = true
                usbStoragePicker.launch(buildDocumentTreeIntent())
                return
            }

            if (uri == null) {
                statusView.text = getString(R.string.main_usb_picker_cancelled)
            } else {
                proceedWithUsbUri(uri, statusView, rememberSelection = false)
            }
            return
        }

        proceedWithUsbUri(uri, statusView, rememberSelection = true)
    }

    private fun proceedWithUsbUri(uri: Uri, statusView: TextView, rememberSelection: Boolean) {
        if (rememberSelection) {
            val takeFlags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                usbPermissionStore.rememberUri(uri)
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist USB permission: ${e.message}")
            }
        }

        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null || !root.canRead()) {
            statusView.text = getString(R.string.main_usb_open_failed)
            usbPermissionStore.clear()
            return
        }

        statusView.text = getString(R.string.usb_list_loading)
        Log.i(TAG, "Opening USB file list for uri=$uri")
        startActivity(Intent(this, UsbFileListActivity::class.java).apply {
            data = uri
        })
    }

    private fun buildDocumentTreeIntent(): Intent =
        withCommonFlags(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))

    private fun withCommonFlags(intent: Intent): Intent = intent.apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }

    fun showWriteActivity(view: View) {
//        startActivity(Intent(view.context, EditActivity::class.java))
        startActivity(Intent(view.context, EnterTagActivity::class.java))
    }

    fun showBulkWriteActivity(view: View) {
        startActivity(Intent(view.context, BulkWriteActivity::class.java))
    }

    @Suppress("UNUSED_PARAMETER")
    fun openNfcSettings(view: View) {
        startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
    }

    override fun onNfcTag(tag: Tag) {
        val bytes = readFromTag(tag)
        Log.d(TAG, "bytes: ${byteArrayToHex(bytes).joinToString(" ")}")
        if (bytes.isNotEmpty()) {
            val intent = Intent(this, ReadActivity::class.java).apply {
                putExtra(PARCEL_TAG, tag)
                putExtra(PARCEL_TAGDATA, TagData(bytes))
            }
            startActivity(intent)
        } else {
            showReadErrorModalDialog(tag)
        }
    }

}
