using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile.EditorTools
{
    public static class MiniGolfTools
    {
        const string Folder = "Assets/MiniGolfMobile/Generated";
        const string MenuScenePath = Folder + "/LevelSelect.unity";

        // ---------- Test course ----------

        [MenuItem("Mini Golf/Create Test Course", priority = 1)]
        public static void CreateTestCourse()
        {
            if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo()) return;
            EnsureFolder(Folder);
            EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);
            foreach (var c in Object.FindObjectsByType<Camera>(FindObjectsSortMode.None)) Object.DestroyImmediate(c.gameObject);

            var grass = MakeMaterial("Grass", new Color(0.35f, 0.6f, 0.3f));
            var green = MakeMaterial("Green", new Color(0.15f, 0.5f, 0.2f));
            var wall = MakeMaterial("Wall", new Color(0.55f, 0.35f, 0.2f));
            var dark = MakeMaterial("Cup", new Color(0.05f, 0.05f, 0.05f));
            var gold = MakeMaterial("LostBall", new Color(1f, 0.75f, 0.1f));
            var rock = MakeMaterial("Rock", new Color(0.5f, 0.5f, 0.52f));
            var turf = MakePhysics("Turf", 0.4f, 0.1f, PhysicsMaterialCombine.Minimum);
            var bumper = MakePhysics("Bumper", 0.2f, 0.65f, PhysicsMaterialCombine.Maximum);

            var root = new GameObject("TestCourse");
            Box("Ground", root, new Vector3(0, -0.1f, 4), new Vector3(20, 0.2f, 20), grass, turf);

            var h1 = BuildHole(root, 1, 2, new Vector3(-3, 0, 2), 0f, 5f, green, wall, dark, turf, bumper);
            var spinner = Box("Spinner", h1, new Vector3(-3, 0.03f, 2.6f), new Vector3(0.7f, 0.06f, 0.04f), wall, bumper);
            spinner.AddComponent<SpinObstacle>().degreesPerSecond = 50f;

            var h2 = BuildHole(root, 2, 3, new Vector3(3, 0, 5), 30f, 6f, green, wall, dark, turf, bumper);
            var gate = Box("Slider", h2, h2.transform.TransformPoint(new Vector3(-0.3f, 0.03f, 0)), new Vector3(0.4f, 0.06f, 0.05f), wall, bumper);
            gate.transform.rotation = h2.transform.rotation;
            gate.AddComponent<PingPongMover>().offset = h2.transform.right * 0.6f;

            // A lost ball tucked behind a rock, off the greens.
            Box("Rock", root, new Vector3(7, 0.4f, 11), new Vector3(1.4f, 0.8f, 1f), rock, null);
            var lost = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            lost.name = "LostBall";
            Object.DestroyImmediate(lost.GetComponent<Collider>());
            lost.transform.SetParent(root.transform);
            lost.transform.position = new Vector3(7.2f, 0.03f, 11.8f);
            lost.transform.localScale = Vector3.one * 0.05f;
            lost.GetComponent<Renderer>().sharedMaterial = gold;
            lost.AddComponent<LostBallPickup>();

            CreatePlayerRig(new Vector3(0, 0, -3), 0f);
            string path = $"{Folder}/TestCourse.unity";
            EditorSceneManager.SaveScene(SceneManager.GetActiveScene(), path);
            AddToBuild(path);
            Debug.Log($"Mini Golf: test course saved to {path}. Press Play. Walk with WASD, hold right mouse to look, click to grab.");
        }

        static GameObject BuildHole(GameObject root, int number, int par, Vector3 teePos, float yaw, float length,
            Material green, Material wall, Material dark, PhysicsMaterial turf, PhysicsMaterial bumper)
        {
            var hole = new GameObject($"Hole {number}");
            hole.transform.SetParent(root.transform);
            hole.transform.SetPositionAndRotation(teePos, Quaternion.Euler(0, yaw, 0));
            var gh = hole.AddComponent<GolfHole>();
            gh.number = number;
            gh.par = par;

            Vector3 L(float x, float y, float z) => hole.transform.TransformPoint(new Vector3(x, y, z));
            Quaternion rot = hole.transform.rotation;
            void Part(string n, Vector3 local, Vector3 size, Material m, PhysicsMaterial p, bool play)
            {
                var b = Box(n, hole, L(local.x, local.y, local.z), size, m, p);
                b.transform.rotation = rot;
                if (play) b.AddComponent<GolfPlaySurface>();
            }
            float mid = length / 2 - 0.4f;
            Part("Green", new Vector3(0, -0.049f, mid), new Vector3(1.2f, 0.1f, length), green, turf, true);
            Part("Wall L", new Vector3(-0.625f, 0.04f, mid), new Vector3(0.05f, 0.08f, length + 0.1f), wall, bumper, false);
            Part("Wall R", new Vector3(0.625f, 0.04f, mid), new Vector3(0.05f, 0.08f, length + 0.1f), wall, bumper, false);
            Part("Wall Back", new Vector3(0, 0.04f, mid - length / 2 - 0.025f), new Vector3(1.3f, 0.08f, 0.05f), wall, bumper, false);
            Part("Wall Front", new Vector3(0, 0.04f, mid + length / 2 + 0.025f), new Vector3(1.3f, 0.08f, 0.05f), wall, bumper, false);

            var tee = new GameObject("Tee");
            tee.transform.SetParent(hole.transform, false);
            gh.tee = tee.transform;

            var cupGo = new GameObject("Cup");
            cupGo.transform.SetParent(hole.transform, false);
            cupGo.transform.localPosition = new Vector3(0.2f, 0.001f, length - 1.1f);
            var cup = cupGo.AddComponent<GolfCup>();
            cup.hole = gh;
            gh.cup = cup;
            var disc = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            disc.name = "CupMarker";
            Object.DestroyImmediate(disc.GetComponent<Collider>());
            disc.transform.SetParent(cupGo.transform, false);
            disc.transform.localScale = new Vector3(0.11f, 0.001f, 0.11f);
            disc.GetComponent<Renderer>().sharedMaterial = dark;
            return hole;
        }

        // ---------- Level select ----------

        [MenuItem("Mini Golf/Create Level Select Menu", priority = 2)]
        public static void CreateLevelSelect()
        {
            if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo()) return;
            EnsureFolder(Folder);
            EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);
            var cam = Camera.main;
            if (cam)
            {
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(0.1f, 0.25f, 0.35f);
            }
            new GameObject("LevelSelectMenu").AddComponent<LevelSelectMenu>();
            EditorSceneManager.SaveScene(SceneManager.GetActiveScene(), MenuScenePath);
            AddToBuild(MenuScenePath, first: true);
            Debug.Log("Mini Golf: level select menu created and set as the first scene in the build.");
        }

        public static void CreateLevelSelectSilently(string path)
        {
            EnsureFolder(Path.GetDirectoryName(path).Replace('\\', '/'));
            EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);
            var cam = Camera.main;
            if (cam)
            {
                cam.clearFlags = CameraClearFlags.SolidColor;
                cam.backgroundColor = new Color(0.1f, 0.25f, 0.35f);
            }
            new GameObject("LevelSelectMenu").AddComponent<LevelSelectMenu>();
            EditorSceneManager.SaveScene(SceneManager.GetActiveScene(), path);
        }

        public static void AddCoursesToBuildSilently()
        {
            foreach (string guid in AssetDatabase.FindAssets("t:Scene"))
            {
                string path = AssetDatabase.GUIDToAssetPath(guid);
                if (path == MenuScenePath || !path.StartsWith("Assets/")) continue;
                if (File.ReadAllText(path).Contains(MonoScriptGuid<CourseManager>())) AddToBuild(path);
            }
            if (File.Exists(MenuScenePath)) AddToBuild(MenuScenePath, first: true);
        }

        [MenuItem("Mini Golf/Add Set-Up Courses To Build", priority = 3)]
        public static void AddCoursesToBuild()
        {
            int added = 0;
            foreach (string guid in AssetDatabase.FindAssets("t:Scene"))
            {
                string path = AssetDatabase.GUIDToAssetPath(guid);
                if (path == MenuScenePath || !path.StartsWith("Assets/")) continue;
                // Only scenes that have been set up to play (they contain a CourseManager).
                string text = File.ReadAllText(path);
                if (!text.Contains(MonoScriptGuid<CourseManager>())) continue;
                if (AddToBuild(path)) added++;
            }
            if (File.Exists(MenuScenePath)) AddToBuild(MenuScenePath, first: true);
            Debug.Log($"Mini Golf: added {added} course scene(s) to the build.");
        }

        static string MonoScriptGuid<T>() where T : MonoBehaviour
        {
            var go = new GameObject("tmp") { hideFlags = HideFlags.HideAndDontSave };
            var script = MonoScript.FromMonoBehaviour(go.AddComponent<T>());
            Object.DestroyImmediate(go);
            return AssetDatabase.AssetPathToGUID(AssetDatabase.GetAssetPath(script));
        }

        static bool AddToBuild(string path, bool first = false)
        {
            var list = EditorBuildSettings.scenes.ToList();
            list.RemoveAll(s => s.path == path);
            var entry = new EditorBuildSettingsScene(path, true);
            if (first) list.Insert(0, entry); else list.Add(entry);
            bool isNew = EditorBuildSettings.scenes.All(s => s.path != path);
            EditorBuildSettings.scenes = list.ToArray();
            return isNew;
        }

        // ---------- Set up an exported course scene ----------

        [MenuItem("Mini Golf/Set Up Open Course Scene", priority = 20)]
        public static void SetUpOpenCourse()
        {
            var report = new StringBuilder();

            // Clean up earlier attempts: old player rigs and components whose script no longer exists.
            int removedRigs = 0, removedMissing = 0;
            foreach (var root in SceneManager.GetActiveScene().GetRootGameObjects())
            {
                if (root.name == "MiniGolfPlayer") { Undo.DestroyObjectImmediate(root); removedRigs++; continue; }
                foreach (var t in root.GetComponentsInChildren<Transform>(true))
                    removedMissing += GameObjectUtility.RemoveMonoBehavioursWithMissingScript(t.gameObject);
            }
            if (removedRigs + removedMissing > 0)
                report.AppendLine($"Cleaned up {removedRigs} old player(s) and {removedMissing} broken components");

            var all = AllComponents().ToList();
            List<Component> Named(params string[] names) => all.Where(c => names.Contains(c.GetType().Name)).ToList();

            // Old VR cameras and listeners would fight with ours.
            foreach (var c in all.OfType<Camera>().Where(c => c.enabled)) { Undo.RecordObject(c, "Disable camera"); c.enabled = false; }
            foreach (var l in all.OfType<AudioListener>().Where(l => l.enabled)) { Undo.RecordObject(l, "Disable listener"); l.enabled = false; }

            // The game's own ball object would sit on the course and get in the way.
            foreach (var c in Named("Ball", "GolfBall"))
            {
                if (c.GetComponent<MiniGolfMobile.GolfBall>()) continue;
                Undo.RecordObject(c.gameObject, "Disable original ball");
                c.gameObject.SetActive(false);
            }

            // Lost balls: attach our pickup to the game's own LostBall objects, in their original spots.
            int lostBalls = 0;
            foreach (var c in Named("LostBall"))
            {
                if (c.GetComponent<LostBallPickup>()) continue;
                Undo.AddComponent<LostBallPickup>(c.gameObject);
                lostBalls++;
            }
            report.AppendLine($"Lost balls set up: {lostBalls}");

            // Holes: one GolfHole per original Hole component, with its cup and tee.
            int holesMade = 0;
            var usedNumbers = new HashSet<int>();
            foreach (var h in Named("Hole"))
            {
                if (h.GetComponent<GolfHole>()) continue;
                var gh = Undo.AddComponent<GolfHole>(h.gameObject);
                gh.number = GuessNumber(h.gameObject.name, usedNumbers);
                gh.par = 3;

                // The hole's own cup is its direct "CupPosition" child; pipe holes have their own CupPositions deeper down.
                Transform cupSource = h.transform.Find("CupPosition");
                if (!cupSource)
                    cupSource = h.GetComponentsInChildren<Component>(true)
                        .FirstOrDefault(c => c && (c.GetType().Name == "Cup" || c.GetType().Name == "MightyCup"))?.transform;
                if (cupSource)
                {
                    var cup = cupSource.GetComponent<GolfCup>();
                    if (!cup) cup = Undo.AddComponent<GolfCup>(cupSource.gameObject);
                    cup.hole = gh;
                    gh.cup = cup;
                }
                else report.AppendLine($"  No cup found under {h.name}");

                // Courses mark each tee with a "StartingPosition" object.
                var tee = h.transform.Find("StartingPosition");
                if (!tee)
                    tee = h.GetComponentsInChildren<Transform>(true)
                        .FirstOrDefault(t => t != h.transform && Regex.IsMatch(t.name, "^(tee|startingposition|ballstart)", RegexOptions.IgnoreCase));
                if (tee) gh.tee = tee;
                else report.AppendLine($"  No tee found under {h.name} (the ball will start at the Hole object)");
                holesMade++;
            }
            report.AppendLine($"Holes set up: {holesMade} (par defaults to 3; set each hole's par in the Inspector)");

            // Greens: the game marks them with InPlaySurface, which our ball already understands.
            report.AppendLine($"Green surfaces found: {Named("InPlaySurface").Count}");

            // Player: spawn near the first tee.
            var first = Object.FindObjectsByType<GolfHole>(FindObjectsSortMode.None).OrderBy(g => g.number).FirstOrDefault();
            Vector3 spawn = first ? first.TeePosition - (first.tee ? first.tee.forward : Vector3.forward) * 2f : Vector3.zero;
            float yaw = 0f;
            // Hole 1 has a "PlayerStartPosition" where the player stands when the course loads.
            var playerStart = first ? first.transform.Find("PlayerStartPosition") : null;
            if (playerStart)
            {
                spawn = playerStart.position;
                Vector3 d = first.TeePosition - spawn;
                yaw = Mathf.Atan2(d.x, d.z) * Mathf.Rad2Deg;
            }
            if (!Object.FindFirstObjectByType<PlayerModeController>())
                CreatePlayerRig(spawn, yaw);

            EditorSceneManager.MarkSceneDirty(SceneManager.GetActiveScene());
            string text = report.ToString();
            EditorGUIUtility.systemCopyBuffer = text;
            Debug.Log("Mini Golf: course set up (report copied to clipboard)\n" + text);
            if (!Application.isBatchMode && !CourseFixTools.Quiet)
                EditorUtility.DisplayDialog("Mini Golf", "Course set up. Save the scene (Ctrl+S), then press Play.\n\n" + text, "OK");
        }

        static int GuessNumber(string name, HashSet<int> used)
        {
            var m = Regex.Match(name, @"(\d+)");
            int n = m.Success ? int.Parse(m.Groups[1].Value) : 1;
            while (used.Contains(n)) n++;
            used.Add(n);
            return n;
        }

        // ---------- Player rig ----------

        static void CreatePlayerRig(Vector3 spawn, float yaw)
        {
            EnsureFolder(Folder);
            var white = MakeMaterial("Ball", Color.white);
            var ballPhys = MakePhysics("BallPhysics", 0.3f, 0.5f, PhysicsMaterialCombine.Average);

            var rig = new GameObject("MiniGolfPlayer");
            Undo.RegisterCreatedObjectUndo(rig, "Add mini golf player");

            var ball = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            ball.name = "GolfBall";
            ball.transform.SetParent(rig.transform);
            ball.transform.position = spawn + Vector3.up * 0.03f;
            ball.transform.localScale = Vector3.one * 0.043f;
            ball.GetComponent<Renderer>().sharedMaterial = white;
            ball.GetComponent<Collider>().sharedMaterial = ballPhys;
            ball.AddComponent<Rigidbody>().mass = 0.046f;
            var gb = ball.AddComponent<GolfBall>();

            var walkerGo = new GameObject("Walker");
            walkerGo.transform.SetParent(rig.transform);
            walkerGo.transform.SetPositionAndRotation(spawn + Vector3.up * 0.05f, Quaternion.Euler(0, yaw, 0));
            var cc = walkerGo.AddComponent<CharacterController>();
            cc.height = 1.7f;
            cc.radius = 0.25f;
            cc.center = new Vector3(0, 0.85f, 0);
            cc.stepOffset = 0.3f;
            var head = new GameObject("Head");
            head.transform.SetParent(walkerGo.transform, false);
            head.transform.localPosition = new Vector3(0, 1.6f, 0);
            var headCam = head.AddComponent<Camera>();
            headCam.nearClipPlane = 0.02f;
            headCam.farClipPlane = 1000f;
            head.tag = "MainCamera";
            head.AddComponent<AudioListener>();
            var walker = walkerGo.AddComponent<FirstPersonWalker>();
            walker.head = headCam;

            var puttGo = new GameObject("PuttCamera");
            puttGo.transform.SetParent(rig.transform);
            var puttCam = puttGo.AddComponent<Camera>();
            puttCam.nearClipPlane = 0.02f;
            puttCam.farClipPlane = 1000f;
            puttGo.AddComponent<AudioListener>();
            var gc = puttGo.AddComponent<GolfCamera>();
            var putter = puttGo.AddComponent<TouchPutter>();
            gc.ball = gb;
            gc.putter = putter;
            putter.ball = gb;
            putter.cam = puttCam;
            puttGo.SetActive(false);

            var cm = rig.AddComponent<CourseManager>();
            cm.ball = gb;
            putter.course = cm;

            var mode = rig.AddComponent<PlayerModeController>();
            mode.walker = walker;
            mode.puttRig = puttGo;
            mode.golfCamera = gc;
            mode.course = cm;
            mode.ball = gb;
            mode.menuScene = Path.GetFileNameWithoutExtension(MenuScenePath);
        }

        // ---------- Helpers ----------

        static IEnumerable<Component> AllComponents()
        {
            for (int s = 0; s < SceneManager.sceneCount; s++)
            {
                var scene = SceneManager.GetSceneAt(s);
                if (!scene.isLoaded) continue;
                foreach (var root in scene.GetRootGameObjects())
                foreach (var c in root.GetComponentsInChildren<Component>(true))
                    if (c) yield return c;
            }
        }

        static void EnsureFolder(string path)
        {
            if (AssetDatabase.IsValidFolder(path)) return;
            string parent = Path.GetDirectoryName(path).Replace('\\', '/');
            EnsureFolder(parent);
            AssetDatabase.CreateFolder(parent, Path.GetFileName(path));
        }

        static GameObject Box(string name, GameObject parent, Vector3 pos, Vector3 size, Material mat, PhysicsMaterial phys)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent.transform);
            go.transform.position = pos;
            go.transform.localScale = size;
            go.GetComponent<Renderer>().sharedMaterial = mat;
            if (phys) go.GetComponent<Collider>().sharedMaterial = phys;
            return go;
        }

        static Material MakeMaterial(string name, Color color)
        {
            string path = $"{Folder}/{name}.mat";
            var existing = AssetDatabase.LoadAssetAtPath<Material>(path);
            if (existing) return existing;
            var shader = Shader.Find("Universal Render Pipeline/Lit");
            if (!shader) shader = Shader.Find("Standard");
            var mat = new Material(shader);
            if (mat.HasProperty("_BaseColor")) mat.SetColor("_BaseColor", color);
            if (mat.HasProperty("_Color")) mat.SetColor("_Color", color);
            AssetDatabase.CreateAsset(mat, path);
            return mat;
        }

        static PhysicsMaterial MakePhysics(string name, float friction, float bounce, PhysicsMaterialCombine bounceCombine)
        {
            string path = $"{Folder}/{name}.asset";
            var existing = AssetDatabase.LoadAssetAtPath<PhysicsMaterial>(path);
            if (existing) return existing;
            var pm = new PhysicsMaterial(name)
            {
                dynamicFriction = friction,
                staticFriction = friction,
                bounciness = bounce,
                bounceCombine = bounceCombine,
                frictionCombine = PhysicsMaterialCombine.Average
            };
            AssetDatabase.CreateAsset(pm, path);
            return pm;
        }

        // ---------- Scene report ----------

        static readonly string[] Interesting =
        {
            "Hole", "Cup", "MightyCup", "Flag", "HoleComplete", "Course", "InPlaySurface", "BallCatcher",
            "BallKiller", "LostBall", "ScaleUpLostBall", "MovingObject", "RotatingObject", "WaypointMover",
            "WaypointAnimationMover", "SineMover", "WindTrigger", "BallConstantForce", "MightyConstantForce",
            "BallBouncinessTrigger", "MightyBouncinessTrigger", "BallTeleporter", "BallPortal", "BallInPipe",
            "Teleporter", "Surface", "GolfBall", "Ball", "Waypoint", "HoleSelector", "DoNotEndPuttTrigger"
        };

        [MenuItem("Mini Golf/Copy Scene Report", priority = 40)]
        public static void CopySceneReport()
        {
            var counts = new Dictionary<string, int>();
            var found = new Dictionary<string, List<string>>();
            int missing = 0;
            var missingOn = new List<string>();
            for (int s = 0; s < SceneManager.sceneCount; s++)
            {
                var scene = SceneManager.GetSceneAt(s);
                if (!scene.isLoaded) continue;
                foreach (var root in scene.GetRootGameObjects())
                foreach (var t in root.GetComponentsInChildren<Transform>(true))
                {
                    int n = GameObjectUtility.GetMonoBehavioursWithMissingScriptCount(t.gameObject);
                    missing += n;
                    if (n > 0 && missingOn.Count < 25) missingOn.Add($"  {PathOf(t)}  ({n})");
                }
            }
            foreach (var c in AllComponents())
            {
                string n = c.GetType().Name;
                counts[n] = counts.TryGetValue(n, out int k) ? k + 1 : 1;
                if (!Interesting.Contains(n)) continue;
                if (!found.TryGetValue(n, out var list)) found[n] = list = new List<string>();
                if (list.Count >= 40) continue;
                Transform t = c.transform;
                Vector3 p = t.position;
                list.Add($"  {PathOf(t)}  pos=({p.x:F2}, {p.y:F2}, {p.z:F2})  children={t.childCount}  colliders={t.GetComponents<Collider>().Length}");
            }

            var sb = new StringBuilder();
            sb.AppendLine($"Scene: {SceneManager.GetActiveScene().path}");
            sb.AppendLine($"Missing scripts: {missing}");
            foreach (var line in missingOn) sb.AppendLine(line);
            sb.AppendLine("== Golf-related objects ==");
            foreach (var kv in found.OrderBy(k => k.Key))
            {
                sb.AppendLine($"{kv.Key} ({counts[kv.Key]}):");
                foreach (var line in kv.Value) sb.AppendLine(line);
            }
            sb.AppendLine("== All component types ==");
            foreach (var kv in counts.OrderByDescending(k => k.Value).Take(80))
                sb.AppendLine($"{kv.Value,6}  {kv.Key}");

            sb.AppendLine("== Hole layouts ==");
            var holeRoots = AllComponents().Where(c => c.GetType().Name == "Hole").Select(c => c.transform).ToList();
            foreach (var h in holeRoots.Where(h => Regex.IsMatch(h.name, "(^|\\D)(0?1|0?6|14|18)$")).Take(4))
                DumpTree(sb, h, 0, h.position);

            string text = sb.ToString();
            EditorGUIUtility.systemCopyBuffer = text;
            File.WriteAllText("MiniGolfSceneReport.txt", text);
            Debug.Log("Mini Golf: scene report copied to the clipboard (also saved as MiniGolfSceneReport.txt in the project folder).");
        }

        static void DumpTree(StringBuilder sb, Transform t, int depth, Vector3 origin)
        {
            if (depth > 4) return;
            var comps = t.GetComponents<Component>()
                .Where(c => c && !(c is Transform))
                .Select(c => c.GetType().Name);
            Vector3 p = t.position, d = p - origin;
            sb.AppendLine($"{new string(' ', depth * 2)}{t.name}  pos=({p.x:F2}, {p.y:F2}, {p.z:F2})  fromHole={d.magnitude:F1}m  [{string.Join(", ", comps)}]");
            foreach (Transform child in t) DumpTree(sb, child, depth + 1, origin);
        }

        static string PathOf(Transform t)
        {
            var parts = new List<string>();
            for (; t != null && parts.Count < 6; t = t.parent) parts.Add(t.name);
            parts.Reverse();
            return string.Join("/", parts);
        }
    }
}
