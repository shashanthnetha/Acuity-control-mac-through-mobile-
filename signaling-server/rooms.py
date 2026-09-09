"""
Room and RoomManager implementations for managing WebRTC signaling state.
"""

import asyncio
import logging
import secrets
import time
from fastapi import WebSocket
from models import PeerRole

logger = logging.getLogger("signaling.rooms")

# Characters for 6-character room codes (unambiguous uppercase alphanumeric)
ROOM_CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
ROOM_CODE_LENGTH = 6


class Room:
    """
    Represents a 1-to-1 WebRTC signaling room.
    Accommodates exactly one 'host' (Mac) and one 'viewer' (Android).
    Thread-safe and async-safe via internal asyncio.Lock.
    """

    def __init__(self, room_code: str) -> None:
        self.room_code: str = room_code.upper()
        self.created_at: float = time.time()
        self.last_activity: float = self.created_at
        self.host_ws: WebSocket | None = None
        self.viewer_ws: WebSocket | None = None
        self.messages_relayed: int = 0
        self.both_connected_once: bool = False
        self._lock: asyncio.Lock = asyncio.Lock()

    def touch(self) -> None:
        """Update last activity timestamp."""
        self.last_activity = time.time()

    def increment_relay_count(self) -> int:
        """Increment and return total relayed message count."""
        self.messages_relayed += 1
        self.touch()
        return self.messages_relayed

    @property
    def is_empty(self) -> bool:
        """Returns True if no peers are currently connected."""
        return self.host_ws is None and self.viewer_ws is None

    @property
    def is_full(self) -> bool:
        """Returns True if both host and viewer are connected."""
        return self.host_ws is not None and self.viewer_ws is not None

    def has_role(self, role: PeerRole | str) -> bool:
        """Check if a specific role is currently connected."""
        if role == PeerRole.HOST:
            return self.host_ws is not None
        if role == PeerRole.VIEWER:
            return self.viewer_ws is not None
        return False

    def get_ws(self, role: PeerRole | str) -> WebSocket | None:
        """Get WebSocket connection for a role."""
        if role == PeerRole.HOST:
            return self.host_ws
        if role == PeerRole.VIEWER:
            return self.viewer_ws
        return None

    def get_other_ws(self, role: PeerRole | str) -> WebSocket | None:
        """Get WebSocket connection of the opposite peer."""
        if role == PeerRole.HOST:
            return self.viewer_ws
        if role == PeerRole.VIEWER:
            return self.host_ws
        return None

    async def attach_peer(
        self, role: PeerRole, ws: WebSocket, allow_host_reconnect: bool = True
    ) -> tuple[bool, str | None, WebSocket | None]:
        """
        Atomically attach a peer to the room under room lock.
        Prevents race conditions when multiple peers attempt to join simultaneously.

        Returns:
            (success: bool, error_message: str | None, old_ws_to_close: WebSocket | None)
        """
        async with self._lock:
            if role == PeerRole.HOST:
                old_ws: WebSocket | None = None
                if self.host_ws is not None:
                    if allow_host_reconnect:
                        # Mac host app crashed or restarted: replace stale connection
                        old_ws = self.host_ws
                        logger.info(
                            "Host reconnecting to room %s: replacing previous connection",
                            self.room_code,
                        )
                    else:
                        return False, "Host is already connected to this room", None

                self.host_ws = ws
                if self.viewer_ws is not None:
                    self.both_connected_once = True
                self.touch()
                return True, None, old_ws

            elif role == PeerRole.VIEWER:
                if self.host_ws is None:
                    return (
                        False,
                        "Room not found or host has not joined yet. Host must connect first.",
                        None,
                    )

                if self.viewer_ws is not None:
                    return False, "Viewer is already connected to this room", None

                self.viewer_ws = ws
                self.both_connected_once = True
                self.touch()
                return True, None, None

            return False, f"Unknown role '{role}'", None

    async def detach_peer(
        self, role: PeerRole, ws: WebSocket
    ) -> tuple[bool, WebSocket | None]:
        """
        Atomically detach a peer if the given websocket is still the active one.
        If the socket was already superseded by a reconnection, does not notify peer-left.
        Returns:
            (should_cleanup_room: bool, other_ws_to_notify: WebSocket | None)
        """
        async with self._lock:
            is_active = False
            if role == PeerRole.HOST and self.host_ws is ws:
                self.host_ws = None
                self.touch()
                is_active = True
            elif role == PeerRole.VIEWER and self.viewer_ws is ws:
                self.viewer_ws = None
                self.touch()
                is_active = True

            # If this socket was superseded by a reconnect, do not notify peer-left
            if not is_active:
                return False, None

            other_ws = self.get_other_ws(role)

            # Cleanup room immediately if both peers have left after an active paired session
            should_cleanup = self.is_empty and self.both_connected_once
            return should_cleanup, other_ws

    def is_expired(self, ttl_seconds: float) -> bool:
        """
        Check if the room has expired based on TTL.
        Active rooms with a currently connected host are NOT expired.
        """
        if self.host_ws is not None:
            return False
        return (time.time() - self.last_activity) > ttl_seconds

    def ttl_remaining(self, ttl_seconds: float) -> float:
        """Returns remaining TTL in seconds (or 0 if expired)."""
        elapsed = time.time() - self.last_activity
        return max(0.0, ttl_seconds - elapsed)


class RoomManager:
    """
    In-memory manager handling room lifecycle, lookups, and expiration.
    Thread/async safe via internal asyncio.Lock.
    """

    def __init__(self, room_ttl_seconds: float = 300.0) -> None:
        self._rooms: dict[str, Room] = {}
        self._ttl_seconds: float = room_ttl_seconds
        self._lock: asyncio.Lock = asyncio.Lock()

    @property
    def room_ttl_seconds(self) -> float:
        return self._ttl_seconds

    def generate_room_code(self) -> str:
        """Generate a cryptographically random 6-character room code."""
        while True:
            code = "".join(secrets.choice(ROOM_CODE_ALPHABET) for _ in range(ROOM_CODE_LENGTH))
            if code not in self._rooms:
                return code

    async def create_room(self, room_code: str | None = None) -> Room:
        """Create a new room or return existing room under lock."""
        async with self._lock:
            if room_code:
                code = room_code.strip().upper()
            else:
                code = self.generate_room_code()

            if code in self._rooms:
                return self._rooms[code]

            room = Room(code)
            self._rooms[code] = room
            logger.info("Created room %s (total active rooms: %d)", code, len(self._rooms))
            return room

    async def get_room(self, room_code: str) -> Room | None:
        """Retrieve room by code (case-insensitive)."""
        code = room_code.strip().upper()
        async with self._lock:
            return self._rooms.get(code)

    async def remove_room(self, room_code: str) -> bool:
        """Remove a room by code."""
        code = room_code.strip().upper()
        async with self._lock:
            if code in self._rooms:
                del self._rooms[code]
                logger.info("Removed room %s (remaining rooms: %d)", code, len(self._rooms))
                return True
            return False

    async def cleanup_expired_rooms(self) -> int:
        """
        Background task to clean up rooms that have exceeded their TTL.
        Returns count of removed rooms.
        """
        async with self._lock:
            expired_codes: list[str] = []
            for code, room in list(self._rooms.items()):
                if room.is_expired(self._ttl_seconds):
                    expired_codes.append(code)

            for code in expired_codes:
                room = self._rooms.pop(code, None)
                if room:
                    for role in (PeerRole.HOST, PeerRole.VIEWER):
                        ws = room.get_ws(role)
                        if ws:
                            try:
                                await ws.close(code=1000, reason="Room expired due to inactivity")
                            except Exception:
                                pass
                    logger.info(
                        "Expired and cleaned up room %s (inactive for >%.0fs)",
                        code,
                        self._ttl_seconds,
                    )

            return len(expired_codes)

    def total_rooms(self) -> int:
        """Get current room count."""
        return len(self._rooms)
