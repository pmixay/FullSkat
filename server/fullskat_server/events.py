"""In-process pub/sub behind `GET /v1/events`: per user, the latest change seq; per device, revocation.

The server is a single process, so a dictionary of subscriptions is enough. A subscription keeps only the
newest seq (bursts of changes coalesce into one `changed` event) and a revoked flag.
"""

from __future__ import annotations

import asyncio


class Subscription:
    """One open event stream of one device."""

    def __init__(self, user_id: str, device_id: str, seq: int) -> None:
        self.user_id = user_id
        self.device_id = device_id
        self.seq = seq  # newest seq announced to this stream (sent or pending)
        self.revoked = False
        self._event = asyncio.Event()

    def _wake(self) -> None:
        self._event.set()

    async def wait(self, timeout: float) -> bool:
        """Wait for a change or revocation; False when `timeout` seconds passed without one."""
        try:
            await asyncio.wait_for(self._event.wait(), timeout)
        except TimeoutError:
            return False
        self._event.clear()  # cleared before the caller reads `seq`, so a later publish wakes it again
        return True


class EventHub:
    def __init__(self) -> None:
        self._by_user: dict[str, set[Subscription]] = {}

    def subscribe(self, user_id: str, device_id: str, seq: int) -> Subscription:
        subscription = Subscription(user_id, device_id, seq)
        self._by_user.setdefault(user_id, set()).add(subscription)
        return subscription

    def unsubscribe(self, subscription: Subscription) -> None:
        subscriptions = self._by_user.get(subscription.user_id)
        if subscriptions is not None:
            subscriptions.discard(subscription)
            if not subscriptions:
                del self._by_user[subscription.user_id]

    def publish(self, user_id: str, seq: int | None) -> None:
        """The user's data changed; `seq` is their newest sequence number (None: nothing changed)."""
        if seq is None:
            return
        for subscription in self._by_user.get(user_id, ()):
            if seq > subscription.seq:
                subscription.seq = seq
                subscription._wake()

    def revoke_device(self, device_id: str) -> None:
        """The device was unlinked or got a new token: its open streams end with `revoked`."""
        for subscriptions in self._by_user.values():
            for subscription in subscriptions:
                if subscription.device_id == device_id:
                    subscription.revoked = True
                    subscription._wake()

    def count(self, user_id: str) -> int:
        return len(self._by_user.get(user_id, ()))
