class FrameError(ValueError):
    """Stable refusal codes (nips/road-events.md in Roadbeacon + docs/nostr-mesh-bridge.md 1.3)."""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(f"{code}: {detail}" if detail else code)
        self.code = code
