package com.bobby.docreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView

class MainActivity : AppCompatActivity() {

    private lateinit var store: DocumentStore
    private lateinit var adapter: DocumentAdapter

    private val TAG = "MainActivity"

    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult

        // 1. Take persistable permission immediately
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to take persistable permission", e)
        }

        val defaultName = getFileName(uri) ?: "Document ${System.currentTimeMillis()}"
        showNameDialog(uri, defaultName)
    }

    private var refreshList: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = DocumentStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val title = MaterialTextView(this).apply {
            text = "My Documents"
            textSize = 28f
            setPadding(24, 32, 24, 16)
        }
        root.addView(title)

        val addBtn = MaterialButton(this).apply {
            text = getString(R.string.add_document)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(24, 0, 24, 24)
            }
        }
        addBtn.setOnClickListener {
            pickDocument.launch(arrayOf("*/*")) // OpenDocument requires an array of MIME types
        }
        root.addView(addBtn)

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(recycler)

        val emptyView = MaterialTextView(this).apply {
            text = getString(R.string.no_documents)
            textSize = 18f
            setPadding(32, 64, 32, 32)
            gravity = Gravity.CENTER
            isVisible = false
        }
        root.addView(emptyView)

        adapter = DocumentAdapter(
            onOpen = { entry ->
                ReaderActivity.start(this, entry.uri, entry.displayName, entry.lastPosition)
            },
            onDelete = { entry ->
                store.remove(entry.uri)
                refreshList()
            }
        )
        recycler.adapter = adapter

        setContentView(root)

        refreshList = {
            val items = store.getAll()
            Log.d(TAG, "Refreshing list with ${items.size} items")
            adapter.submitList(items)
            emptyView.isVisible = items.isEmpty()
        }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun showNameDialog(uri: Uri, defaultName: String) {
        val editText = EditText(this).apply {
            setText(defaultName)
            setSelection(0, defaultName.length)  // select all for easy overwrite
        }

        AlertDialog.Builder(this)
            .setTitle("Name this document")
            .setView(editText)
            .setPositiveButton("Add") { _, _ ->
                val chosenName = editText.text.toString().trim().ifEmpty { defaultName }
                store.add(uri, chosenName)
                refreshList()
            }
            .setNegativeButton("Cancel") { _, _ -> }
            .show()
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name ?: uri.lastPathSegment
    }
}

class DocumentAdapter(
    private val onOpen: (DocumentEntry) -> Unit,
    private val onDelete: (DocumentEntry) -> Unit
) : RecyclerView.Adapter<DocumentAdapter.ViewHolder>() {

    private var items: List<DocumentEntry> = emptyList()

    fun submitList(newList: List<DocumentEntry>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val card = MaterialCardView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 8, 16, 8)
            }
            radius = 12f
            cardElevation = 2f
        }

        val inner = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
        }
        card.addView(inner)

        val title = MaterialTextView(parent.context).apply {
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        inner.addView(title)

        val progress = MaterialTextView(parent.context).apply {
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            visibility = View.GONE
        }
        inner.addView(progress)

        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        inner.addView(row)

        val openBtn = MaterialButton(parent.context).apply {
            text = "Open"
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(openBtn)

        val deleteBtn = MaterialButton(parent.context).apply {
            text = "Delete"
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(deleteBtn)

        return ViewHolder(card, title, progress, openBtn, deleteBtn)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.displayName

        val height = item.totalHeight
        val pos = item.lastPosition.coerceAtMost(height ?: 0)

        if (height != null && height > 0) {
            val percent = (pos.toFloat() / height * 100).toInt().coerceIn(0, 100)
            //holder.progress.text = "$percent% read"
            holder.progress.isVisible = true
        } else {
            holder.progress.isVisible = false
        }

        holder.openButton.setOnClickListener { onOpen(item) }
        holder.deleteButton.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size

    class ViewHolder(
        view: View,
        val title: MaterialTextView,
        val progress: MaterialTextView,
        val openButton: MaterialButton,
        val deleteButton: MaterialButton
    ) : RecyclerView.ViewHolder(view)
}