package de.mw136.tonuino.ui

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import de.mw136.tonuino.R

class UsbFileListActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_file_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.usb_list_title)

        val statusView = findViewById<TextView>(R.id.usb_file_list_text)
        statusView.text = getString(R.string.usb_list_loading)

        val uri = intent?.data
        if (uri == null) {
            statusView.text = getString(R.string.usb_list_no_uri)
            return
        }

        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null) {
            statusView.text = getString(R.string.usb_list_error)
            return
        }

        val files = listFilesRecursively(root)
        if (files.isEmpty()) {
            statusView.text = getString(R.string.main_usb_no_files)
            return
        }

        val message = buildString {
            appendLine(getString(R.string.main_usb_listing_prefix))
            files.forEach { appendLine(it) }
        }.trimEnd()

        statusView.text = message
        Log.i("UsbFileList", "Files on USB drive:\n$message")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun listFilesRecursively(node: DocumentFile, prefix: String = ""): List<String> {
        val collected = mutableListOf<String>()
        for (child in node.listFiles()) {
            val name = child.name ?: "(unnamed)"
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                collected.add("$path/")
                collected.addAll(listFilesRecursively(child, path))
            } else {
                collected.add(path)
            }
        }
        return collected
    }
}
