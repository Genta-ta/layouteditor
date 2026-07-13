package com.itsvks.layouteditor.utils

import android.util.Log

object EditorLog {
  private val entries = mutableListOf<String>()
  private const val MAX_ENTRIES = 500

  @Synchronized
  fun d(tag: String, message: String) {
    Log.d(tag, message)
    val entry = "[$tag] $message"
    if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
    entries.add(entry)
  }

  @Synchronized
  fun getAll(): String {
    return entries.joinToString("\n")
  }

  @Synchronized
  fun clear() {
    entries.clear()
  }

  @Synchronized
  fun size(): Int = entries.size
}
