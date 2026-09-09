package com.macky.client.webrtc

import com.macky.client.webrtc.models.InputEvent
import org.junit.Assert.*
import org.junit.Test

class InputEventTest {

    @Test
    fun testTapSerialization() {
        val event = InputEvent.tap(0.45f, 0.65f)
        val json = event.toJson()
        assertTrue(json.contains("\"type\":\"tap\""))
        assertTrue(json.contains("\"x\":0.45"))
        assertTrue(json.contains("\"y\":0.65"))

        val parsed = InputEvent.fromJson(json)
        assertEquals("tap", parsed.type)
        assertEquals(0.45f, parsed.x ?: 0f, 0.001f)
        assertEquals(0.65f, parsed.y ?: 0f, 0.001f)
    }

    @Test
    fun testRightTapSerialization() {
        val event = InputEvent.rightTap(0.12f, 0.34f)
        val json = event.toJson()
        assertTrue(json.contains("\"type\":\"right_tap\""))

        val parsed = InputEvent.fromJson(json)
        assertEquals("right_tap", parsed.type)
        assertEquals(0.12f, parsed.x ?: 0f, 0.001f)
        assertEquals(0.34f, parsed.y ?: 0f, 0.001f)
    }

    @Test
    fun testDragLifecycleSerialization() {
        val start = InputEvent.dragStart(0.1f, 0.2f)
        val move = InputEvent.dragMove(0.15f, 0.25f)
        val end = InputEvent.dragEnd(0.3f, 0.4f)

        val parsedStart = InputEvent.fromJson(start.toJson())
        val parsedMove = InputEvent.fromJson(move.toJson())
        val parsedEnd = InputEvent.fromJson(end.toJson())

        assertEquals("drag_start", parsedStart.type)
        assertEquals("drag_move", parsedMove.type)
        assertEquals("drag_end", parsedEnd.type)
        assertEquals(0.15f, parsedMove.x ?: 0f, 0.001f)
    }

    @Test
    fun testScrollSerialization() {
        val scroll = InputEvent.scroll(0.5f, 0.5f, 2.5f, -18.0f)
        val json = scroll.toJson()
        val parsed = InputEvent.fromJson(json)

        assertEquals("scroll", parsed.type)
        assertEquals(2.5f, parsed.dx ?: 0f, 0.001f)
        assertEquals(-18.0f, parsed.dy ?: 0f, 0.001f)
    }

    @Test
    fun testKeyTextSerialization() {
        val keyText = InputEvent.keyText("https://google.com")
        val json = keyText.toJson()
        val parsed = InputEvent.fromJson(json)

        assertEquals("key", parsed.type)
        assertEquals("https://google.com", parsed.text)
    }

    @Test
    fun testKeyCodeSerialization() {
        val keyCode = InputEvent.keyCode(51) // Backspace
        val json = keyCode.toJson()
        val parsed = InputEvent.fromJson(json)

        assertEquals("key", parsed.type)
        assertEquals(51, parsed.keyCode)
    }
}
