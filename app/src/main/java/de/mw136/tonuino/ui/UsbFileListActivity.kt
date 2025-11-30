package de.mw136.tonuino.ui

import android.os.Bundle
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.util.Locale
import de.mw136.tonuino.R

class UsbFileListActivity : AppCompatActivity() {
    private val hiddenTopLevelFolderNames = setOf("advert", "mp3", "lost.dir")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_file_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.usb_list_title)

        val statusView = findViewById<TextView>(R.id.usb_file_list_status)
        val table = findViewById<TableLayout>(R.id.usb_file_table)
        statusView.text = getString(R.string.usb_list_loading)

        val uri = intent?.data
        if (uri == null) {
            statusView.text = getString(R.string.usb_list_no_uri)
            table.visibility = View.GONE
            return
        }

        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null) {
            statusView.text = getString(R.string.usb_list_error)
            table.visibility = View.GONE
            return
        }

        val folders = topLevelFolderSummaries(root)
        if (folders.isEmpty()) {
            statusView.text = getString(R.string.usb_list_no_folders)
            table.visibility = View.GONE
            return
        }

        statusView.visibility = View.GONE
        populateTable(table, folders)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun populateTable(table: TableLayout, folders: List<FolderSummary>) {
        // Keep the header row defined in XML, append folder rows below
        for (summary in folders) {
            val row = TableRow(this)
            val nameView = TextView(this).apply {
                text = summary.name
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }
            val countView = TextView(this).apply {
                text = summary.fileCount.toString()
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
                textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            }
            row.addView(nameView)
            row.addView(countView)
            table.addView(row)
        }
    }

    private fun topLevelFolderSummaries(root: DocumentFile): List<FolderSummary> =
        root.listFiles()
            .filter { it.isDirectory }
            .filterNot { dir ->
                val name = dir.name ?: return@filterNot false
                hiddenTopLevelFolderNames.contains(name.lowercase(Locale.ROOT))
            }
            .map { dir ->
                val name = dir.name ?: getString(R.string.usb_folder_unknown_name)
                FolderSummary(name, countFiles(dir))
            }
            .sortedBy { it.name.lowercase() }

    private fun countFiles(folder: DocumentFile): Int {
        var count = 0
        for (child in folder.listFiles()) {
            if (child.isDirectory) {
                count += countFiles(child)
            } else {
                count += 1
            }
        }
        return count
    }
}

data class FolderSummary(val name: String, val fileCount: Int)
