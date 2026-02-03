package com.bobby.docreader

import android.net.Uri

data class DocumentEntry(
    val uri: Uri,
    val displayName: String,
    val lastPosition: Int = 0,
    val totalHeight: Int? = null   // new: total scrollable height
)