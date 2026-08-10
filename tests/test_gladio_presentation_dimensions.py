import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
GLADIO = ROOT / "native" / "xserver-winlator" / "cpp" / "gladiorenderer"
RUNTIME = ROOT / "runtime" / "xserver-winlator" / "com" / "winlator"


class GladioPresentationDimensionsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.context = (GLADIO / "src" / "gl_context.c").read_text(
            encoding="utf-8"
        )
        cls.texture_native = (GLADIO / "src" / "gl_texture.c").read_text(
            encoding="utf-8"
        )
        cls.texture_java = (RUNTIME / "renderer" / "Texture.java").read_text(
            encoding="utf-8"
        )
        cls.glx_java = (
            RUNTIME / "xserver" / "extensions" / "GLXExtension.java"
        ).read_text(encoding="utf-8")

    def test_readback_uses_attachment_dimensions_not_display_dimensions(self):
        self.assertIn("getPresentationSize(context, framebuffer", self.context)
        self.assertIn("GLTexture_getDimensions", self.context)
        call_start = self.context.index("int result = (*jmethods->env)->CallIntMethod")
        call = self.context[
            call_start : self.context.index(
                "if ((*jmethods->env)->ExceptionCheck", call_start
            )
        ]
        self.assertIn("sourceWidth, sourceHeight", call)
        self.assertNotIn("currentRenderer->displaySize[0], currentRenderer->displaySize[1]", call)

    def test_shared_display_attachment_size_is_authoritative_across_contexts(self):
        header = (GLADIO / "include" / "gl_context.h").read_text(
            encoding="utf-8"
        )
        self.assertIn("short displayAttachmentSize[2];", header)
        self.assertIn("uint32_t displayAttachmentGeneration;", header)
        self.assertIn(
            "context->displayAttachmentSize[0] == width", self.context
        )
        self.assertIn(
            "context->displayAttachmentSize[1] == height", self.context
        )
        self.assertIn(
            "if (hasDisplayBuffers && attachmentSizeMatches) return;",
            self.context,
        )
        self.assertLess(
            self.context.index("getWindowSize(&context->jmethods"),
            self.context.index("if (hasDisplayBuffers && attachmentSizeMatches) return;"),
        )
        self.assertIn(
            "*width = context->displayAttachmentSize[0];", self.context
        )
        self.assertIn(
            "*height = context->displayAttachmentSize[1];", self.context
        )
        self.assertIn(
            "(GLuint)localObjectName == context->displayBufAttachments[i].texture",
            self.context,
        )
        destroy_start = self.context.index(
            "static void destroyDisplayBufAttachments"
        )
        destroy_end = self.context.index(
            "static void setCurrentRenderWindow", destroy_start
        )
        destroy = self.context[destroy_start:destroy_end]
        self.assertIn("context->displayAttachmentSize[0] = 0;", destroy)
        self.assertIn("context->displayAttachmentSize[1] = 0;", destroy)

    def test_mip_dimensions_are_derived_from_tracked_texture(self):
        self.assertIn("bool GLTexture_getDimensions", self.texture_native)
        self.assertIn("mipWidth = MAX(1, mipWidth / 2)", self.texture_native)
        self.assertIn("mipHeight = MAX(1, mipHeight / 2)", self.texture_native)

    def test_android_texture_reallocates_when_source_dimensions_change(self):
        self.assertIn("private short readbackWidth;", self.texture_java)
        self.assertIn("allocatedWidth != sourceWidth", self.texture_java)
        self.assertIn("allocateTexture(sourceWidth, sourceHeight, data)", self.texture_java)
        self.assertIn("readbackWidth = width;", self.texture_java)
        self.assertIn("readbackHeight = height;", self.texture_java)

    def test_glx_drawable_geometry_is_not_resized_to_intermediate_fbo(self):
        self.assertNotIn(
            "if (drawable.width != width || drawable.height != height) return 0;",
            self.glx_java,
        )
        self.assertIn("if (width <= 0 || height <= 0) return 1;", self.glx_java)


if __name__ == "__main__":
    unittest.main()
