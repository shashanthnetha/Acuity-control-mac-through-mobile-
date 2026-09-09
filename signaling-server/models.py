"""
Pydantic data models and schemas for WebRTC signaling server.
"""

from enum import StrEnum
from typing import Any
from pydantic import BaseModel, Field


class SignalingMessageType(StrEnum):
    """Allowed signaling message types in the WebSocket protocol."""
    OFFER = "offer"
    ANSWER = "answer"
    ICE_CANDIDATE = "ice-candidate"
    PEER_JOINED = "peer-joined"
    PEER_LEFT = "peer-left"
    DEVICE_IDENTITY = "device-identity"
    AUTH_CHALLENGE = "auth-challenge"
    AUTH_RESPONSE = "auth-response"
    APPROVAL_PENDING = "approval-pending"
    ERROR = "error"


class PeerRole(StrEnum):
    """Supported peer roles in a 1-to-1 WebRTC room."""
    HOST = "host"
    VIEWER = "viewer"


class SignalingMessage(BaseModel):
    """
    Standard JSON envelope for WebRTC signaling messages.
    Example:
        {
            "type": "offer",
            "payload": {"sdp": "...", "type": "offer"}
        }
    """
    type: SignalingMessageType
    payload: dict[str, Any] = Field(default_factory=dict)

    @classmethod
    def error(cls, message: str, code: str | None = None) -> "SignalingMessage":
        payload: dict[str, Any] = {"message": message}
        if code:
            payload["code"] = code
        return cls(type=SignalingMessageType.ERROR, payload=payload)

    @classmethod
    def peer_joined(
        cls,
        role: PeerRole | str,
        device_id: str | None = None,
        device_name: str | None = None,
    ) -> "SignalingMessage":
        payload: dict[str, Any] = {"role": str(role)}
        if device_id:
            payload["deviceId"] = device_id
        if device_name:
            payload["deviceName"] = device_name
        return cls(
            type=SignalingMessageType.PEER_JOINED,
            payload=payload,
        )

    @classmethod
    def peer_left(cls, role: PeerRole | str) -> "SignalingMessage":
        return cls(
            type=SignalingMessageType.PEER_LEFT,
            payload={"role": str(role)},
        )


class RoomCreateResponse(BaseModel):
    """Response returned when a host creates a new signaling room."""
    room_code: str = Field(
        ...,
        description="6-character alphanumeric room identifier",
        examples=["X7K2QP"],
    )


class CurrentRoomResponse(BaseModel):
    """Active host room response for zero-config and saved connection retrieval."""
    room_code: str
    host_connected: bool
    version: str = "1.0"


class RoomStatusResponse(BaseModel):
    """Debugging and diagnostic status response for a room."""
    room_code: str
    exists: bool = True
    host_connected: bool
    viewer_connected: bool
    created_at: float
    last_activity: float
    messages_relayed: int
    ttl_remaining_seconds: float
