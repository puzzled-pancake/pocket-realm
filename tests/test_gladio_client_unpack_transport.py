import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
BUILD_SCRIPT = ROOT / "tools" / "build_gladio_client.py"


class GladioClientUnpackTransportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = BUILD_SCRIPT.read_text(encoding="utf-8")

    def test_tracks_every_size_affecting_unpack_field(self):
        for field in (
            "unpackAlignment",
            "unpackRowLength",
            "unpackImageHeight",
            "unpackSkipPixels",
            "unpackSkipRows",
            "unpackSkipImages",
        ):
            self.assertIn(field, self.source)
        self.assertIn("clientState->pixelStore.unpackAlignment = 4", self.source)

    def test_span_includes_prefix_padding_and_last_row(self):
        self.assertIn("uint64_t rowStride", self.source)
        self.assertIn("(uint64_t)skipRows * rowStride", self.source)
        self.assertIn("(uint64_t)skipPixels * (uint64_t)pixelBytes", self.source)
        self.assertIn("(uint64_t)(height - 1) * rowStride", self.source)
        self.assertIn("(uint64_t)width * (uint64_t)pixelBytes", self.source)

        width, height, pixel_bytes = 4, 2, 4
        row_length, alignment = 8, 4
        skip_pixels, skip_rows = 2, 3
        row_bytes = row_length * pixel_bytes
        row_stride = (row_bytes + alignment - 1) & ~(alignment - 1)
        transmitted_span = (
            skip_rows * row_stride
            + skip_pixels * pixel_bytes
            + (height - 1) * row_stride
            + width * pixel_bytes
        )
        self.assertEqual(152, transmitted_span)
        self.assertGreater(transmitted_span, width * height * pixel_bytes)

    def test_compressed_payload_keeps_explicit_size(self):
        self.assertIn("int dataSize = compressedSize", self.source)
        self.assertIn("if (compressedSize == 0)", self.source)
        self.assertIn("else ArrayBuffer_putInt(&outputBuffer, compressedSize)", self.source)

    def test_known_good_x86_client_is_not_rebuilt_with_arm_transport_change(self):
        self.assertGreaterEqual(self.source.count('TARGET_ABI != "x86_64"'), 4)
        self.assertIn(
            'expected_sha256 = "7b60dafa5e071e11187c0936840201920e141160f0897609ce530cb6f69b60b6"',
            self.source,
        )


if __name__ == "__main__":
    unittest.main()
