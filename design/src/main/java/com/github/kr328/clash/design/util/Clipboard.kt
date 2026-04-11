package com.github.kr328.clash.common.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object Clipboard {
    /**
     * Копирует переданный текст в буфер обмена устройства.
     */
    fun setClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("clash_copied", text)
        clipboard.setPrimaryClip(clip)
    }
}