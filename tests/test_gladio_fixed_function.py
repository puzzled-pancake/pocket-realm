import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
GLADIO = ROOT / "native" / "xserver-winlator" / "cpp" / "gladiorenderer"


class GladioFixedFunctionContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.renderer = (GLADIO / "src" / "gl_renderer.c").read_text(
            encoding="utf-8"
        )
        cls.shader = (GLADIO / "src" / "shader_material.c").read_text(
            encoding="utf-8"
        )
        cls.renderer_header = (GLADIO / "include" / "gl_renderer.h").read_text(
            encoding="utf-8"
        )
        cls.vao_header = (GLADIO / "include" / "gl_vao.h").read_text(
            encoding="utf-8"
        )

    def test_lit_primary_color_is_not_multiplied_by_current_color(self):
        self.assertNotIn(
            "gd_FrontColor.rgb * gd_TotalDiffuseLight", self.shader
        )
        self.assertIn(
            "gd_TotalDiffuseLight + gd_TotalSpecularLight", self.shader
        )
        self.assertIn(
            "if (gd_ColorMaterialEnabled && colorMaterialFace)", self.shader
        )
        self.assertIn("materialDiffuse = gd_Color", self.shader)

    def test_diffuse_material_preserves_alpha(self):
        self.assertIn("float diffuse[4];", self.renderer_header)
        self.assertIn(
            "const float diffuse[] = {0.8f, 0.8f, 0.8f, 1.0f}",
            self.renderer,
        )
        init_body = self.renderer[
            self.renderer.index("void GLRenderer_initOnEGLContext") :
            self.renderer.index("static void bindVertexBuffer")
        ]
        self.assertIn("initMaterials(renderer);", init_body)
        self.assertIn("vec4 diffuse;", self.shader)
        self.assertIn(
            "glUniform4fv(material->location.materials[i][1]", self.shader
        )
        self.assertIn("gd_FrontColor.a = materialDiffuse.a", self.shader)

    def test_immediate_arrays_restore_current_normal_and_texcoord(self):
        self.assertIn(
            "glVertexAttrib3fv(attribLocations[NORMAL_ARRAY_INDEX], renderer->state.normal)",
            self.renderer,
        )
        self.assertIn(
            "glVertexAttrib4fv(attribLocations[j], renderer->state.texCoords[i])",
            self.renderer,
        )

    def test_only_normalize_capability_mutates_normalize_state(self):
        normalize_assignment = "renderer->state.normalize = state;"
        self.assertEqual(1, self.renderer.count(normalize_assignment))
        compatibility_start = self.renderer.index("case GL_LINE_SMOOTH:")
        normalize_case = self.renderer.index("case GL_NORMALIZE:", compatibility_start)
        self.assertIn("break;", self.renderer[compatibility_start:normalize_case])

    def test_guest_material_writes_respect_color_material_tracking(self):
        self.assertIn("GLRenderer_setGuestMaterialParams", self.renderer)
        self.assertIn(
            "trackedMode != GL_AMBIENT && trackedMode != GL_AMBIENT_AND_DIFFUSE",
            self.renderer,
        )
        self.assertIn(
            "trackedMode != GL_DIFFUSE && trackedMode != GL_AMBIENT_AND_DIFFUSE",
            self.renderer,
        )

    def test_legacy_zero_stride_remains_tightly_packed_with_fragment_program(self):
        read_start = self.vao_header.index("#define GL_READ_VERTEX_ARRAY")
        read_end = self.vao_header.index(
            "extern void GLVertexArrayObject_setAttribState", read_start
        )
        read_macro = self.vao_header[read_start:read_end]
        self.assertIn(
            "stride > 0 ? stride : (size * sizeofGLType(type))", read_macro
        )
        self.assertNotIn("hasBoundProgram", read_macro)


if __name__ == "__main__":
    unittest.main()
