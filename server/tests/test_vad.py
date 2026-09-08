import numpy as np

from fullskat_server.vad import EnergyVAD

RATE = 16000


def silence(ms: int) -> bytes:
    return np.zeros(RATE * ms // 1000, dtype=np.int16).tobytes()


def tone(ms: int, amplitude: int = 8000, freq: float = 440.0) -> bytes:
    t = np.arange(RATE * ms // 1000) / RATE
    return (np.sin(2 * np.pi * freq * t) * amplitude).astype(np.int16).tobytes()


def test_silence_never_triggers() -> None:
    vad = EnergyVAD(sample_rate=RATE)
    assert vad.feed(silence(2000)) == []
    assert not vad.speech_active
    assert not vad.speech_detected
    assert vad.silence_ms_since_speech is None


def test_tone_then_silence_produces_transitions() -> None:
    vad = EnergyVAD(sample_rate=RATE)
    assert vad.feed(silence(500)) == []
    assert vad.feed(tone(500)) == [True]
    assert vad.speech_active
    assert vad.speech_detected
    assert vad.silence_ms_since_speech == 0

    transitions = vad.feed(silence(1500))
    assert transitions == [False]
    assert not vad.speech_active
    assert vad.silence_ms_since_speech is not None
    assert 1400 <= vad.silence_ms_since_speech <= 1500


def test_partial_frames_are_buffered() -> None:
    vad = EnergyVAD(sample_rate=RATE)
    audio = tone(200)
    transitions: list[bool] = []
    for offset in range(0, len(audio), 333):  # not a multiple of the 640-byte frame
        transitions.extend(vad.feed(audio[offset : offset + 333]))
    assert transitions == [True]


def test_quiet_background_noise_is_not_speech() -> None:
    rng = np.random.default_rng(0)
    noise = (rng.normal(0, 60, RATE * 2)).astype(np.int16).tobytes()
    vad = EnergyVAD(sample_rate=RATE)
    assert vad.feed(noise) == []
    assert vad.feed(tone(300)) == [True]
