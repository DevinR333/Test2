using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile.EditorTools
{
    public static class MiniGolfTools
    {
        const string TestFolder = "Assets/MiniGolfMobile/TestHole";

        // ---------- Test hole ----------

        [MenuItem("Mini Golf/Create Test Hole")]
        public static void CreateTestHole()
        {
            if (!EditorSceneManager.SaveCurrentModifiedScenesIfUserWantsTo()) return;
            EnsureFolder(TestFolder);
            EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);

            var green = MakeMaterial("Green", new Color(0.18f, 0.55f, 0.22f));
            var wall = MakeMaterial("Wall", new Color(0.55f, 0.35f, 0.2f));
            var white = MakeMaterial("Ball", Color.white);
            var dark = MakeMaterial("Cup", new Color(0.05f, 0.05f, 0.05f));
            var gold = MakeMaterial("LostBall", new Color(1f, 0.75f, 0.1f));
            var turf = MakePhysics("Turf", 0.4f, 0.1f, PhysicsMaterialCombine.Minimum);
            var bumper = MakePhysics("Bumper", 0.2f, 0.65f, PhysicsMaterialCombine.Maximum);
            var ballPhys = MakePhysics("BallPhysics", 0.3f, 0.5f, PhysicsMaterialCombine.Average);

            var root = new GameObject("TestHole");
            Box("Green", root, new Vector3(0, -0.05f, 0), new Vector3(1.2f, 0.1f, 6f), green, turf)
                .AddComponent<GolfPlaySurface>();
            Box("Wall L", root, new Vector3(-0.625f, 0.04f, 0), new Vector3(0.05f, 0.08f, 6.1f), wall, bumper);
            Box("Wall R", root, new Vector3(0.625f, 0.04f, 0), new Vector3(0.05f, 0.08f, 6.1f), wall, bumper);
            Box("Wall Back", root, new Vector3(0, 0.04f, -3.025f), new Vector3(1.3f, 0.08f, 0.05f), wall, bumper);
            Box("Wall Front", root, new Vector3(0, 0.04f, 3.025f), new Vector3(1.3f, 0.08f, 0.05f), wall, bumper);

            var spinner = Box("Spinner", root, new Vector3(0, 0.03f, 0.4f), new Vector3(0.7f, 0.06f, 0.04f), wall, bumper);
            spinner.AddComponent<SpinObstacle>().degreesPerSecond = 50f;

            var hole = new GameObject("Hole 1");
            hole.transform.SetParent(root.transform);
            var gh = hole.AddComponent<GolfHole>();
            gh.number = 1;
            gh.par = 2;
            var tee = new GameObject("Tee");
            tee.transform.SetParent(hole.transform);
            tee.transform.position = new Vector3(0, 0, -2.6f);
            gh.tee = tee.transform;

            var cupGo = new GameObject("Cup");
            cupGo.transform.SetParent(hole.transform);
            cupGo.transform.position = new Vector3(0.2f, 0, 2.3f);
            var cup = cupGo.AddComponent<GolfCup>();
            cup.hole = gh;
            gh.cup = cup;
            var disc = GameObject.CreatePrimitive(PrimitiveType.Cylinder);
            disc.name = "CupMarker";
            Object.DestroyImmediate(disc.GetComponent<Collider>());
            disc.transform.SetParent(cupGo.transform);
            disc.transform.localPosition = new Vector3(0, 0.001f, 0);
            disc.transform.localScale = new Vector3(0.11f, 0.001f, 0.11f);
            disc.GetComponent<Renderer>().sharedMaterial = dark;

            var lost = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            lost.name = "LostBall";
            Object.DestroyImmediate(lost.GetComponent<Collider>());
            lost.transform.SetParent(root.transform);
            lost.transform.position = new Vector3(-0.5f, 0.025f, 1.2f);
            lost.transform.localScale = Vector3.one * 0.05f;
            lost.GetComponent<Renderer>().sharedMaterial = gold;
            lost.AddComponent<LostBallPickup>();

            var ball = CreateBall(white, ballPhys);
            ball.transform.position = gh.TeePosition;

            AddPlayerRig(ball);

            string path = $"{TestFolder}/TestHole.unity";
            EditorSceneManager.SaveScene(SceneManager.GetActiveScene(), path);
            Debug.Log($"Mini Golf: test hole saved to {path}. Press Play and drag back from the ball to putt.");
        }

        // ---------- Add to an existing (e.g. exported) course scene ----------

        [MenuItem("Mini Golf/Add Player To Open Scene")]
        public static void AddPlayerToOpenScene()
        {
            EnsureFolder(TestFolder);
            var white = MakeMaterial("Ball", Color.white);
            var ballPhys = MakePhysics("BallPhysics", 0.3f, 0.5f, PhysicsMaterialCombine.Average);
            var ball = CreateBall(white, ballPhys);
            var view = SceneView.lastActiveSceneView;
            ball.transform.position = view ? view.pivot : Vector3.zero;
            AddPlayerRig(ball);
            EditorSceneManager.MarkSceneDirty(SceneManager.GetActiveScene());
            Selection.activeGameObject = ball;
            Debug.Log("Mini Golf: added ball, camera and CourseManager. Add GolfHole objects (with a Tee and a GolfCup) for each hole.");
        }

        static void EnsureFolder(string path)
        {
            if (AssetDatabase.IsValidFolder(path)) return;
            string parent = Path.GetDirectoryName(path).Replace('\\', '/');
            EnsureFolder(parent);
            AssetDatabase.CreateFolder(parent, Path.GetFileName(path));
        }

        static GameObject CreateBall(Material mat, PhysicsMaterial phys)
        {
            var ball = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            ball.name = "GolfBall";
            ball.transform.localScale = Vector3.one * 0.043f;
            ball.GetComponent<Renderer>().sharedMaterial = mat;
            ball.GetComponent<Collider>().sharedMaterial = phys;
            var rb = ball.AddComponent<Rigidbody>();
            rb.mass = 0.046f;
            ball.AddComponent<GolfBall>();
            return ball;
        }

        static void AddPlayerRig(GameObject ball)
        {
            var cam = Camera.main;
            if (!cam)
            {
                var camGo = new GameObject("Main Camera") { tag = "MainCamera" };
                cam = camGo.AddComponent<Camera>();
                camGo.AddComponent<AudioListener>();
            }
            cam.nearClipPlane = 0.02f;
            cam.farClipPlane = 500f;
            var gc = cam.GetComponent<GolfCamera>();
            if (!gc) gc = cam.gameObject.AddComponent<GolfCamera>();
            var putter = cam.GetComponent<TouchPutter>();
            if (!putter) putter = cam.gameObject.AddComponent<TouchPutter>();
            var gb = ball.GetComponent<GolfBall>();
            gc.ball = gb;
            gc.putter = putter;
            putter.ball = gb;
            putter.cam = cam;

            var cm = Object.FindFirstObjectByType<CourseManager>();
            if (!cm) cm = new GameObject("CourseManager").AddComponent<CourseManager>();
            cm.ball = gb;
            cm.golfCamera = gc;
            putter.course = cm;
        }

        static GameObject Box(string name, GameObject parent, Vector3 pos, Vector3 size, Material mat, PhysicsMaterial phys)
        {
            var go = GameObject.CreatePrimitive(PrimitiveType.Cube);
            go.name = name;
            go.transform.SetParent(parent.transform);
            go.transform.position = pos;
            go.transform.localScale = size;
            go.GetComponent<Renderer>().sharedMaterial = mat;
            go.GetComponent<Collider>().sharedMaterial = phys;
            return go;
        }

        static Material MakeMaterial(string name, Color color)
        {
            string path = $"{TestFolder}/{name}.mat";
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
            string path = $"{TestFolder}/{name}.asset";
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

        // Component names from the original game that we care about when setting up a course.
        static readonly string[] Interesting =
        {
            "Hole", "Cup", "MightyCup", "Flag", "HoleComplete", "Course", "InPlaySurface", "BallCatcher",
            "BallKiller", "LostBall", "ScaleUpLostBall", "MovingObject", "RotatingObject", "WaypointMover",
            "WaypointAnimationMover", "SineMover", "WindTrigger", "BallConstantForce", "MightyConstantForce",
            "BallBouncinessTrigger", "MightyBouncinessTrigger", "BallTeleporter", "BallPortal", "BallInPipe",
            "Teleporter", "Surface", "GolfBall", "Ball", "Waypoint", "HoleSelector", "DoNotEndPuttTrigger"
        };

        [MenuItem("Mini Golf/Copy Scene Report")]
        public static void CopySceneReport()
        {
            var counts = new Dictionary<string, int>();
            var found = new Dictionary<string, List<string>>();
            int missing = 0;

            for (int s = 0; s < SceneManager.sceneCount; s++)
            {
                var scene = SceneManager.GetSceneAt(s);
                if (!scene.isLoaded) continue;
                foreach (var root in scene.GetRootGameObjects())
                foreach (var t in root.GetComponentsInChildren<Transform>(true))
                foreach (var c in t.GetComponents<Component>())
                {
                    if (c == null) { missing++; continue; }
                    string n = c.GetType().Name;
                    counts[n] = counts.TryGetValue(n, out int k) ? k + 1 : 1;
                    if (!Interesting.Contains(n)) continue;
                    if (!found.TryGetValue(n, out var list)) found[n] = list = new List<string>();
                    if (list.Count < 40)
                    {
                        Vector3 p = t.position;
                        list.Add($"  {PathOf(t)}  pos=({p.x:F2}, {p.y:F2}, {p.z:F2})  colliders={t.GetComponents<Collider>().Length}  active={t.gameObject.activeInHierarchy}");
                    }
                }
            }

            var sb = new StringBuilder();
            sb.AppendLine($"Scenes: {string.Join(", ", Enumerable.Range(0, SceneManager.sceneCount).Select(i => SceneManager.GetSceneAt(i).name))}");
            sb.AppendLine($"Missing scripts: {missing}");
            sb.AppendLine("\n== Golf-related objects ==");
            foreach (var kv in found.OrderBy(k => k.Key))
            {
                sb.AppendLine($"{kv.Key} ({counts[kv.Key]}):");
                foreach (var line in kv.Value) sb.AppendLine(line);
            }
            sb.AppendLine("\n== All component types ==");
            foreach (var kv in counts.OrderByDescending(k => k.Value))
                sb.AppendLine($"{kv.Value,6}  {kv.Key}");

            string text = sb.ToString();
            EditorGUIUtility.systemCopyBuffer = text;
            File.WriteAllText("MiniGolfSceneReport.txt", text);
            Debug.Log("Mini Golf: scene report copied to the clipboard and saved as MiniGolfSceneReport.txt in the project folder.");
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
