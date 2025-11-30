package de.mw136.tonuino.ui

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import de.mw136.tonuino.R
import java.util.Locale

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
            val artistView = TextView(this).apply {
                text = summary.artist ?: getString(R.string.usb_list_unknown_artist)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }
            val albumView = TextView(this).apply {
                text = summary.album ?: getString(R.string.usb_list_unknown_album)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)
            row.addView(countView)
            row.addView(artistView)
            row.addView(albumView)
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
                val metadata = summarizeFolderMp3Metadata(dir)
                FolderSummary(
                    name = name,
                    fileCount = countFiles(dir),
                    artist = metadata.mostCommonArtist,
                    album = metadata.mostCommonAlbum,
                    albumArt = metadata.albumArt
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

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

    private fun summarizeFolderMp3Metadata(folder: DocumentFile): FolderMetadataSummary {
        val artistCounts = mutableMapOf<String, Int>()
        val albumCounts = mutableMapOf<String, Int>()
        var albumArt: ByteArray? = null

        fun traverse(doc: DocumentFile) {
            if (doc.isDirectory) {
                doc.listFiles().forEach { traverse(it) }
                return
            }

            if (doc.name?.endsWith(".mp3", ignoreCase = true) == true) {
                val metadata = readMp3Metadata(doc)
                metadata.artist?.let { artist ->
                    artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
                }
                metadata.album?.let { album ->
                    albumCounts[album] = (albumCounts[album] ?: 0) + 1
                }
                if (albumArt == null && metadata.albumArt != null) {
                    albumArt = metadata.albumArt
                }
            }
        }

        traverse(folder)

        return FolderMetadataSummary(
            mostCommonArtist = mostCommon(artistCounts),
            mostCommonAlbum = mostCommon(albumCounts),
            albumArt = albumArt
        )
    }

    private fun readMp3Metadata(file: DocumentFile): Mp3Metadata {
        var artist: String? = null
        var album: String? = null
        var albumArt: ByteArray? = null

        try {
            contentResolver.openFileDescriptor(file.uri, "r")?.use { fd ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(fd.fileDescriptor)
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
                        .takeIf { it.isNotEmpty() }
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
                        .takeIf { it.isNotEmpty() }
                    albumArt = retriever.embeddedPicture
                } finally {
                    retriever.release()
                }
            }
        } catch (_: Exception) {
            // Ignore unreadable files; treat as missing metadata.
        }

        return Mp3Metadata(artist, album, albumArt)
    }

    private fun mostCommon(counts: Map<String, Int>): String? {
        if (counts.isEmpty()) return null
        return counts
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key.lowercase(Locale.ROOT) })
            ?.key
    }
}

data class FolderSummary(
    val name: String,
    val fileCount: Int,
    val artist: String?,
    val album: String?,
    val albumArt: ByteArray?
)

data class FolderMetadataSummary(
    val mostCommonArtist: String?,
    val mostCommonAlbum: String?,
    val albumArt: ByteArray?
)

data class Mp3Metadata(val artist: String?, val album: String?, val albumArt: ByteArray?)
