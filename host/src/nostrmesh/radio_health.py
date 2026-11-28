"""Radio health (SPEC 3.2 "Radio health", R37): "node service up, radio down" counts as down.

The bridge needs the state of the radio itself, not of the software in front of it. On 2026-11-23 a
bridge radio stayed dead for 68 h while meshcored and the bridge kept running and the Local API session
stayed alive; the old watchdog read that session's frames and never fired.

Down, with the reason (field log `radio_down`):
- `api_says_down`: the adapter says the radio is not connected (Local API `device.connected` false or the
  session not paired; companion serial or KISS link broken);
- `send_failed`: a send came back `not_connected` (or the session closed), or `send_failures` sends in a
  row timed out;
- `rx_silence`: nothing from the radio itself for `rx_silence_s`: a received packet, or the answer to a
  probe (`radio.stats` on the Local API, a node request on companion serial) every `probe_s`. Frames of
  the Local API session alone do not count;
- `probe_timeout`: two probes in a row went unanswered.

Degraded (`radio_degraded`, `late_answers`): the node service reports late answers or unanswered
heartbeats (the probe's `link.lateReplies` / `link.slowHeartbeats` grew). meshcored does not expose
them yet; the bridge reads them when it does.

On a change the bridge logs, publishes its announcement with `["radio", state, since]` at once, runs
`[watchdog] alert_cmd` and, while down, answers due timed entries with a miss note (lt2svc). It never
exits. Recovery while down, one round per back-off (`recover_backoff_s`, 5, 15, 60 min, then every
60 min), each step once per round with `step_wait_s` between, stopping as soon as the radio is back:
(1) reboot the node by command when it still answers, (2) reopen the port, (3) the RTS and DTR reset,
(4) `[watchdog] recover_cmd` (e.g. uhubctl switching the USB port's power), and after the third round
(5) the alert says a person has to power-cycle the node. A node that stays degraded for
`degraded_reboot_s` while it still answers is rebooted by command once per back-off as well; 0 (the
default) never does: in the field test of 2026-11-27 a Heltec V3 stalled for 5 to 8 s a few times an
hour, and the stalls came back within 30 min of each of 5 reboots, which only cost reception.
Steps the adapter cannot do (a Local API node service reopens and resets the port itself) are logged
as `node_service`.
"""
from __future__ import annotations

import asyncio
import logging
import os

from . import fieldlog
from .radio import RadioError

log = logging.getLogger("nostrmesh.radio_health")

TICK_S = 10.0
DOWN_CODES = ("not_connected", "closed")
TIMEOUT_CODES = ("timeout",)
REASONS = ("api_says_down", "send_failed", "rx_silence", "probe_timeout")


class RadioHealth:
    def __init__(self, bridge, now: float):
        self.b = bridge
        self.cfg = bridge.cfg.watchdog
        self.state = "up"
        self.since = now
        self.reason = ""
        self.degraded = False
        self.degraded_since: float | None = None
        self.last_traffic = now        # a packet, or a probe answer that says the radio is connected
        self.send_fail_streak = 0
        self.send_down = False         # a not_connected send: down until a send or probe succeeds
        self.probe_fail_streak = 0
        self.last_probe = now
        self.last_link: dict | None = None
        self.round = 0                 # recovery rounds run since the radio went down
        self.next_round_at: float | None = None
        self.last_reboot: float | None = None
        self.power_alerted = False
        self._recovering = False

    # ------------------------------------------------------------ inputs

    def on_rx(self, now: float) -> None:
        self.last_traffic = now

    def on_send_ok(self, now: float) -> None:
        self.send_fail_streak = 0
        self.send_down = False

    def on_send_error(self, err: RadioError, now: float) -> None:
        code = getattr(err, "code", "")
        if code in DOWN_CODES:
            self.send_down = True
        elif code in TIMEOUT_CODES:
            self.send_fail_streak += 1

    def _api_up(self) -> bool:
        up = getattr(self.b.radio, "radio_connected", None)
        return True if up is None else bool(up)

    def _probe_result(self, r: dict, now: float) -> None:
        self.probe_fail_streak = 0
        link = r.get("link") if isinstance(r.get("link"), dict) else None
        fresh = True
        if link is not None and isinstance(link.get("lastHeartbeatOkAt"), str):
            # a node service that answers radio.stats from its cache says when the node last answered
            from datetime import datetime
            try:
                t = datetime.fromisoformat(link["lastHeartbeatOkAt"].replace("Z", "+00:00")).timestamp()
                fresh = now - t <= max(2 * self.cfg.probe_s, 120.0)
            except ValueError:
                pass
        if r.get("connected", True) is not False and fresh:
            self.last_traffic = now
            self.send_down = False
        if link is not None:
            late = sum(int(link.get(k) or 0) for k in ("lateReplies", "slowHeartbeats", "unansweredHeartbeats")
                       if isinstance(link.get(k), int))
            prev = self.last_link
            self.last_link = {"late": late}
            grew = prev is not None and late > prev["late"]
            self._set_degraded(grew, now)

    def _set_degraded(self, on: bool, now: float) -> None:
        if on and not self.degraded:
            self.degraded, self.degraded_since = True, now
            log.warning("radio degraded: the node service reports late answers")
            fieldlog.ev("radio_degraded", reason="late_answers")
            self.b.stats.inc("radio_degraded")
        elif not on and self.degraded:
            self.degraded, self.degraded_since = False, None
            fieldlog.ev("radio_degraded", reason="late_answers", cleared=True)

    # ------------------------------------------------------------ the state

    def down_reason(self, now: float) -> str | None:
        if not self._api_up():
            return "api_says_down"
        if self.send_down or self.send_fail_streak >= max(1, self.cfg.send_failures):
            return "send_failed"
        if self.probe_fail_streak >= 2:
            return "probe_timeout"
        if self.cfg.rx_silence_s > 0 and now - self.last_traffic > self.cfg.rx_silence_s:
            return "rx_silence"
        return None

    @property
    def up(self) -> bool:
        return self.state == "up"

    def can_send(self) -> bool:
        """Worth trying a send: the adapter does not say the radio is away (a send is the way back from a
        `send_failed` or `rx_silence` state)."""
        return self._api_up()

    async def tick(self, now: float) -> None:
        probe = getattr(self.b.radio, "probe", None)
        every = self.cfg.probe_s if self.state == "up" else min(self.cfg.probe_s, 60.0)  # down: look more often
        if probe is not None and self.cfg.probe_s > 0 and now - self.last_probe >= every:
            self.last_probe = now
            try:
                r = await self.b.loop.run_in_executor(None, probe)
                self._probe_result(r if isinstance(r, dict) else {}, self.b.clock())
            except RadioError as e:
                if e.code != "unsupported":
                    self.probe_fail_streak += 1
                    log.warning("radio probe: %s", e)
        now = self.b.clock()
        if now - self.b.started_at < self.cfg.start_grace_s:
            return
        why = self.down_reason(now)
        if why and self.state == "up":
            self._change("down", why, now)
        elif not why and self.state == "down":
            self._change("up", self.reason, now)
        if self.state == "down" and not self._recovering and self.next_round_at is not None \
                and now >= self.next_round_at:
            self._recovering = True
            self.b._spawn(self._recover_round())
        elif self.state == "up" and self.degraded and self.degraded_since is not None and self.cfg.degraded_reboot_s > 0 \
                and now - self.degraded_since >= self.cfg.degraded_reboot_s and not self._recovering \
                and (self.last_reboot is None or now - self.last_reboot >= self._backoff(0)):
            self._recovering = True
            self.b._spawn(self._reboot_degraded())

    def _backoff(self, i: int) -> float:
        bo = list(self.cfg.recover_backoff_s) or [300.0]
        return float(bo[min(i, len(bo) - 1)])

    def _change(self, state: str, reason: str, now: float) -> None:
        was = self.since
        self.state, self.since = state, now
        if state == "down":
            self.reason = reason
            self.round, self.power_alerted = 0, False
            self.next_round_at = now + self._backoff(0)
            log.error("radio down (%s); the bridge keeps running, announces it and tries to recover", reason)
            fieldlog.ev("radio_down", reason=reason)
            self.b.stats.inc("radio_down")
        else:
            self.next_round_at = None
            self.send_fail_streak, self.probe_fail_streak, self.send_down = 0, 0, False
            log.info("radio up again after %.0f min (%s)", (now - was) / 60, reason)
            fieldlog.ev("radio_up", reason=reason, down_s=round(now - was))
            self.b.stats.inc("radio_up")
        self.b.on_radio_state(state, reason, now)

    # ------------------------------------------------------------ recovery and hooks

    def _steps(self):
        r = self.b.radio
        return [("reboot", getattr(r, "reboot", None)), ("reopen", getattr(r, "reopen", None)),
                ("reset", getattr(r, "hard_reset", None)), ("hook", None)]

    async def _step(self, name: str, fn) -> str:
        if name == "hook":
            if not self.cfg.recover_cmd:
                return "not_configured"
            return await self.run_hook(self.cfg.recover_cmd, "recover")
        if fn is None:
            return "node_service"  # a Local API node service reopens and resets the port itself
        if name == "reboot" and self.probe_fail_streak and not self.degraded:
            return "skipped_no_answer"
        try:
            return str(await self.b.loop.run_in_executor(None, fn))
        except Exception as e:  # noqa: BLE001
            return f"failed: {str(e)[:80]}"

    async def _recover_round(self) -> None:
        try:
            self.round += 1
            for name, fn in self._steps():
                if self.state == "up":
                    return
                res = await self._step(name, fn)
                log.warning("radio recovery round %d, step %s: %s", self.round, name, res)
                fieldlog.ev("recover", step=name, result=res, round=self.round)
                self.b.stats.inc(f"radio_recover_{name}")
                if name == "reboot" and res == "ok":
                    self.last_reboot = self.b.clock()
                if self.cfg.step_wait_s > 0:
                    await asyncio.sleep(self.cfg.step_wait_s)
                if self.down_reason(self.b.clock()) is None:
                    return
            if self.round >= max(3, len(self.cfg.recover_backoff_s)) and not self.power_alerted and self.state == "down":
                self.power_alerted = True
                fieldlog.ev("recover", step="person", result="power-cycle the node", round=self.round)
                await self.alert("power_cycle_needed", self.reason)
        finally:
            if self.state == "down":
                self.next_round_at = self.b.clock() + self._backoff(self.round)
            self._recovering = False

    async def _reboot_degraded(self) -> None:
        try:
            fn = getattr(self.b.radio, "reboot", None)
            res = await self._step("reboot", fn)
            self.last_reboot = self.b.clock()
            fieldlog.ev("recover", step="reboot", result=res, degraded=True)
            log.warning("radio degraded for %.0f min: reboot by command: %s",
                        self.cfg.degraded_reboot_s / 60, res)
        finally:
            self._recovering = False

    async def alert(self, state: str, reason: str) -> str:
        if not self.cfg.alert_cmd:
            return "not_configured"
        return await self.run_hook(self.cfg.alert_cmd, state, reason)

    async def run_hook(self, cmd: str, state: str, reason: str | None = None) -> str:
        env = dict(os.environ, NOSTRMESH_RADIO_STATE=state, NOSTRMESH_RADIO_REASON=reason or self.reason or "",
                   NOSTRMESH_BRIDGE=getattr(self.b.cfg.bridge, "name", "") or "nostrmesh")
        try:
            p = await asyncio.create_subprocess_shell(cmd, env=env, stdout=asyncio.subprocess.DEVNULL,
                                                      stderr=asyncio.subprocess.PIPE)
            try:
                _, err = await asyncio.wait_for(p.communicate(), self.cfg.hook_timeout_s)
            except asyncio.TimeoutError:
                p.kill()
                res = "timeout"
            else:
                res = "ok" if p.returncode == 0 else f"exit {p.returncode}"
                if p.returncode and err:
                    log.warning("hook %r: %s", state, err.decode(errors="replace")[-200:])
        except OSError as e:
            res = f"failed: {e}"
        fieldlog.ev("hook", what=state, result=res)
        self.b.stats.inc(f"radio_hook_{state}")
        return res
