using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile.EditorTools
{
    // Repairs for exported courses: custom shaders that came through as blank placeholders,
    // and scenery with no collision to walk on.
    public static class CourseFixTools
    {
        static readonly string[] MainTexNames = { "_BaseMap", "_MainTex", "_Albedo", "_AlbedoMap", "_BaseColorMap", "_Diffuse", "_DiffuseMap", "_MainTexture", "_Texture", "_Tex" };
        static readonly string[] ColorNames = { "_BaseColor", "_Color", "_MainColor", "_Tint", "_TintColor", "_AlbedoColor" };
        static readonly string[] NormalNames = { "_BumpMap", "_NormalMap", "_Normal", "_NormalTex" };
        static readonly string[] EmissionNames = { "_EmissionMap", "_Emission", "_EmissiveMap" };

        [MenuItem("Mini Golf/Fix Materials (switch to standard shader)", priority = 21)]
        public static void FixMaterials()
        {
            if (!EditorUtility.DisplayDialog("Mini Golf",
                "This switches every material that uses one of the game's own shaders to Unity's standard lit shader, " +
                "keeping its textures and colours. It changes the material files in this project.\n\nContinue?", "Fix materials", "Cancel"))
                return;

            var report = new StringBuilder();
            EnsureRenderPipeline(report);

            bool urp = GraphicsSettings.defaultRenderPipeline != null;
            Shader lit = urp ? Shader.Find("Universal Render Pipeline/Lit") : Shader.Find("Standard");
            Shader unlit = urp ? Shader.Find("Universal Render Pipeline/Unlit") : Shader.Find("Unlit/Texture");
            Shader sky = Shader.Find("Skybox/Procedural");
            if (!lit)
            {
                EditorUtility.DisplayDialog("Mini Golf", "Couldn't find a standard lit shader in this project.", "OK");
                return;
            }

            var guids = AssetDatabase.FindAssets("t:Material", new[] { "Assets" });
            int converted = 0, skipped = 0;
            try
            {
                for (int i = 0; i < guids.Length; i++)
                {
                    string path = AssetDatabase.GUIDToAssetPath(guids[i]);
                    if (path.StartsWith("Assets/MiniGolfMobile")) continue;
                    if (i % 50 == 0 && EditorUtility.DisplayCancelableProgressBar("Fixing materials", path, (float)i / guids.Length)) break;

                    var mat = AssetDatabase.LoadAssetAtPath<Material>(path);
                    if (!mat || !NeedsFix(mat)) { skipped++; continue; }

                    string oldShader = mat.shader ? mat.shader.name : "";
                    if (Regex.IsMatch(oldShader + mat.name, "TextMesh|TMP|/UI|Sprite|Font", RegexOptions.IgnoreCase)) { skipped++; continue; }

                    var saved = ReadSavedProperties(mat);
                    Undo.RecordObject(mat, "Fix material");

                    if (Regex.IsMatch(oldShader + mat.name, "sky", RegexOptions.IgnoreCase) && sky)
                    {
                        mat.shader = sky;
                    }
                    else
                    {
                        bool particle = Regex.IsMatch(oldShader + mat.name, "particle|vfx|fx_|glow|additive", RegexOptions.IgnoreCase);
                        mat.shader = particle && unlit ? unlit : lit;
                        Apply(mat, saved, urp);
                    }
                    EditorUtility.SetDirty(mat);
                    converted++;
                }
            }
            finally
            {
                EditorUtility.ClearProgressBar();
            }
            AssetDatabase.SaveAssets();
            report.AppendLine($"Materials switched: {converted}  (left alone: {skipped})");
            Debug.Log("Mini Golf: " + report);
            EditorUtility.DisplayDialog("Mini Golf", report.ToString(), "OK");
        }

        // Custom shaders from the export live under Assets/; Unity's own shaders live in packages or are built in.
        static bool NeedsFix(Material mat)
        {
            var s = mat.shader;
            if (!s || s.name == "Hidden/InternalErrorShader" || !s.isSupported) return true;
            return AssetDatabase.GetAssetPath(s).StartsWith("Assets/");
        }

        class Saved
        {
            public readonly Dictionary<string, (Texture tex, Vector2 scale, Vector2 offset)> textures = new();
            public readonly Dictionary<string, Color> colors = new();
            public readonly Dictionary<string, float> floats = new();
        }

        // Reads what the material stored, even for properties its (placeholder) shader doesn't declare.
        static Saved ReadSavedProperties(Material mat)
        {
            var saved = new Saved();
            var so = new SerializedObject(mat);
            var texEnvs = so.FindProperty("m_SavedProperties.m_TexEnvs");
            for (int i = 0; texEnvs != null && i < texEnvs.arraySize; i++)
            {
                var e = texEnvs.GetArrayElementAtIndex(i);
                var tex = e.FindPropertyRelative("second.m_Texture").objectReferenceValue as Texture;
                if (tex) saved.textures[e.FindPropertyRelative("first").stringValue] =
                    (tex, e.FindPropertyRelative("second.m_Scale").vector2Value, e.FindPropertyRelative("second.m_Offset").vector2Value);
            }
            var colors = so.FindProperty("m_SavedProperties.m_Colors");
            for (int i = 0; colors != null && i < colors.arraySize; i++)
            {
                var e = colors.GetArrayElementAtIndex(i);
                saved.colors[e.FindPropertyRelative("first").stringValue] = e.FindPropertyRelative("second").colorValue;
            }
            var floats = so.FindProperty("m_SavedProperties.m_Floats");
            for (int i = 0; floats != null && i < floats.arraySize; i++)
            {
                var e = floats.GetArrayElementAtIndex(i);
                saved.floats[e.FindPropertyRelative("first").stringValue] = e.FindPropertyRelative("second").floatValue;
            }
            return saved;
        }

        static void Apply(Material mat, Saved saved, bool urp)
        {
            string mainProp = urp ? "_BaseMap" : "_MainTex";
            string colorProp = urp ? "_BaseColor" : "_Color";

            var main = Pick(saved.textures, MainTexNames, n => Regex.IsMatch(n, "albedo|base|main|diff|col", RegexOptions.IgnoreCase)
                                                             && !Regex.IsMatch(n, "normal|bump|mask|emis|light|detail|noise", RegexOptions.IgnoreCase));
            if (main.HasValue)
            {
                mat.SetTexture(mainProp, main.Value.tex);
                mat.SetTextureScale(mainProp, main.Value.scale);
                mat.SetTextureOffset(mainProp, main.Value.offset);
                if (urp && mat.HasProperty("_MainTex")) mat.SetTexture("_MainTex", main.Value.tex);
            }

            Color color = Color.white;
            foreach (var n in ColorNames)
                if (saved.colors.TryGetValue(n, out var c)) { color = c; break; }
            if (!main.HasValue && color == Color.white)
            {
                // Untextured and uncoloured would stay plain white; use any colour the material had instead.
                var any = saved.colors.FirstOrDefault(kv => !Regex.IsMatch(kv.Key, "emis|spec|rim|fresnel", RegexOptions.IgnoreCase));
                if (any.Key != null) color = any.Value;
            }
            color.a = Mathf.Max(color.a, 0.01f);
            mat.SetColor(colorProp, color);

            var normal = Pick(saved.textures, NormalNames, n => Regex.IsMatch(n, "normal|bump", RegexOptions.IgnoreCase));
            if (normal.HasValue && mat.HasProperty("_BumpMap"))
            {
                mat.SetTexture("_BumpMap", normal.Value.tex);
                mat.EnableKeyword("_NORMALMAP");
            }

            var emission = Pick(saved.textures, EmissionNames, n => Regex.IsMatch(n, "emis", RegexOptions.IgnoreCase));
            if (emission.HasValue && mat.HasProperty("_EmissionMap"))
            {
                mat.SetTexture("_EmissionMap", emission.Value.tex);
                mat.SetColor("_EmissionColor", saved.colors.TryGetValue("_EmissionColor", out var ec) ? ec : Color.white);
                mat.EnableKeyword("_EMISSION");
                mat.globalIlluminationFlags = MaterialGlobalIlluminationFlags.BakedEmissive;
            }

            // Leaves, grass, fences etc. use cut-out transparency.
            if (saved.floats.TryGetValue("_Cutoff", out float cutoff) && cutoff > 0f && mat.HasProperty("_Cutoff")
                || Regex.IsMatch(mat.name, "leaf|leaves|foliage|grass|plant|fence|cutout", RegexOptions.IgnoreCase))
            {
                mat.SetFloat("_Cutoff", cutoff > 0 ? cutoff : 0.5f);
                if (mat.HasProperty("_AlphaClip")) mat.SetFloat("_AlphaClip", 1f);
                mat.EnableKeyword("_ALPHATEST_ON");
                mat.renderQueue = (int)RenderQueue.AlphaTest;
            }

            if (mat.HasProperty("_Smoothness")) mat.SetFloat("_Smoothness", 0.2f);
            if (mat.HasProperty("_Glossiness")) mat.SetFloat("_Glossiness", 0.2f);
        }

        static (Texture tex, Vector2 scale, Vector2 offset)? Pick(Dictionary<string, (Texture, Vector2, Vector2)> textures,
            string[] preferred, System.Func<string, bool> fallback)
        {
            foreach (var n in preferred)
                if (textures.TryGetValue(n, out var t)) return t;
            foreach (var kv in textures)
                if (fallback(kv.Key)) return kv.Value;
            return null;
        }

        // Standard shaders only draw when the project has a render pipeline asset assigned.
        static void EnsureRenderPipeline(StringBuilder report)
        {
            if (GraphicsSettings.defaultRenderPipeline)
            {
                report.AppendLine($"Render pipeline: {GraphicsSettings.defaultRenderPipeline.name}");
                return;
            }
            var asset = AssetDatabase.FindAssets("t:RenderPipelineAsset")
                .Select(g => AssetDatabase.LoadAssetAtPath<RenderPipelineAsset>(AssetDatabase.GUIDToAssetPath(g)))
                .FirstOrDefault(a => a);
            if (asset)
            {
                GraphicsSettings.defaultRenderPipeline = asset;
                int current = QualitySettings.GetQualityLevel();
                for (int i = 0; i < QualitySettings.names.Length; i++)
                {
                    QualitySettings.SetQualityLevel(i, false);
                    QualitySettings.renderPipeline = asset;
                }
                QualitySettings.SetQualityLevel(current, false);
                report.AppendLine($"Render pipeline set to: {asset.name}");
            }
            else
            {
                report.AppendLine("No render pipeline asset found; using the built-in Standard shader.");
            }
        }

        // ---------- Walking collision ----------

        [MenuItem("Mini Golf/Add Walking Collision To Open Scene", priority = 22)]
        public static void AddWalkingCollision()
        {
            int added = 0;
            var scene = SceneManager.GetActiveScene();
            foreach (var root in scene.GetRootGameObjects())
            foreach (var mf in root.GetComponentsInChildren<MeshFilter>(true))
            {
                var go = mf.gameObject;
                if (!mf.sharedMesh || go.GetComponent<Collider>() || !go.GetComponent<MeshRenderer>()) continue;
                // Skip UI, effects, skies and tiny props.
                if (go.GetComponentInParent<Canvas>(true) || go.GetComponentInParent<ParticleSystem>(true)) continue;
                if (Regex.IsMatch(go.name, "sky|water|cloud|fx|vfx|particle|glow|light|shadow|decal|flag|line", RegexOptions.IgnoreCase)) continue;
                var size = mf.sharedMesh.bounds.size;
                size.Scale(go.transform.lossyScale);
                if (size.magnitude < 0.3f) continue;

                var mc = Undo.AddComponent<MeshCollider>(go);
                mc.sharedMesh = mf.sharedMesh;
                added++;
            }
            EditorSceneManager.MarkSceneDirty(scene);
            string msg = $"Added collision to {added} objects. Save the scene (Ctrl+S).";
            Debug.Log("Mini Golf: " + msg);
            EditorUtility.DisplayDialog("Mini Golf", msg, "OK");
        }
    }
}
