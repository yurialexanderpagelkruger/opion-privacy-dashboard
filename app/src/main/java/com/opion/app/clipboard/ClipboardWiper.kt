package com.opion.app.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import kotlin.random.Random

object ClipboardWiper {

    data class Snapshot(
        val hasContent: Boolean,
        val length: Int,
        val label: String?
    )

    fun snapshot(context: Context): Snapshot = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Snapshot(false, 0, null)
        } else {
            val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            Snapshot(true, text.length, clip.description?.label?.toString())
        }
    } catch (_: Throwable) {
        Snapshot(false, 0, null)
    }

    fun wipe(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // 1. Reemplazo por ruido
        val noise = buildString { repeat(96) { append("0123456789abcdef"[Random.nextInt(16)]) } }
        cm.setPrimaryClip(ClipData.newPlainText("", noise))

        // 2. Vaciado real
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()                                  // API 28+
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("", ""))       // API 26-27
        }
        true
    } catch (_: Throwable) {
        false
    }
}
