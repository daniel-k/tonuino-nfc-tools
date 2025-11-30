package de.mw136.tonuino.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import de.mw136.tonuino.R
import de.mw136.tonuino.nfc.NfcIntentActivity
import de.mw136.tonuino.ui.enter.TagData
import java.util.Locale
import kotlin.ExperimentalUnsignedTypes
import kotlin.math.roundToInt

@ExperimentalUnsignedTypes
class UsbFileListActivity : AppCompatActivity() {
    private val hiddenTopLevelFolderNames = setOf("advert", "mp3", "lost.dir")
    private val selectableFolderRange = 1..99

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

        val cachedFolders = UsbFolderCache.getCachedFolders(this, uri)
        val folders = cachedFolders ?: topLevelFolderSummaries(root).also { summaries ->
            UsbFolderCache.save(this, uri, summaries)
        }
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

    private fun parseSelectableFolderNumber(name: String): Int? {
        val numeric = name.trim().toIntOrNull() ?: return null
        return if (numeric in selectableFolderRange) numeric else null
    }

    private fun populateTable(table: TableLayout, folders: List<FolderSummary>) {
        // Keep the header row defined in XML, append folder rows below
        val albumArtSize = resources.getDimensionPixelSize(R.dimen.usb_album_art_size)
        val verticalSpacing = (albumArtSize * 0.2f).roundToInt().coerceAtLeast(1)
        val horizontalSpacing = resources.getDimensionPixelSize(R.dimen.usb_table_horizontal_spacing)
        val halfHorizontalSpacing = (horizontalSpacing / 2f).roundToInt().coerceAtLeast(0)

        applyHorizontalSpacingToHeader(table, halfHorizontalSpacing)
        for (summary in folders) {
            val row = TableRow(this).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { launchWriteActivity(summary.name) }
            }
            val artView = ImageView(this).apply {
                layoutParams = TableRow.LayoutParams(albumArtSize, albumArtSize).apply {
                    setMargins(halfHorizontalSpacing, 0, halfHorizontalSpacing, 0)
                }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                val artBytes = summary.albumArt
                if (artBytes != null) {
                    BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)?.let { bitmap ->
                        setImageBitmap(bitmap)
                    }
                }
            }
            val nameView = TextView(this).apply {
                text = summary.name
                layoutParams = TableRow.LayoutParams(
                    TableRow.LayoutParams.WRAP_CONTENT,
                    TableRow.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(halfHorizontalSpacing, 0, halfHorizontalSpacing, 0) }
            }
            val artistView = TextView(this).apply {
                text = summary.artist ?: getString(R.string.usb_list_unknown_artist)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(halfHorizontalSpacing, 0, halfHorizontalSpacing, 0)
                }
            }
            val albumView = TextView(this).apply {
                text = summary.album ?: getString(R.string.usb_list_unknown_album)
                layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(halfHorizontalSpacing, 0, halfHorizontalSpacing, 0)
                }
            }
            row.addView(nameView)
            row.addView(artView)
            row.addView(artistView)
            row.addView(albumView)

            val rowLayoutParams = TableLayout.LayoutParams(
                TableLayout.LayoutParams.MATCH_PARENT,
                TableLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, verticalSpacing, 0, verticalSpacing)
            }
            table.addView(row, rowLayoutParams)
        }
    }

    private fun applyHorizontalSpacingToHeader(table: TableLayout, halfSpacing: Int) {
        val headerRow = table.getChildAt(0) as? TableRow ?: return
        for (i in 0 until headerRow.childCount) {
            val child = headerRow.getChildAt(i)
            val existingParams = child.layoutParams
            val params = if (existingParams is TableRow.LayoutParams) {
                existingParams
            } else {
                TableRow.LayoutParams(existingParams)
            }
            params.setMargins(halfSpacing, params.topMargin, halfSpacing, params.bottomMargin)
            child.layoutParams = params
        }
    }

    private fun launchWriteActivity(folderName: String) {
        val folderNumber = parseSelectableFolderNumber(folderName) ?: return
        val tagData = TagData().apply { setFolder(folderNumber.toUByte()) }
        startActivity(Intent(this, EnterTagActivity::class.java).apply {
            putExtra(NfcIntentActivity.PARCEL_TAGDATA, tagData)
        })
    }

    private fun topLevelFolderSummaries(root: DocumentFile): List<FolderSummary> =
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val rawName = dir.name ?: return@mapNotNull null
                if (hiddenTopLevelFolderNames.contains(rawName.lowercase(Locale.ROOT))) return@mapNotNull null

                val folderNumber = parseSelectableFolderNumber(rawName) ?: return@mapNotNull null
                val metadata = summarizeFolderMp3Metadata(dir)
                FolderSummary(
                    name = folderNumber.toString().padStart(2, '0'),
                    artist = metadata.mostCommonArtist,
                    album = metadata.mostCommonAlbum,
                    albumArt = metadata.albumArt
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

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
