"""Sliding-window rate limits."""
from __future__ import annotations

from collections import defaultdict, deque


class RateLimit:
    def __init__(self, count: int, per_s: float):
        self.count, self.per_s = count, per_s
        self._hits: dict[object, deque] = defaultdict(deque)

    def _trim(self, key, now: float) -> deque:
        q = self._hits[key]
        while q and now - q[0] >= self.per_s:
            q.popleft()
        return q

    def allow(self, now: float, key: object = None) -> bool:
        """Check only."""
        return len(self._trim(key, now)) < self.count

    def hit(self, now: float, key: object = None) -> bool:
        """Check and count."""
        q = self._trim(key, now)
        if len(q) >= self.count:
            return False
        q.append(now)
        return True


class AirtimeBudget:
    """Own time on air over a rolling window (e.g. 0.1 % of an hour = 3.6 s). Silence when exhausted.
    `on_spend(now, seconds)` persists each transmission (the ledger), `restore` reloads them."""

    def __init__(self, fraction: float, window_s: float = 3600.0, on_spend=None):
        self.cap_s = fraction * window_s
        self.window_s = window_s
        self.on_spend = on_spend
        self._spent: deque[tuple[float, float]] = deque()

    def restore(self, entries, now: float) -> None:
        self._spent = deque((t, s) for t, s in sorted(entries) if 0 <= now - t < self.window_s)

    def used(self, now: float) -> float:
        while self._spent and now - self._spent[0][0] >= self.window_s:
            self._spent.popleft()
        return sum(s for _, s in self._spent)

    def allow(self, now: float, seconds: float) -> bool:
        return self.used(now) + seconds <= self.cap_s + 1e-9

    def spend(self, now: float, seconds: float) -> None:
        self._spent.append((now, seconds))
        if self.on_spend is not None:
            self.on_spend(now, seconds)
