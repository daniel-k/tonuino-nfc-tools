package de.mw136.tonuino.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.nfc.Tag
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
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
    private var mediaReceiverRegistered = false
    private var usbAttachedReceiverRegistered = false
    private var storageVolumeCallbackRegistered = false
    private var lastUsbAttachHandledAt = 0L

    private val usbStoragePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleUsbStorageResult(result)
        }

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_MEDIA_MOUNTED -> handleExternalStorageAttached()
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val isMassStorage = device?.let {
                        (0 until it.interfaceCount).any { index ->
                            it.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                        }
                    } ?: false

                    if (isMassStorage) {
                        handleExternalStorageAttached()
                    }
                }
            }
        }
    }

    private val storageVolumeCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: android.os.storage.StorageVolume) {
                if (volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED) {
                    handleExternalStorageAttached(volume)
                }
            }
        }
    } else {
        null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onStart() {
        super.onStart()
        registerUsbAttachReceiver()
    }

    override fun onStop() {
        super.onStop()
        unregisterUsbAttachReceiver()
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

    private fun startUsbFlow(usePersistedUri: Boolean, overrideVolume: StorageVolume? = null) {
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
            val targetVolume = overrideVolume ?: storageManager?.storageVolumes?.firstOrNull { it.isRemovable }
            targetVolume?.createAccessIntent(null)
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

    private fun registerUsbAttachReceiver() {
        if (!mediaReceiverRegistered) {
            val mediaFilter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply {
                addDataScheme("file")
            }
            mediaReceiverRegistered = registerReceiverSafely(mediaFilter)
        }

        if (!usbAttachedReceiverRegistered) {
            usbAttachedReceiverRegistered = registerReceiverSafely(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        }

        if (!storageVolumeCallbackRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            storageVolumeCallback?.let { callback ->
                getSystemService(StorageManager::class.java)?.registerStorageVolumeCallback(mainExecutor, callback)
                storageVolumeCallbackRegistered = true
            }
        }
    }

    private fun unregisterUsbAttachReceiver() {
        if (mediaReceiverRegistered) {
            unregisterReceiverSafely()
            mediaReceiverRegistered = false
        }
        if (usbAttachedReceiverRegistered) {
            unregisterReceiverSafely()
            usbAttachedReceiverRegistered = false
        }
        if (storageVolumeCallbackRegistered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            storageVolumeCallback?.let { callback ->
                getSystemService(StorageManager::class.java)?.unregisterStorageVolumeCallback(callback)
            }
            storageVolumeCallbackRegistered = false
        }
    }

    private fun handleExternalStorageAttached(volume: StorageVolume? = null) {
        val mountedVolume = volume ?: getMountedRemovableVolume() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastUsbAttachHandledAt < 1_000) return
        lastUsbAttachHandledAt = now

        val persistedUri = usbPermissionStore.getPersistedUriIfReadable(contentResolver)
        val persistedVolumeId = persistedUri?.let { uri ->
            DocumentsContract.getTreeDocumentId(uri).substringBefore(":")
        }
        val attachedVolumeId = mountedVolume.uuid ?: if (mountedVolume.isPrimary) "primary" else null

        if (persistedUri != null && attachedVolumeId != null && persistedVolumeId != null && attachedVolumeId != persistedVolumeId) {
            showSwitchVolumeDialog(mountedVolume)
            return
        }

        startUsbFlow(usePersistedUri = true, overrideVolume = mountedVolume)
    }

    private fun hasMountedRemovableStorage(): Boolean {
        return getMountedRemovableVolume() != null
    }

    private fun getMountedRemovableVolume(): StorageVolume? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val storageManager = getSystemService(StorageManager::class.java) ?: return null
        return storageManager.storageVolumes.firstOrNull { volume ->
            volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED
        }
    }

    private fun showSwitchVolumeDialog(volume: StorageVolume) {
        AlertDialog.Builder(this)
            .setTitle(R.string.main_usb_new_drive_title)
            .setMessage(R.string.main_usb_new_drive_message)
            .setPositiveButton(R.string.main_usb_new_drive_use) { _, _ ->
                launchPickerForVolume(volume)
            }
            .setNegativeButton(R.string.main_usb_new_drive_keep) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun launchPickerForVolume(volume: StorageVolume) {
        usedDocumentTreeFallback = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val intent = volume.createAccessIntent(null)?.let { withCommonFlags(it) }
            if (intent != null) {
                usbStoragePicker.launch(intent)
                return
            }
        }

        usbStoragePicker.launch(buildDocumentTreeIntent())
    }

    private fun registerReceiverCompat(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun registerReceiverSafely(filter: IntentFilter): Boolean {
        return try {
            registerReceiverCompat(usbAttachReceiver, filter)
            true
        } catch (e: SecurityException) {
            val actions = filter.actionsIterator().asSequence().joinToString()
            Log.w(TAG, "Could not register USB receiver for action $actions: ${e.message}")
            false
        }
    }

    private fun unregisterReceiverSafely() {
        try {
            unregisterReceiver(usbAttachReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "USB receiver already unregistered: ${e.message}")
        }
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
