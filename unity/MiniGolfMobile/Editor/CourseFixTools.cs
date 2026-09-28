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
            Shader lit = urp ? Shader.Find("Universal Render Pipeline/Lit") : Shader.Find("MiniGolf/Lit Vertex Color");
            if (!lit) lit = Shader.Find("Standard");
            var vertexColored = MaterialsOnVertexColoredMeshes();
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
                        if (mat.HasProperty("_UseVertexColor"))
                            mat.SetFloat("_UseVertexColor", vertexColored.Contains(mat) ? 1f : 0f);
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
            if (s.name == "Standard" || s.name == "MiniGolf/Lit Vertex Color") return true;
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
                                                             && !Regex.IsMatch(n, "normal|bump|mask|emis|light|detail|noise", RegexOptions.IgnoreCase), anyAsLastResort: true);
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

            // Start with no glow; leftover emission colours from the original shader would make everything glow white.
            if (mat.HasProperty("_EmissionColor")) mat.SetColor("_EmissionColor", Color.black);
            if (mat.HasProperty("_EmissionMap")) mat.SetTexture("_EmissionMap", null);
            mat.DisableKeyword("_EMISSION");

            var emission = Pick(saved.textures, EmissionNames, n => Regex.IsMatch(n, "emis", RegexOptions.IgnoreCase));
            if (emission.HasValue && mat.HasProperty("_EmissionMap"))
            {
                mat.SetTexture("_EmissionMap", emission.Value.tex);
                Color ec = saved.colors.TryGetValue("_EmissionColor", out var savedEc) ? savedEc : Color.white;
                mat.SetColor("_EmissionColor", ec.maxColorComponent > 1f ? ec / ec.maxColorComponent : ec);
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
            string[] preferred, System.Func<string, bool> fallback, bool anyAsLastResort = false)
        {
            foreach (var n in preferred)
                if (textures.TryGetValue(n, out var t)) return t;
            foreach (var kv in textures)
                if (fallback(kv.Key)) return kv.Value;
            if (anyAsLastResort)
                foreach (var kv in textures.OrderBy(k => k.Key))
                    if (!Regex.IsMatch(kv.Key, "normal|bump|mask|emis|light|detail|noise|height|occlu|rough|metal|spec|flow|dist|cube|refl|matcap", RegexOptions.IgnoreCase)
                        && !Regex.IsMatch(kv.Value.Item1.name, "normal|_n$|_nrm|mask|noise", RegexOptions.IgnoreCase))
                        return kv.Value;
            return null;
        }

        // Materials used by meshes that carry vertex colours in the open scene.
        static HashSet<Material> MaterialsOnVertexColoredMeshes()
        {
            var set = new HashSet<Material>();
            foreach (var mr in Object.FindObjectsByType<MeshRenderer>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                var mf = mr.GetComponent<MeshFilter>();
                if (!mf || !mf.sharedMesh || !mf.sharedMesh.HasVertexAttribute(VertexAttribute.Color)) continue;
                foreach (var m in mr.sharedMaterials) if (m) set.Add(m);
            }
            return set;
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

        // ---------- Lighting ----------

        [MenuItem("Mini Golf/Fix Lighting (fog, sky, sun)", priority = 23)]
        public static void FixLighting()
        {
            var report = new StringBuilder();

            // The game's fog was tuned for its own shaders; with standard shaders it washes everything out white.
            if (RenderSettings.fog) report.AppendLine("Fog turned off");
            RenderSettings.fog = false;

            // A normal sky, used for both the background and ambient light.
            const string skyPath = "Assets/MiniGolfMobile/Generated/Sky.mat";
            EnsureFolder("Assets/MiniGolfMobile/Generated");
            var skyMat = AssetDatabase.LoadAssetAtPath<Material>(skyPath);
            if (!skyMat && Shader.Find("Skybox/Procedural"))
            {
                skyMat = new Material(Shader.Find("Skybox/Procedural"));
                skyMat.SetFloat("_Exposure", 1.1f);
                skyMat.SetFloat("_AtmosphereThickness", 0.8f);
                AssetDatabase.CreateAsset(skyMat, skyPath);
            }
            if (skyMat) RenderSettings.skybox = skyMat;
            RenderSettings.ambientMode = AmbientMode.Skybox;
            RenderSettings.ambientIntensity = 1f;
            RenderSettings.reflectionIntensity = 0.6f;
            report.AppendLine("Sky and ambient light reset");

            // Baked-only lights do nothing without the (missing) lightmaps, so make the sun live and tame the rest.
            var lights = Object.FindObjectsByType<Light>(FindObjectsInactive.Include, FindObjectsSortMode.None);
            var sun = lights.Where(l => l.type == LightType.Directional).OrderByDescending(l => l.intensity).FirstOrDefault();
            if (!sun)
            {
                sun = new GameObject("Sun").AddComponent<Light>();
                sun.type = LightType.Directional;
                sun.transform.rotation = Quaternion.Euler(50f, -30f, 0f);
                Undo.RegisterCreatedObjectUndo(sun.gameObject, "Add sun");
                report.AppendLine("Added a sun");
            }
            Undo.RecordObject(sun, "Fix sun");
            sun.gameObject.SetActive(true);
            sun.enabled = true;
            sun.lightmapBakeType = LightmapBakeType.Realtime;
            sun.intensity = Mathf.Clamp(sun.intensity, 0.8f, 1.3f);
            sun.shadows = LightShadows.Soft;
            RenderSettings.sun = sun;

            int tamed = 0;
            foreach (var l in lights)
            {
                if (l == sun) continue;
                Undo.RecordObject(l, "Fix light");
                if (l.type == LightType.Directional) { l.enabled = false; tamed++; continue; }
                if (l.intensity > 2f) { l.intensity = 2f; tamed++; }
                l.lightmapBakeType = LightmapBakeType.Realtime;
                l.shadows = LightShadows.None;
            }
            report.AppendLine($"Sun: {sun.name}; other lights adjusted: {tamed}");

            DynamicGI.UpdateEnvironment();
            EditorSceneManager.MarkSceneDirty(SceneManager.GetActiveScene());
            report.AppendLine("Save the scene (Ctrl+S).");
            Debug.Log("Mini Golf: " + report);
            EditorUtility.DisplayDialog("Mini Golf", report.ToString(), "OK");
        }

        static void EnsureFolder(string path)
        {
            if (AssetDatabase.IsValidFolder(path)) return;
            string parent = System.IO.Path.GetDirectoryName(path).Replace('\\', '/');
            EnsureFolder(parent);
            AssetDatabase.CreateFolder(parent, System.IO.Path.GetFileName(path));
        }

        // ---------- Helper objects ----------

        // Trigger zones, aim-pin markers and hole covers have renderers that the game's shaders kept invisible.
        [MenuItem("Mini Golf/Hide Helper Boxes", priority = 24)]
        public static void HideHelperBoxes()
        {
            int hidden = 0;
            foreach (var r in Object.FindObjectsByType<MeshRenderer>(FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                var go = r.gameObject;
                bool trigger = go.GetComponents<Collider>().Any(c => c.isTrigger);
                bool helperName = Regex.IsMatch(go.name, "trigger|keepalive|holecover|trapperkeeper|^collider|bounds|blocker|invisible", RegexOptions.IgnoreCase);
                bool pinMarker = go.GetComponentsInParent<MonoBehaviour>(true).Any(m => m && (m.GetType().Name == "Pin" || m.GetType().Name == "CustomPin"));
                bool broken = r.sharedMaterials.Any(m => !m || !m.shader || m.shader.name == "Hidden/InternalErrorShader" || !m.shader.isSupported);
                bool simpleBox = go.GetComponent<MeshFilter>() is var f && f && f.sharedMesh && f.sharedMesh.name.StartsWith("Cube");
                if (!(trigger || helperName || pinMarker || (broken && simpleBox)) || !r.enabled) continue;
                if (Regex.IsMatch(go.name, "water", RegexOptions.IgnoreCase) && !trigger) continue;
                Undo.RecordObject(r, "Hide helper");
                r.enabled = false;
                hidden++;
            }
            EditorSceneManager.MarkSceneDirty(SceneManager.GetActiveScene());
            string msg = $"Hid {hidden} helper objects. Save the scene (Ctrl+S).";
            Debug.Log("Mini Golf: " + msg);
            EditorUtility.DisplayDialog("Mini Golf", msg, "OK");
        }

        [MenuItem("Mini Golf/Copy Selected Object's Material Info", priority = 41)]
        public static void CopyMaterialInfo()
        {
            var go = Selection.activeGameObject;
            if (!go) { EditorUtility.DisplayDialog("Mini Golf", "Click an object in the Scene view first.", "OK"); return; }
            var sb = new StringBuilder();
            sb.AppendLine($"Object: {go.name}");
            var mf = go.GetComponent<MeshFilter>();
            if (mf && mf.sharedMesh)
                sb.AppendLine($"Mesh: {mf.sharedMesh.name}  vertexColors={mf.sharedMesh.HasVertexAttribute(VertexAttribute.Color)}  uv2={mf.sharedMesh.HasVertexAttribute(VertexAttribute.TexCoord1)}");
            var r = go.GetComponent<Renderer>();
            if (r)
                foreach (var m in r.sharedMaterials)
                {
                    if (!m) continue;
                    sb.AppendLine($"Material: {m.name}  shader={(m.shader ? m.shader.name : "none")}  path={AssetDatabase.GetAssetPath(m)}");
                    var saved = ReadSavedProperties(m);
                    foreach (var kv in saved.textures) sb.AppendLine($"  tex {kv.Key} = {kv.Value.tex.name}");
                    foreach (var kv in saved.colors) sb.AppendLine($"  color {kv.Key} = {kv.Value}");
                    foreach (var kv in saved.floats.Take(30)) sb.AppendLine($"  float {kv.Key} = {kv.Value}");
                }
            EditorGUIUtility.systemCopyBuffer = sb.ToString();
            Debug.Log(sb.ToString());
            EditorUtility.DisplayDialog("Mini Golf", "Material info copied to the clipboard.", "OK");
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
