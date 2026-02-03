package com.bobby.docreader

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

private const val PREFS_NAME = "document_prefs"
private const val KEY_DOCUMENTS = "documents"
private const val KEY_POSITION_PREFIX = "pos_"
private const val KEY_ZOOM_PREFIX = "zoom_"
private const val KEY_HEIGHT_PREFIX = "height_"           // for total scroll height
private const val KEY_HPOS_PREFIX = "hpos_"               // for horizontal position

class DocumentStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<DocumentEntry> {
        val set = prefs.getStringSet(KEY_DOCUMENTS, emptySet()) ?: emptySet()
        return set.mapNotNull { serialized ->
            val parts = serialized.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val uri = Uri.parse(parts[0])
            val name = parts[1]
            val pos = prefs.getInt(KEY_POSITION_PREFIX + uri.toString(), 0)
            val height = getTotalHeight(uri)
            DocumentEntry(uri, name, pos, height)
        }
    }

    fun add(uri: Uri, displayName: String) {
        val set = prefs.getStringSet(KEY_DOCUMENTS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        val serialized = "${uri}|$displayName"
        if (set.add(serialized)) {
            prefs.edit { putStringSet(KEY_DOCUMENTS, set) }
        }
    }

    fun remove(uri: Uri) {
        val set = prefs.getStringSet(KEY_DOCUMENTS, mutableSetOf())?.toMutableSet() ?: return
        val toRemove = set.find { it.startsWith("$uri|") } ?: return
        set.remove(toRemove)
        prefs.edit {
            putStringSet(KEY_DOCUMENTS, set)
            remove(KEY_POSITION_PREFIX + uri.toString())
            remove(KEY_ZOOM_PREFIX + uri.toString())
            remove(KEY_HEIGHT_PREFIX + uri.toString())
            remove(KEY_HPOS_PREFIX + uri.toString())
        }
    }

    fun savePosition(uri: Uri, position: Int) {
        prefs.edit { putInt(KEY_POSITION_PREFIX + uri.toString(), position) }
    }

    fun getPosition(uri: Uri): Int =
        prefs.getInt(KEY_POSITION_PREFIX + uri.toString(), 0)

    fun saveZoom(uri: Uri, zoom: Float) {
        prefs.edit {
            putFloat(KEY_ZOOM_PREFIX + uri.toString(), zoom)
        }
    }

    fun getZoom(uri: Uri): Float =
        prefs.getFloat(KEY_ZOOM_PREFIX + uri.toString(), 1.0f)

    fun saveTotalHeight(uri: Uri, height: Int) {
        prefs.edit {
            putInt(KEY_HEIGHT_PREFIX + uri.toString(), height)
        }
    }

    fun getTotalHeight(uri: Uri): Int? {
        val height = prefs.getInt(KEY_HEIGHT_PREFIX + uri.toString(), -1)
        return if (height >= 0) height else null
    }

    fun saveHorizontalPosition(uri: Uri, position: Int) {
        prefs.edit {
            putInt(KEY_HPOS_PREFIX + uri.toString(), position)
        }
    }

    fun getHorizontalPosition(uri: Uri): Int =
        prefs.getInt(KEY_HPOS_PREFIX + uri.toString(), 0)
}