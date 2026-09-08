"""Energy-based voice activity detection on 16-bit PCM."""

from __future__ import annotations

import numpy as np

FRAME_MS = 20


class EnergyVAD:
    """Frame-level RMS detector with an adaptive noise floor.

    A frame is speech when its RMS exceeds max(noise_floor * ratio, absolute_min). The floor
    tracks the minimum energy: it drops quickly and rises slowly, and only meaningfully rises
    while no speech is detected so that a long utterance does not raise the threshold onto itself.
    Speech becomes inactive only after `hangover_ms` of consecutive quiet frames.
    """

    def __init__(
        self,
        sample_rate: int = 16000,
        ratio: float = 3.5,
        absolute_min: float = 250.0,
        hangover_ms: int = 300,
        initial_floor: float = 100.0,
    ) -> None:
        self.sample_rate = sample_rate
        self.ratio = ratio
        self.absolute_min = absolute_min
        self.frame_bytes = sample_rate * FRAME_MS // 1000 * 2
        self.hangover_frames = max(1, hangover_ms // FRAME_MS)
        self.noise_floor = initial_floor
        self.speech_active = False
        self.speech_detected = False
        self._pending = bytearray()
        self._quiet_frames = 0
        self._frames_since_speech: int | None = None

    @property
    def threshold(self) -> float:
        return max(self.noise_floor * self.ratio, self.absolute_min)

    @property
    def silence_ms_since_speech(self) -> int | None:
        """Milliseconds since the last speech frame, or None if speech was never detected."""
        if self._frames_since_speech is None:
            return None
        return self._frames_since_speech * FRAME_MS

    def _update_floor(self, rms: float, is_speech: bool) -> None:
        if rms < self.noise_floor:
            self.noise_floor = self.noise_floor * 0.7 + rms * 0.3
        elif not is_speech:
            self.noise_floor += (rms - self.noise_floor) * 0.05
        else:
            self.noise_floor += (rms - self.noise_floor) * 0.001
        self.noise_floor = max(self.noise_floor, 1.0)

    def _process_frame(self, frame: bytes) -> bool | None:
        samples = np.frombuffer(frame, dtype=np.int16).astype(np.float32)
        rms = float(np.sqrt(np.mean(samples * samples))) if samples.size else 0.0
        is_speech = rms > self.threshold
        self._update_floor(rms, is_speech)

        transition: bool | None = None
        if is_speech:
            self.speech_detected = True
            self._frames_since_speech = 0
            self._quiet_frames = 0
            if not self.speech_active:
                self.speech_active = True
                transition = True
        else:
            if self._frames_since_speech is not None:
                self._frames_since_speech += 1
            if self.speech_active:
                self._quiet_frames += 1
                if self._quiet_frames >= self.hangover_frames:
                    self.speech_active = False
                    transition = False
        return transition

    def feed(self, chunk: bytes) -> list[bool]:
        """Consume PCM bytes; return the list of speech-state transitions (True=started, False=ended)."""
        self._pending.extend(chunk)
        transitions: list[bool] = []
        while len(self._pending) >= self.frame_bytes:
            frame = bytes(self._pending[: self.frame_bytes])
            del self._pending[: self.frame_bytes]
            transition = self._process_frame(frame)
            if transition is not None:
                transitions.append(transition)
        return transitions
