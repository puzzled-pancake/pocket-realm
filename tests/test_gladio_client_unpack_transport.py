import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / "tools" / "build_gladio_client.py"
BUILD_GRADLE = ROOT / "android" / "app" / "build.gradle.kts"


class GladioClientValidationLaneContractTest(unittest.TestCase):
    """Keep the retired bridge out of the ARM production runtime.

    Gladio remains reproducible only for the historical x86 validation lane;
    the RP6/ARM product path is Box64 + DXVK and must not rebuild or package an
    ARM libGL client/server pair.
    """

    @classmethod
    def setUpClass(cls):
        cls.source = BUILD_SCRIPT.read_text(encoding="utf-8")
        cls.gradle = BUILD_GRADLE.read_text(encoding="utf-8")

    def test_build_script_is_x86_validation_only(self):
        self.assertIn(
            'BUILD_ROOT = ROOT / "native" / ".build-x86_64" / "gladio-client"',
            self.source,
        )
        self.assertNotIn(".build-arm64", self.source)
        self.assertNotIn("TARGET_ABI", self.source)

    def test_arm_production_packaging_excludes_gladio(self):
        self.assertIn(
            "The GLX bridge is retained only for the historical x86",
            self.gradle,
        )
        self.assertIn('if (abi == "x86_64") {', self.gradle)
        self.assertIn('if (pocketAbi == "x86_64") {', self.gradle)
        self.assertNotIn(
            'if (pocketAbi == "arm64-v8a") {\n        from(File(xserverBuild, "libgladiorenderer.so"))',
            self.gradle,
        )


if __name__ == "__main__":
    unittest.main()
