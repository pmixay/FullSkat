from fullskat_server.ratelimit import PairLimiter, SlidingWindowLimiter


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def test_per_minute_window() -> None:
    clock = FakeClock()
    limiter = SlidingWindowLimiter(per_minute=3, per_day=100, clock=clock)
    for _ in range(3):
        assert limiter.check("chat", "k").allowed
    denied = limiter.check("chat", "k")
    assert not denied.allowed
    assert denied.reason == "rate_limited"
    assert 1 <= denied.retry_after <= 60
    clock.advance(denied.retry_after)
    assert limiter.check("chat", "k").allowed


def test_keys_and_scopes_are_independent() -> None:
    limiter = SlidingWindowLimiter(per_minute=1, per_day=10, clock=FakeClock())
    assert limiter.check("chat", "a").allowed
    assert not limiter.check("chat", "a").allowed
    assert limiter.check("chat", "b").allowed
    assert limiter.check("other", "a").allowed


def test_per_day_window() -> None:
    clock = FakeClock()
    limiter = SlidingWindowLimiter(per_minute=100, per_day=5, clock=clock)
    for _ in range(5):
        assert limiter.check("chat", "k").allowed
        clock.advance(120)
    denied = limiter.check("chat", "k")
    assert not denied.allowed
    assert denied.retry_after > 60
    clock.advance(denied.retry_after)
    assert limiter.check("chat", "k").allowed


def test_pair_limiter_attempts_and_lockout() -> None:
    clock = FakeClock()
    limiter = PairLimiter(per_minute=5, lock_failures=3, lock_minutes=15, clock=clock)
    for _ in range(5):
        assert limiter.check("1.2.3.4").allowed
    assert limiter.check("1.2.3.4").reason == "rate_limited"

    clock.advance(61)
    limiter.record_failure("1.2.3.4")
    limiter.record_failure("1.2.3.4")
    assert limiter.check("1.2.3.4").allowed  # 2 failures: not locked yet
    limiter.record_failure("1.2.3.4")
    locked = limiter.check("1.2.3.4")
    assert not locked.allowed
    assert locked.reason == "locked"
    assert 14 * 60 < locked.retry_after <= 15 * 60
    assert limiter.check("5.6.7.8").allowed  # other IPs unaffected

    clock.advance(15 * 60 + 1)
    assert limiter.check("1.2.3.4").allowed


def test_pair_success_resets_failures() -> None:
    limiter = PairLimiter(per_minute=100, lock_failures=2, lock_minutes=1, clock=FakeClock())
    limiter.record_failure("ip")
    limiter.record_success("ip")
    limiter.record_failure("ip")
    assert limiter.check("ip").allowed
