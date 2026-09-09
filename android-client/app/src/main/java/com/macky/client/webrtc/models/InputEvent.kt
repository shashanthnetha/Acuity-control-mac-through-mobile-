package com.macky.client.webrtc.models

import com.google.gson.Gson

/**
 * Data model for touch, gesture, and keyboard events transmitted over the
 * WebRTC DataChannel ("input") to the macOS agent.
 */
data class InputEvent(
    val type: String,
    val x: Float? = null,
    val y: Float? = null,
    val dx: Float? = null,
    val dy: Float? = null,
    val text: String? = null,
    val keyCode: Int? = null,
    val modifiers: List<String>? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): String = gson.toJson(this)

    companion object {
        private val gson = Gson()

        fun tap(x: Float, y: Float) = InputEvent(type = "tap", x = x, y = y)
        fun rightTap(x: Float, y: Float) = InputEvent(type = "right_tap", x = x, y = y)
        fun dragStart(x: Float, y: Float) = InputEvent(type = "drag_start", x = x, y = y)
        fun dragMove(x: Float, y: Float) = InputEvent(type = "drag_move", x = x, y = y)
        fun dragEnd(x: Float, y: Float) = InputEvent(type = "drag_end", x = x, y = y)
        fun scroll(x: Float, y: Float, dx: Float, dy: Float) = InputEvent(
            type = "scroll",
            x = x,
            y = y,
            dx = dx,
            dy = dy
        )
        fun keyText(text: String, modifiers: List<String>? = null) = InputEvent(type = "key", text = text, modifiers = modifiers)
        fun keyCode(keyCode: Int, modifiers: List<String>? = null) = InputEvent(type = "key", keyCode = keyCode, modifiers = modifiers)

        fun fromJson(json: String): InputEvent = gson.fromJson(json, InputEvent::class.java)
    }
}

