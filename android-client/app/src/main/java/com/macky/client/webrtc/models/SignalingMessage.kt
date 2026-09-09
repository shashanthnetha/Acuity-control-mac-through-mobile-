package com.macky.client.webrtc.models

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * Standard envelope for WebRTC signaling messages matching the FastAPI server.
 *
 * Example JSON:
 * {
 *   "type": "offer",
 *   "payload": { "type": "offer", "sdp": "..." }
 * }
 */
data class SignalingEnvelope(
    @SerializedName("type") val type: String,
    @SerializedName("payload") val payload: JsonElement? = JsonObject()
) {
    companion object {
        private val gson = Gson()

        fun createAnswer(sdp: String): SignalingEnvelope {
            val payloadObj = SdpPayload(type = "answer", sdp = sdp)
            return SignalingEnvelope(
                type = "answer",
                payload = gson.toJsonTree(payloadObj)
            )
        }

        fun createCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int): SignalingEnvelope {
            val payloadObj = IceCandidatePayload(
                candidate = candidate,
                sdpMid = sdpMid ?: "0",
                sdpMLineIndex = sdpMLineIndex
            )
            return SignalingEnvelope(
                type = "ice-candidate",
                payload = gson.toJsonTree(payloadObj)
            )
        }

        fun createDeviceIdentity(deviceId: String, deviceName: String, publicKey: String): SignalingEnvelope {
            val payloadObj = DeviceIdentityPayload(
                deviceId = deviceId,
                deviceName = deviceName,
                publicKey = publicKey
            )
            return SignalingEnvelope(
                type = "device-identity",
                payload = gson.toJsonTree(payloadObj)
            )
        }

        fun createAuthResponse(nonce: String, signature: String): SignalingEnvelope {
            val payloadObj = AuthResponsePayload(
                nonce = nonce,
                signature = signature
            )
            return SignalingEnvelope(
                type = "auth-response",
                payload = gson.toJsonTree(payloadObj)
            )
        }

        fun fromJson(json: String): SignalingEnvelope {
            return gson.fromJson(json, SignalingEnvelope::class.java)
        }
    }

    fun toJson(): String {
        return gson.toJson(this)
    }

    fun getSdpPayload(): SdpPayload? {
        if (payload == null) return null
        return try {
            gson.fromJson(payload, SdpPayload::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getCandidatePayload(): IceCandidatePayload? {
        if (payload == null) return null
        return try {
            gson.fromJson(payload, IceCandidatePayload::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getPeerEventPayload(): PeerEventPayload? {
        if (payload == null) return null
        return try {
            gson.fromJson(payload, PeerEventPayload::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getAuthChallengePayload(): AuthChallengePayload? {
        if (payload == null) return null
        return try {
            gson.fromJson(payload, AuthChallengePayload::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getErrorPayload(): ErrorPayload? {
        if (payload == null) return null
        return try {
            gson.fromJson(payload, ErrorPayload::class.java)
        } catch (e: Exception) {
            null
        }
    }
}

data class SdpPayload(
    @SerializedName("type") val type: String,
    @SerializedName("sdp") val sdp: String
)

data class IceCandidatePayload(
    @SerializedName("candidate") val candidate: String,
    @SerializedName("sdpMid") val sdpMid: String? = "0",
    @SerializedName("sdpMLineIndex") val sdpMLineIndex: Int = 0
)

data class PeerEventPayload(
    @SerializedName("role") val role: String
)

data class DeviceIdentityPayload(
    @SerializedName("deviceId") val deviceId: String,
    @SerializedName("deviceName") val deviceName: String,
    @SerializedName("publicKey") val publicKey: String
)

data class AuthChallengePayload(
    @SerializedName("nonce") val nonce: String
)

data class AuthResponsePayload(
    @SerializedName("nonce") val nonce: String,
    @SerializedName("signature") val signature: String
)

data class ErrorPayload(
    @SerializedName("message") val message: String,
    @SerializedName("code") val code: String? = null
)
