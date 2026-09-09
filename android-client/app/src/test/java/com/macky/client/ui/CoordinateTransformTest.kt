package com.macky.client.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateTransformTest {

    private val viewWidth = 1000f
    private val viewHeight = 600f

    @Test
    fun test1xZoomIdentityMapping() {
        // At 1x zoom with zero pan, screen touch should match normalized coordinates 1:1
        val center = mapTouchToNormalized(
            touchPos = Offset(500f, 300f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 1f,
            pan = Offset.Zero
        )
        assertEquals(0.5f, center.first, 0.0001f)
        assertEquals(0.5f, center.second, 0.0001f)

        val topLeft = mapTouchToNormalized(
            touchPos = Offset(0f, 0f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 1f,
            pan = Offset.Zero
        )
        assertEquals(0.0f, topLeft.first, 0.0001f)
        assertEquals(0.0f, topLeft.second, 0.0001f)

        val bottomRight = mapTouchToNormalized(
            touchPos = Offset(1000f, 600f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 1f,
            pan = Offset.Zero
        )
        assertEquals(1.0f, bottomRight.first, 0.0001f)
        assertEquals(1.0f, bottomRight.second, 0.0001f)

        val arbitrary = mapTouchToNormalized(
            touchPos = Offset(250f, 450f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 1f,
            pan = Offset.Zero
        )
        assertEquals(0.25f, arbitrary.first, 0.0001f)
        assertEquals(0.75f, arbitrary.second, 0.0001f)
    }

    @Test
    fun test2xZoomCenteredMapping() {
        // At 2x zoom centered, the visible area of the Mac desktop is [0.25 .. 0.75] in both axes
        val center = mapTouchToNormalized(
            touchPos = Offset(500f, 300f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 2f,
            pan = Offset.Zero
        )
        assertEquals(0.5f, center.first, 0.0001f)
        assertEquals(0.5f, center.second, 0.0001f)

        // Left edge of screen (0px) maps to 25% of Mac desktop
        val leftEdge = mapTouchToNormalized(
            touchPos = Offset(0f, 300f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 2f,
            pan = Offset.Zero
        )
        assertEquals(0.25f, leftEdge.first, 0.0001f)
        assertEquals(0.5f, leftEdge.second, 0.0001f)

        // Right edge of screen (1000px) maps to 75% of Mac desktop
        val rightEdge = mapTouchToNormalized(
            touchPos = Offset(1000f, 300f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 2f,
            pan = Offset.Zero
        )
        assertEquals(0.75f, rightEdge.first, 0.0001f)
        assertEquals(0.5f, rightEdge.second, 0.0001f)
    }

    @Test
    fun test2xZoomWithPanMapping() {
        // At 2x zoom, max horizontal pan is viewWidth * (2 - 1) / 2 = 500f.
        // Pan of +500f shifts content to the right, showing the leftmost part [0.0 .. 0.5]
        val pan = Offset(500f, 300f) // Panned to top-left corner of the desktop
        val topLeftTouch = mapTouchToNormalized(
            touchPos = Offset(0f, 0f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 2f,
            pan = pan
        )
        assertEquals(0.0f, topLeftTouch.first, 0.0001f)
        assertEquals(0.0f, topLeftTouch.second, 0.0001f)

        val centerTouch = mapTouchToNormalized(
            touchPos = Offset(500f, 300f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 2f,
            pan = pan
        )
        assertEquals(0.25f, centerTouch.first, 0.0001f)
        assertEquals(0.25f, centerTouch.second, 0.0001f)
    }

    @Test
    fun testOutOfrangeClamping() {
        val clamped = mapTouchToNormalized(
            touchPos = Offset(-100f, 800f),
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            scale = 1f,
            pan = Offset.Zero
        )
        assertEquals(0.0f, clamped.first, 0.0001f)
        assertEquals(1.0f, clamped.second, 0.0001f)
    }

    @Test
    fun testInvalidDimensionsFallback() {
        val fallback = mapTouchToNormalized(
            touchPos = Offset(50f, 50f),
            viewWidth = 0f,
            viewHeight = 0f,
            scale = 2f,
            pan = Offset.Zero
        )
        assertEquals(0.5f, fallback.first, 0.0001f)
        assertEquals(0.5f, fallback.second, 0.0001f)
    }
}
