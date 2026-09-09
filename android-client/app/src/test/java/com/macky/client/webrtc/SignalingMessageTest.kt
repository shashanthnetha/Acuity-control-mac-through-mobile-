package com.macky.client.webrtc

import com.macky.client.webrtc.models.SignalingEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying exact serialization and deserialization parity
 * between Android client and the Python FastAPI signaling server + Mac agent.
 */
class SignalingMessageTest {

    @Test
    fun testDeserializeOfferFromMacAgent() {
        val rawOfferJson = """
            {
              "type": "offer",
              "payload": {
                "type": "offer",
                "sdp": "v=0\r\no=- 42 2 IN IP4 127.0.0.1\r\ns=-\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
              }
            }
        """.trimIndent()

        val envelope = SignalingEnvelope.fromJson(rawOfferJson)
        assertEquals("offer", envelope.type)

        val sdpPayload = envelope.getSdpPayload()
        assertNotNull(sdpPayload)
        assertEquals("offer", sdpPayload?.type)
        assertTrue(sdpPayload?.sdp?.contains("m=video") == true)
    }

    @Test
    fun testDeserializeIceCandidate() {
        val rawCandidateJson = """
            {
              "type": "ice-candidate",
              "payload": {
                "candidate": "candidate:12345 1 UDP 2130706431 192.168.1.50 54321 typ host",
                "sdpMid": "0",
                "sdpMLineIndex": 0
              }
            }
        """.trimIndent()

        val envelope = SignalingEnvelope.fromJson(rawCandidateJson)
        assertEquals("ice-candidate", envelope.type)

        val candidate = envelope.getCandidatePayload()
        assertNotNull(candidate)
        assertEquals("0", candidate?.sdpMid)
        assertEquals(0, candidate?.sdpMLineIndex)
        assertTrue(candidate?.candidate?.startsWith("candidate:") == true)
    }

    @Test
    fun testDeserializePeerJoinedAndError() {
        val rawJoinedJson = """{"type":"peer-joined","payload":{"role":"viewer"}}"""
        val joinedEnv = SignalingEnvelope.fromJson(rawJoinedJson)
        assertEquals("peer-joined", joinedEnv.type)
        assertEquals("viewer", joinedEnv.getPeerEventPayload()?.role)

        val rawErrorJson = """{"type":"error","payload":{"message":"Room full: already has a viewer","code":"ROOM_FULL"}}"""
        val errorEnv = SignalingEnvelope.fromJson(rawErrorJson)
        assertEquals("error", errorEnv.type)
        assertEquals("Room full: already has a viewer", errorEnv.getErrorPayload()?.message)
        assertEquals("ROOM_FULL", errorEnv.getErrorPayload()?.code)
    }

    @Test
    fun testSerializeAnswerForSignalingServer() {
        val dummySdp = "v=0\r\no=- 99 2 IN IP4 127.0.0.1\r\ns=-\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n"
        val answerEnvelope = SignalingEnvelope.createAnswer(dummySdp)
        val json = answerEnvelope.toJson()

        // Verify JSON string format expected by server
        assertTrue(json.contains("\"type\":\"answer\""))
        assertTrue(json.contains("\"sdp\":"))

        // Roundtrip verification
        val parsed = SignalingEnvelope.fromJson(json)
        assertEquals("answer", parsed.type)
        assertEquals("answer", parsed.getSdpPayload()?.type)
        assertEquals(dummySdp, parsed.getSdpPayload()?.sdp)
    }

    @Test
    fun testSerializeCandidateForSignalingServer() {
        val candidateStr = "candidate:98765 1 UDP 2130706431 192.168.1.20 60000 typ host"
        val candidateEnvelope = SignalingEnvelope.createCandidate(candidateStr, "0", 0)
        val json = candidateEnvelope.toJson()

        assertTrue(json.contains("\"type\":\"ice-candidate\""))
        assertTrue(json.contains("\"sdpMid\":\"0\""))

        val parsed = SignalingEnvelope.fromJson(json)
        assertEquals("ice-candidate", parsed.type)
        val payload = parsed.getCandidatePayload()
        assertEquals(candidateStr, payload?.candidate)
        assertEquals("0", payload?.sdpMid)
        assertEquals(0, payload?.sdpMLineIndex)
    }
}
