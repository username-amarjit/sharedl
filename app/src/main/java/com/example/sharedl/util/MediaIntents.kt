package com.example.sharedl.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.sharedl.R

/** Opening and sharing the files we saved. Only ever hands out our own content:// URIs. */
object MediaIntents {

    fun open(context: Context, uri: Uri, mimeType: String?) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(context, intent)
    }

    fun share(context: Context, uri: Uri, mimeType: String?, title: String) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData lets the share sheet show a preview and carries the read permission along.
        send.clipData = ClipData.newRawUri(title, uri)
        launch(context, Intent.createChooser(send, null))
    }

    private fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_player, Toast.LENGTH_SHORT).show()
        }
    }
}
