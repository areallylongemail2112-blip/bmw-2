package com.bmw.assistant.ui.common

/** One-shot event wrapper so a result isn't re-delivered to observers on config change. */
class Event<out T>(private val content: T) {
    private var handled = false
    fun getIfNotHandled(): T? = if (handled) null else { handled = true; content }
}
