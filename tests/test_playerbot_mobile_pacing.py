import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / "tools" / "build_o09_realm_runtime.py"
BOT_PROFILES = (
    ROOT / "android" / "app" / "src" / "main" / "java" /
    "com" / "pocketrealm" / "bots" / "BotProfiles.kt"
)
UINT32_MASK = (1 << 32) - 1


class ProbeGateModel:
    """Behavioral model of the source-overlay probe generation contract."""

    cadence_ms = 10_000
    timeout_ms = 15_000
    freshness_ms = 15_000
    maximum_delay_ms = 10_000

    def __init__(self):
        self.in_flight = False
        self.has_result = False
        self.sent_at = 0
        self.completed_at = 0
        self.has_started = False
        self.active_token = 0
        self.delay = UINT32_MASK

    @staticmethod
    def elapsed(now, then):
        return (now - then) & UINT32_MASK

    def begin(self, now):
        now &= UINT32_MASK
        if self.in_flight:
            if self.elapsed(now, self.sent_at) < self.timeout_ms:
                return None
            self.in_flight = False
            self.active_token = 0
            self.delay = UINT32_MASK
            self.has_result = True
            self.completed_at = now
        if self.has_started and self.elapsed(now, self.sent_at) < self.cadence_ms:
            return None
        self.has_started = True
        self.active_token = now
        self.in_flight = True
        self.sent_at = now
        return now

    def complete(self, sequence, now, delay, successful):
        if not self.in_flight or sequence != self.active_token:
            return False
        self.delay = delay if successful else UINT32_MASK
        self.has_result = True
        self.completed_at = now & UINT32_MASK
        self.in_flight = False
        self.active_token = 0
        return True

    def ready(self, now):
        return (
            self.has_result
            and self.delay < self.maximum_delay_ms
            and self.elapsed(now & UINT32_MASK, self.completed_at) <= self.freshness_ms
        )


class PlayerbotMobilePacingContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.builder = BUILD_SCRIPT.read_text(encoding="utf-8")
        cls.profiles = BOT_PROFILES.read_text(encoding="utf-8")

    def test_canonical_overlay_declares_and_bounds_activation_budget(self):
        self.assertIn('"id": "bounded-first-player-activation"', self.builder)
        self.assertIn("pocketGenerationYieldMs, pocketActivationBatchSize", self.builder)
        self.assertIn('GetIntDefault("PocketRealm.ActivationBatchSize", 5)', self.builder)
        self.assertIn("if (!pocketActivationBatchSize)", self.builder)
        self.assertIn("pocketActivationBatchSize = 1", self.builder)

    def test_first_player_deficit_scan_stops_after_bounded_activation_batch(self):
        self.assertIn(
            "uint32 pocketActivationRemaining = sPlayerbotAIConfig.pocketActivationBatchSize",
            self.builder,
        )
        self.assertIn("pocketActivationRemaining--", self.builder)
        self.assertIn(
            "if (!pocketActivationRemaining)\n                        currentAllowedBotCount = 0",
            self.builder,
        )

    def test_every_runtime_profile_emits_the_fixed_activation_setting(self):
        self.assertIn("PocketRealm.ActivationBatchSize = $activationBatchSize", self.profiles)
        self.assertIn("require(activationBatchSize in 1..64)", self.profiles)

    def test_lost_callback_expires_and_late_callback_cannot_clear_retry(self):
        gate = ProbeGateModel()
        first = gate.begin(1_000)
        self.assertIsNone(gate.begin(15_999))
        second = gate.begin(16_000)
        self.assertNotEqual(first, second)
        self.assertFalse(gate.complete(first, 16_001, 5, True))
        self.assertTrue(gate.in_flight)
        self.assertTrue(gate.complete(second, 16_010, 7, True))
        self.assertTrue(gate.ready(16_010))

    def test_failed_result_closes_gate_and_cadence_is_bounded(self):
        gate = ProbeGateModel()
        sequence = gate.begin(10_000)
        self.assertTrue(gate.complete(sequence, 10_020, 20, False))
        self.assertFalse(gate.ready(10_020))
        self.assertIsNone(gate.begin(19_999))
        self.assertIsNotNone(gate.begin(20_000))

    def test_timer_wrap_does_not_break_token_or_cadence(self):
        gate = ProbeGateModel()
        gate.has_started = True
        gate.sent_at = 0xFFFFF000
        self.assertIsNone(gate.begin((gate.sent_at + 9_999) & UINT32_MASK))
        expected = (gate.sent_at + 10_000) & UINT32_MASK
        self.assertEqual(expected, gate.begin(expected))

    def test_overlay_handles_failed_submission_and_all_probe_call_sites(self):
        self.assertIn("pocketDatabaseProbeActiveToken", self.builder)
        self.assertIn("if (!queued)", self.builder)
        self.assertIn("token != pocketDatabaseProbeActiveToken", self.builder)
        self.assertEqual(
            2,
            self.builder.count(
                'replace_anchor(bot_root / "PlayerbotLoginMgr.cpp", '
                "PB_LOGIN_DB_SCHEDULE_UPSTREAM"
            ),
        )


if __name__ == "__main__":
    unittest.main()
