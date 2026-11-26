package dev.rustdroid.ide.projects

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * R4A (alcance de archivos): a folder picked with Android's folder picker
 * (`OpenDocumentTree`), read through SAF. Works without the storage
 * permission — the picker grants access to just that folder.
 */
class SafTree(private val resolver: ContentResolver, private val treeUri: Uri) : ProjectRepository.TreeSource {

    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** The picked folder's name (as the user sees it). */
    fun displayName(): String {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        return resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: rootId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "proyecto" }
    }

    override fun children(id: String): List<ProjectRepository.TreeSource.Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, id)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = ArrayList<ProjectRepository.TreeSource.Entry>()
        resolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += ProjectRepository.TreeSource.Entry(
                    id = c.getString(0),
                    name = c.getString(1) ?: continue,
                    isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0L else c.getLong(3),
                )
            }
        }
        return out
    }

    override fun open(id: String): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            ?: throw FileNotFoundException(id)
}
