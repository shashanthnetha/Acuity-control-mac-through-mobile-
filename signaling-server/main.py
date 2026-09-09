"""
FastAPI WebRTC Signaling Server for Mac-to-Android screen mirroring.
"""

import asyncio
from contextlib import asynccontextmanager
import json
import logging
import os

from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect, status
from fastapi.middleware.cors import CORSMiddleware

from models import (
    CurrentRoomResponse,
    PeerRole,
    RoomCreateResponse,
    RoomStatusResponse,
    SignalingMessage,
    SignalingMessageType,
)
from rooms import RoomManager

# Load environment configuration
load_dotenv()

HOST = os.getenv("HOST", "0.0.0.0")
PORT = int(os.getenv("PORT", "8000"))
ROOM_TTL_SECONDS = float(os.getenv("ROOM_TTL_SECONDS", "300.0"))
CLEANUP_INTERVAL_SECONDS = float(os.getenv("CLEANUP_INTERVAL_SECONDS", "30.0"))
LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
CORS_ORIGINS = [orig.strip() for orig in os.getenv("CORS_ORIGINS", "*").split(",") if orig.strip()]

# Configure logging
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("signaling.main")

# Initialize Room Manager
room_manager = RoomManager(room_ttl_seconds=ROOM_TTL_SECONDS)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Application lifespan manager.
    Runs a background cleanup task to periodically evict expired rooms.
    """
    logger.info(
        "Starting WebRTC signaling server (TTL: %.0fs, Cleanup: %.0fs)",
        ROOM_TTL_SECONDS,
        CLEANUP_INTERVAL_SECONDS,
    )

    async def background_cleanup_loop() -> None:
        while True:
            try:
                await asyncio.sleep(CLEANUP_INTERVAL_SECONDS)
                expired_count = await room_manager.cleanup_expired_rooms()
                if expired_count > 0:
                    logger.info("Evicted %d expired room(s)", expired_count)
            except asyncio.CancelledError:
                break
            except Exception as exc:
                logger.error("Error in background cleanup loop: %s", exc)

    cleanup_task = asyncio.create_task(background_cleanup_loop())
    yield
    cleanup_task.cancel()
    try:
        await cleanup_task
    except asyncio.CancelledError:
        pass
    logger.info("Signaling server shut down successfully")


app = FastAPI(
    title="WebRTC Signaling Server",
    description="1-to-1 WebRTC signaling relay for Mac-to-Android screen mirroring",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS configuration (configurable via CORS_ORIGINS)
app.add_middleware(
    CORSMiddleware,
    allow_origins=CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/", tags=["General"])
async def root(room: str | None = None) -> dict:
    """Signaling server root endpoint supporting room status query."""
    if room:
        clean_room = room.strip().upper()
        room_obj = await room_manager.get_room(clean_room)
        return {
            "service": "macky-signaling",
            "status": "online",
            "room": clean_room,
            "room_exists": room_obj is not None,
            "host_connected": room_obj.has_role(PeerRole.HOST) if room_obj else False,
        }
    return {
        "service": "macky-signaling",
        "status": "online",
        "version": "1.0.0",
        "active_rooms": len(room_manager._rooms),
    }


@app.get("/health", tags=["Health"])
async def health_check() -> dict[str, str]:
    """Health check endpoint."""
    return {"status": "ok", "service": "webrtc-signaling-server"}


@app.get("/current-room", response_model=CurrentRoomResponse, tags=["Rooms"])
async def get_current_room() -> CurrentRoomResponse:
    """
    Returns the currently active host room code.
    Allows saved connections (e.g. via Tailscale or direct IP) to auto-fetch
    the live room code without manual typing.
    """
    async with room_manager._lock:
        # Check for active rooms with a connected host
        for room in sorted(
            room_manager._rooms.values(),
            key=lambda r: r.last_activity,
            reverse=True,
        ):
            if room.has_role(PeerRole.HOST):
                return CurrentRoomResponse(
                    room_code=room.room_code,
                    host_connected=True,
                    version="1.0",
                )

        # Fallback: if rooms exist but host is reconnecting
        if room_manager._rooms:
            most_recent = max(room_manager._rooms.values(), key=lambda r: r.last_activity)
            return CurrentRoomResponse(
                room_code=most_recent.room_code,
                host_connected=False,
                version="1.0",
            )

    raise HTTPException(
        status_code=status.HTTP_404_NOT_FOUND,
        detail="No active host room found on this server",
    )


@app.post(
    "/rooms",
    response_model=RoomCreateResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["Rooms"],
)
async def create_room() -> RoomCreateResponse:
    """
    Host calls this endpoint first to allocate a new room and obtain
    a unique 6-character room code before opening a WebSocket connection.
    """
    room = await room_manager.create_room()
    return RoomCreateResponse(room_code=room.room_code)


@app.get(
    "/rooms/{room_code}/status",
    response_model=RoomStatusResponse,
    tags=["Rooms"],
)
async def get_room_status(room_code: str) -> RoomStatusResponse:
    """
    Returns connection status and diagnostics for the specified room code.
    Useful for debugging and pre-connection checks.
    """
    room = await room_manager.get_room(room_code)
    if not room:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Room '{room_code.upper()}' does not exist or has expired",
        )

    return RoomStatusResponse(
        room_code=room.room_code,
        exists=True,
        host_connected=room.has_role(PeerRole.HOST),
        viewer_connected=room.has_role(PeerRole.VIEWER),
        created_at=room.created_at,
        last_activity=room.last_activity,
        messages_relayed=room.messages_relayed,
        ttl_remaining_seconds=round(room.ttl_remaining(room_manager.room_ttl_seconds), 1),
    )


@app.websocket("/ws/{room_code}")
async def websocket_signaling_endpoint(
    websocket: WebSocket,
    room_code: str,
    role: str = Query(..., description="Role must be either 'host' or 'viewer'"),
    deviceId: str | None = Query(None, description="Viewer device fingerprint"),
    deviceName: str | None = Query(None, description="Viewer device label"),
) -> None:
    """
    WebSocket signaling endpoint for 1-to-1 WebRTC negotiation.
    Relays SDP offers, SDP answers, and ICE candidates between host and viewer.
    Handles host reconnects, race-free peer attachment, device identity forwarding,
    and malformed messages safely.
    """
    normalized_code = room_code.strip().upper()
    role_str = role.strip().lower()

    # 1. Validate role
    if role_str not in (PeerRole.HOST, PeerRole.VIEWER):
        await websocket.accept()
        err_msg = SignalingMessage.error(
            f"Invalid role '{role}'. Must be 'host' or 'viewer'"
        )
        await websocket.send_json(err_msg.model_dump())
        await websocket.close(code=1008, reason="Invalid role")
        return

    peer_role = PeerRole(role_str)

    # 2. Get or create room (host auto-creates room if needed)
    room = await room_manager.get_room(normalized_code)
    if not room:
        if peer_role == PeerRole.HOST:
            room = await room_manager.create_room(normalized_code)
        else:
            await websocket.accept()
            err_msg = SignalingMessage.error(
                "Room not found or host has not joined yet. Host must connect first."
            )
            await websocket.send_json(err_msg.model_dump())
            await websocket.close(code=4004, reason="Host not connected")
            return

    # 3. Atomically attach peer under room lock (prevents two viewers racing)
    attached, err, old_ws_to_close = await room.attach_peer(
        peer_role, websocket, allow_host_reconnect=True
    )

    if not attached:
        await websocket.accept()
        err_msg = SignalingMessage.error(err or "Failed to attach to room")
        await websocket.send_json(err_msg.model_dump())
        await websocket.close(code=4003, reason="Room slot occupied or unavailable")
        return

    # Accept incoming connection
    await websocket.accept()
    logger.info(
        "Peer joined: role=%s room=%s (deviceId=%s, deviceName=%s)",
        peer_role.value,
        normalized_code,
        deviceId,
        deviceName,
    )

    # If host reconnected and replaced a stale connection, close the old socket cleanly
    if old_ws_to_close:
        try:
            await old_ws_to_close.close(
                code=1000, reason="Host reconnected from a new session"
            )
            logger.info("Closed previous stale host connection in room %s", normalized_code)
        except Exception as exc:
            logger.debug("Failed closing previous host socket: %s", exc)

    # 4. Notify peers of arrival
    if peer_role == PeerRole.VIEWER:
        # Notify host that viewer joined with device identity
        host_ws = room.get_ws(PeerRole.HOST)
        if host_ws:
            try:
                await host_ws.send_json(
                    SignalingMessage.peer_joined(
                        PeerRole.VIEWER,
                        device_id=deviceId,
                        device_name=deviceName,
                    ).model_dump()
                )
                logger.info(
                    "Notified host of peer-joined in room %s (device: %s, name: %s)",
                    normalized_code,
                    deviceId,
                    deviceName,
                )
            except Exception as exc:
                logger.warning("Failed to notify host of viewer join: %s", exc)
    elif peer_role == PeerRole.HOST:
        # If viewer is already in the room (e.g. host reconnected after crash), notify both!
        viewer_ws = room.get_ws(PeerRole.VIEWER)
        if viewer_ws:
            try:
                await viewer_ws.send_json(
                    SignalingMessage.peer_joined(PeerRole.HOST).model_dump()
                )
                await websocket.send_json(
                    SignalingMessage.peer_joined(PeerRole.VIEWER).model_dump()
                )
                logger.info(
                    "Mutual peer-joined notified after host reconnect in room %s",
                    normalized_code,
                )
            except Exception as exc:
                logger.warning("Failed to notify viewer of host reconnect: %s", exc)

    # 5. Message reception and relay loop
    allowed_relay_types = {
        SignalingMessageType.OFFER,
        SignalingMessageType.ANSWER,
        SignalingMessageType.ICE_CANDIDATE,
        SignalingMessageType.DEVICE_IDENTITY,
        SignalingMessageType.AUTH_CHALLENGE,
        SignalingMessageType.AUTH_RESPONSE,
        SignalingMessageType.APPROVAL_PENDING,
        SignalingMessageType.ERROR,
    }

    try:
        while True:
            raw_message = await websocket.receive_text()

            # Malformed JSON check (syntax error)
            try:
                data = json.loads(raw_message)
            except json.JSONDecodeError:
                await websocket.send_json(
                    SignalingMessage.error("Invalid JSON message format").model_dump()
                )
                continue

            # Malformed payload structure check (e.g. JSON array or scalar)
            if not isinstance(data, dict):
                await websocket.send_json(
                    SignalingMessage.error(
                        "Invalid message structure: root payload must be a JSON object"
                    ).model_dump()
                )
                continue

            msg_type = data.get("type")
            if not msg_type or msg_type not in allowed_relay_types:
                await websocket.send_json(
                    SignalingMessage.error(
                        f"Unsupported or unauthorized message type: '{msg_type}'. "
                        f"Allowed relay types: {list(allowed_relay_types)}"
                    ).model_dump()
                )
                continue

            target_ws = room.get_other_ws(peer_role)
            if not target_ws:
                await websocket.send_json(
                    SignalingMessage.error("Target peer is not connected").model_dump()
                )
                continue

            # Forward JSON payload verbatim to target peer
            await target_ws.send_text(raw_message)
            total_relayed = room.increment_relay_count()
            logger.info(
                "Relayed '%s' from %s in room %s (total relayed: %d)",
                msg_type,
                peer_role.value,
                normalized_code,
                total_relayed,
            )

    except WebSocketDisconnect:
        logger.info(
            "Peer disconnected: role=%s room=%s",
            peer_role.value,
            normalized_code,
        )
    except Exception as exc:
        logger.warning(
            "Connection error for role=%s room=%s: %s",
            peer_role.value,
            normalized_code,
            exc,
        )
    finally:
        # Atomic peer detachment
        should_cleanup, other_ws = await room.detach_peer(peer_role, websocket)

        # Notify remaining peer if connected
        if other_ws:
            try:
                await other_ws.send_json(
                    SignalingMessage.peer_left(peer_role).model_dump()
                )
                logger.info(
                    "Notified remaining peer of peer-left (role=%s) in room %s",
                    peer_role.value,
                    normalized_code,
                )
            except Exception as exc:
                logger.debug("Failed to notify peer of disconnect: %s", exc)

        # Clean up room if both peers completed their session
        if should_cleanup:
            await room_manager.remove_room(normalized_code)
            logger.info(
                "Both peers disconnected after active session. Cleaned up room %s",
                normalized_code,
            )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "main:app",
        host=HOST,
        port=PORT,
        reload=False,
    )
