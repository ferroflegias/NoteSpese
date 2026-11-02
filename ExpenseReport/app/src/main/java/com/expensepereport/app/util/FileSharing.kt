package com.expensepereport.app.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** content:// Uri that other apps can open, for a file in the app's private storage or cache. */
fun Context.contentUriFor(file: File): Uri =
    FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
