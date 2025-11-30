package de.mw136.tonuino.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.documentfile.provider.DocumentFile
import de.mw136.tonuino.R
import de.mw136.tonuino.nfc.NfcIntentActivity
import de.mw136.tonuino.ui.Format1Mode
import de.mw136.tonuino.ui.enter.TagData
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.ExperimentalUnsignedTypes

@ExperimentalUnsignedTypes
class UsbFileListActivity : AppCompatActivity() {
    private val hiddenTopLevelFolderNames = setOf("advert", "mp3", "lost.dir")
    private val selectableFolderRange = 1..99
    private val scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var progressDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usb_file_list)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.usb_list_title)

        val statusView = findViewById<TextView>(R.id.usb_file_list_status)
        val listView = findViewById<ListView>(R.id.usb_folder_list)

        val uri = intent?.data
        if (uri == null) {
            statusView.text = getString(R.string.usb_list_no_uri)
            listView.visibility = View.GONE
            return
        }

        val root = DocumentFile.fromTreeUri(this, uri)
        if (root == null) {
            statusView.text = getString(R.string.usb_list_error)
            listView.visibility = View.GONE
            return
        }

        val cachedFolders = UsbFolderCache.getCachedFolders(this, uri)
        if (cachedFolders != null) {
            showFolderSummaries(statusView, listView, cachedFolders)
            return
        }

        statusView.text = getString(R.string.usb_list_loading)
        scanFoldersAsync(root, uri, listView, statusView)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanExecutor.shutdownNow()
        dismissProgressDialog()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun parseSelectableFolderNumber(name: String): Int? {
        val numeric = name.trim().toIntOrNull() ?: return null
        return if (numeric in selectableFolderRange) numeric else null
    }

    private fun launchWriteActivity(summary: FolderSummary) {
        val folderNumber = parseSelectableFolderNumber(summary.name) ?: return
        val modeValue = if (summary.albumDominant) {
            Format1Mode.AudioBookMultiple.value
        } else {
            // For mixed albums fall back to simple album playback.
            Format1Mode.Album.value
        }
        val tagData = TagData().apply {
            setFolder(folderNumber.toUByte())
            setMode(modeValue.toUByte())
        }
        startActivity(Intent(this, EnterTagActivity::class.java).apply {
            putExtra(NfcIntentActivity.PARCEL_TAGDATA, tagData)
        })
    }

    private fun scanFoldersAsync(
        root: DocumentFile,
        uri: Uri,
        listView: ListView,
        statusView: TextView
    ) {
        showProgressDialog()
        updateScanProgress(0, 0)
        scanExecutor.execute {
            val folders = collectTopLevelFolders(root)
            val totalMp3Files = countEligibleMp3Files(folders)
            updateScanProgress(0, totalMp3Files)

            val summaries = buildFolderSummaries(folders, totalMp3Files) { processed, total ->
                updateScanProgress(processed, total)
            }
            UsbFolderCache.save(this, uri, summaries)

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                dismissProgressDialog()
                showFolderSummaries(statusView, listView, summaries)
            }
        }
    }

    private fun showFolderSummaries(
        statusView: TextView,
        listView: ListView,
        folders: List<FolderSummary>
    ) {
        if (folders.isEmpty()) {
            statusView.text = getString(R.string.usb_list_no_folders)
            listView.visibility = View.GONE
        } else {
            statusView.visibility = View.GONE
            listView.visibility = View.VISIBLE
            listView.adapter = FolderListAdapter(folders)
            listView.setOnItemClickListener { _, _, position, _ ->
                if (position in folders.indices) {
                    launchWriteActivity(folders[position])
                }
            }
        }
    }

    private inner class FolderListAdapter(private val items: List<FolderSummary>) : BaseAdapter() {
        private val inflater: LayoutInflater = layoutInflater
        private val accentColor: Int = ContextCompat.getColor(this@UsbFileListActivity, R.color.colorAccent)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): FolderSummary = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.list_item_usb_folder, parent, false)
            val artView = view.findViewById<ImageView>(R.id.usb_folder_album_art)
            val titleView = view.findViewById<TextView>(R.id.usb_folder_title)
            val artistView = view.findViewById<TextView>(R.id.usb_folder_artist)
            val chevronView = view.findViewById<ImageView>(R.id.usb_folder_chevron)

            val item = getItem(position)
            if (item.albumDominant) {
                artistView.visibility = View.VISIBLE

                val albumText = item.album ?: getString(R.string.usb_list_unknown_album)
                val artistText = item.artist ?: getString(R.string.usb_list_unknown_artist)

                titleView.text = getString(R.string.usb_folder_title_format, item.name, albumText)
                artistView.text = artistText

                val artBytes = item.albumArt
                if (artBytes != null) {
                    val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    if (bitmap != null) {
                        artView.setImageBitmap(bitmap)
                    } else {
                        artView.setImageDrawable(null)
                    }
                } else {
                    artView.setImageDrawable(null)
                }
            } else {
                val isEmpty = item.trackCount == 0
                val trackLabel = if (isEmpty) {
                    getString(R.string.usb_list_empty_folder)
                } else {
                    getString(R.string.usb_list_track_count, item.trackCount)
                }
                titleView.text = getString(R.string.usb_folder_title_format, item.name, trackLabel)
                if (isEmpty) {
                    artistView.text = ""
                    artistView.visibility = View.GONE
                } else {
                    artistView.text = buildTrackPreview(item.trackTitles, item.trackCount)
                    artistView.visibility = View.VISIBLE
                }
                artView.setImageDrawable(null)
            }

            chevronView?.let {
                ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(accentColor))
            }

            return view
        }

        private fun buildTrackPreview(titles: List<String>, totalCount: Int): String {
            if (titles.isEmpty()) return getString(R.string.usb_list_unknown_artist)
            val shortened = titles.take(3).map { shortenTitle(it) }
            val base = shortened.joinToString(", ")
            return if (totalCount > shortened.size) "$base, ..." else base
        }

        private fun shortenTitle(title: String, maxLen: Int = 22): String {
            if (title.length <= maxLen) return title
            return title.take(maxLen - 3).trimEnd() + "..."
        }
    }

    private fun collectTopLevelFolders(root: DocumentFile): List<Pair<String, DocumentFile>> =
        root.listFiles()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                val rawName = dir.name ?: return@mapNotNull null
                if (hiddenTopLevelFolderNames.contains(rawName.lowercase(Locale.ROOT))) return@mapNotNull null

                val folderNumber = parseSelectableFolderNumber(rawName) ?: return@mapNotNull null
                folderNumber.toString().padStart(2, '0') to dir
            }
            .sortedBy { it.first.lowercase(Locale.ROOT) }

    private fun countEligibleMp3Files(folders: List<Pair<String, DocumentFile>>): Int {
        var total = 0

        fun traverse(doc: DocumentFile) {
            if (doc.isDirectory) {
                doc.listFiles().forEach { traverse(it) }
            } else if (doc.name?.endsWith(".mp3", ignoreCase = true) == true) {
                total++
            }
        }

        folders.forEach { (_, dir) -> traverse(dir) }
        return total
    }

    private fun buildFolderSummaries(
        folders: List<Pair<String, DocumentFile>>,
        totalMp3Files: Int,
        onFileProcessed: (processed: Int, total: Int) -> Unit
    ): List<FolderSummary> {
        var processed = 0
        return folders.map { (name, dir) ->
            val metadata = summarizeFolderMp3Metadata(dir) {
                processed++
                onFileProcessed(processed, totalMp3Files)
            }
            val dominantAlbum = metadata.hasDominantAlbum()
            FolderSummary(
                name = name,
                artist = metadata.mostCommonArtist,
                album = metadata.mostCommonAlbum,
                albumArt = if (dominantAlbum) metadata.albumArt else null,
                albumDominant = dominantAlbum,
                trackCount = metadata.trackCount,
                trackTitles = metadata.trackTitles
            )
        }
    }

    private fun summarizeFolderMp3Metadata(
        folder: DocumentFile,
        onMp3Processed: () -> Unit
    ): FolderMetadataSummary {
        val artistCounts = mutableMapOf<String, Int>()
        val albumCounts = mutableMapOf<String, Int>()
        var albumArt: ByteArray? = null
        val trackTitles = mutableListOf<String>()
        var trackCount = 0

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
                metadata.title?.let { title ->
                    if (trackTitles.size < 3) trackTitles.add(title)
                }
                if (albumArt == null && metadata.albumArt != null) {
                    albumArt = metadata.albumArt
                }
                trackCount++
                onMp3Processed()
            }
        }

        traverse(folder)

        return FolderMetadataSummary(
            mostCommonArtist = mostCommon(artistCounts),
            mostCommonAlbum = mostCommon(albumCounts),
            albumArt = albumArt,
            trackCount = trackCount,
            trackTitles = trackTitles.toList(),
            mostCommonAlbumCount = albumCounts.maxOfOrNull { it.value } ?: 0
        )
    }

    private fun readMp3Metadata(file: DocumentFile): Mp3Metadata {
        var artist: String? = null
        var album: String? = null
        var albumArt: ByteArray? = null
        var title: String? = null

        try {
            contentResolver.openFileDescriptor(file.uri, "r")?.use { fd ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(fd.fileDescriptor)
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
                        .takeIf { it.isNotEmpty() }
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
                        .takeIf { it.isNotEmpty() }
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                        .takeIf { it.isNotEmpty() }
                    albumArt = retriever.embeddedPicture
                } finally {
                    retriever.release()
                }
            }
        } catch (_: Exception) {
            // Ignore unreadable files; treat as missing metadata.
        }

        if (title == null) {
            val rawName = file.name
            if (rawName != null) {
                title = rawName.substringBeforeLast('.', rawName)
            }
        }

        return Mp3Metadata(artist, album, albumArt, title)
    }

    private fun mostCommon(counts: Map<String, Int>): String? {
        if (counts.isEmpty()) return null
        return counts
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key.lowercase(Locale.ROOT) })
            ?.key
    }

    private fun showProgressDialog() {
        if (progressDialog?.isShowing == true) return
        val view = layoutInflater.inflate(R.layout.dialog_usb_scan_progress, null)
        progressBar = view.findViewById(R.id.usb_scan_progress_bar)
        progressText = view.findViewById(R.id.usb_scan_progress_text)
        progressText?.text = getString(R.string.usb_scan_preparing)
        progressBar?.isIndeterminate = true

        progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.usb_scan_dialog_title)
            .setView(view)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun updateScanProgress(processed: Int, total: Int) {
        mainHandler.post {
            val bar = progressBar ?: return@post
            val textView = progressText
            if (total <= 0) {
                bar.isIndeterminate = true
                textView?.text = getString(R.string.usb_scan_preparing)
            } else {
                bar.isIndeterminate = false
                bar.max = total
                bar.progress = processed.coerceAtMost(total)
                textView?.text = getString(R.string.usb_scan_progress, processed, total)
            }
        }
    }

    private fun dismissProgressDialog() {
        mainHandler.post {
            progressDialog?.dismiss()
            progressDialog = null
            progressBar = null
            progressText = null
        }
    }
}

data class FolderSummary(
    val name: String,
    val artist: String?,
    val album: String?,
    val albumArt: ByteArray?,
    val albumDominant: Boolean,
    val trackCount: Int,
    val trackTitles: List<String>
)

data class FolderMetadataSummary(
    val mostCommonArtist: String?,
    val mostCommonAlbum: String?,
    val albumArt: ByteArray?,
    val trackCount: Int,
    val trackTitles: List<String>,
    val mostCommonAlbumCount: Int
) {
    fun hasDominantAlbum(threshold: Double = 0.8): Boolean {
        if (trackCount == 0) return false
        return mostCommonAlbumCount.toDouble() >= (trackCount * threshold)
    }
}

data class Mp3Metadata(
    val artist: String?,
    val album: String?,
    val albumArt: ByteArray?,
    val title: String?
)
