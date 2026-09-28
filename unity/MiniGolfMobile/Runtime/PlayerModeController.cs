using System.Linq;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile
{
    // Switches between walking around in first person and putting.
    // Walk up to a tee to start that hole, walk up to your ball to putt, tap lost balls to grab them.
    public class PlayerModeController : MonoBehaviour
    {
        public FirstPersonWalker walker;
        [Tooltip("The putting camera object (with GolfCamera and TouchPutter).")]
        public GameObject puttRig;
        public GolfCamera golfCamera;
        public CourseManager course;
        public GolfBall ball;

        [Tooltip("How close to a tee you need to be to start that hole.")]
        public float teeReach = 3f;
        [Tooltip("How close to your ball you need to be to putt.")]
        public float ballReach = 3f;
        [Tooltip("Arm's reach for grabbing lost balls.")]
        public float grabReach = 2.5f;
        [Tooltip("Scene loaded by the Menu button.")]
        public string menuScene = "LevelSelect";

        public bool Putting { get; private set; }

        [Tooltip("Respawn the player if they somehow fall this far below where they started.")]
        public float fallRespawnDepth = 40f;
        [Tooltip("How deep the player wades into water (metres below the water surface).")]
        public float wadeDepth = 0.5f;

        Vector3 spawnPosition;
        Quaternion spawnRotation;

        void Update()
        {
            if (!Putting && walker && walker.transform.position.y < spawnPosition.y - fallRespawnDepth)
            {
                Vector3 target = course.ActiveHole && ball.gameObject.activeInHierarchy ? ball.transform.position : spawnPosition;
                walker.TeleportTo(target + Vector3.up * 0.1f, target + spawnRotation * Vector3.forward * 3f);
            }
        }

        GUIStyle button;

        [Tooltip("Frame rate to aim for on phones (they default to 30).")]
        public int targetFrameRate = 60;

        void Awake()
        {
            QualitySettings.vSyncCount = 0;
            Application.targetFrameRate = targetFrameRate;
        }

        void Start()
        {
            if (!walker) walker = FindFirstObjectByType<FirstPersonWalker>(FindObjectsInactive.Include);
            if (!golfCamera) golfCamera = FindFirstObjectByType<GolfCamera>(FindObjectsInactive.Include);
            if (!puttRig && golfCamera) puttRig = golfCamera.gameObject;
            if (!course) course = FindFirstObjectByType<CourseManager>(FindObjectsInactive.Include);
            if (!ball) ball = FindFirstObjectByType<GolfBall>(FindObjectsInactive.Include);

            // Exported courses may switch off collisions between some layers; the player and ball need them all.
            foreach (int layer in new[] { walker.gameObject.layer, ball.gameObject.layer })
                for (int other = 0; other < 32; other++)
                    Physics.IgnoreLayerCollision(layer, other, false);

            walker.minFeetHeight = WaterLevel() - wadeDepth;
            spawnPosition = walker.transform.position;
            spawnRotation = walker.transform.rotation;
            walker.Tapped += OnWalkerTap;
            course.HoleStarted += OnHoleStarted;
            course.HoleFinished += _ => EnterWalk();
            LostBallPickup.RecordTotalForActiveScene();
            HideBrokenRenderers();
            SetUpCameras();
            MakeDaylightSky();
            EnterWalk();
        }

        // Anything whose material or shader didn't survive the export draws as bright pink; hide it.
        void HideBrokenRenderers()
        {
            int hidden = 0;
            foreach (var r in FindObjectsByType<Renderer>(FindObjectsSortMode.None))
            {
                if (r.GetComponentInParent<PlayerModeController>()) continue;
                bool broken = false;
                foreach (var m in r.sharedMaterials)
                {
                    if (!m || !m.shader || !m.shader.isSupported || m.shader.name == "Hidden/InternalErrorShader") { broken = true; break; }
                    foreach (var prop in new[] { "_Color", "_BaseColor", "_TintColor" })
                    {
                        if (!m.HasProperty(prop)) continue;
                        Color c = m.GetColor(prop);
                        if (c.r > 0.6f && c.b > 0.6f && c.g < 0.35f) { broken = true; break; }
                    }
                    if (broken) break;
                }
                if (broken) { r.enabled = false; hidden++; }
            }
            if (hidden > 0) Debug.Log($"Mini Golf: hid {hidden} objects with missing materials.");
        }

        // The course's baked visibility data doesn't match anymore and makes things flicker.
        void SetUpCameras()
        {
            foreach (var cam in new[] { walker.head, golfCamera ? golfCamera.GetComponent<Camera>() : null })
            {
                if (!cam) continue;
                cam.useOcclusionCulling = false;
                cam.clearFlags = CameraClearFlags.Skybox;
                cam.farClipPlane = Mathf.Max(cam.farClipPlane, 400f);
            }
        }

        void MakeDaylightSky()
        {
            var sun = RenderSettings.sun;
            if (!sun)
                foreach (var l in FindObjectsByType<Light>(FindObjectsSortMode.None))
                    if (l.type == LightType.Directional && l.enabled) { sun = l; break; }
            if (sun)
            {
                // A sun pointing sideways or upward gives a dusk/night sky; tilt it to mid-afternoon.
                if (sun.transform.forward.y > -0.5f)
                    sun.transform.rotation = Quaternion.Euler(50f, sun.transform.eulerAngles.y, 0f);
                sun.intensity = Mathf.Max(sun.intensity, 1f);
                sun.color = new Color(1f, 0.96f, 0.88f);
                RenderSettings.sun = sun;
            }
            var sky = RenderSettings.skybox;
            if (!sky || !sky.shader || sky.shader.name != "Skybox/Procedural")
            {
                var shader = Shader.Find("Skybox/Procedural");
                if (shader) sky = RenderSettings.skybox = new Material(shader);
            }
            if (sky && sky.shader && sky.shader.name == "Skybox/Procedural")
            {
                sky.SetColor("_SkyTint", new Color(0.42f, 0.62f, 1f));
                sky.SetColor("_GroundColor", new Color(0.55f, 0.6f, 0.65f));
                sky.SetFloat("_Exposure", 1.6f);
                sky.SetFloat("_AtmosphereThickness", 0.9f);
                sky.SetFloat("_SunSize", 0.035f);
            }
            RenderSettings.fog = false;
            RenderSettings.ambientMode = UnityEngine.Rendering.AmbientMode.Skybox;
            RenderSettings.ambientIntensity = 1.1f;
            DynamicGI.UpdateEnvironment();
        }

        // Height of the sea: the top of the largest water surface in the scene.
        float WaterLevel()
        {
            float level = float.NegativeInfinity, biggest = 0f;
            foreach (var r in FindObjectsByType<MeshRenderer>(FindObjectsSortMode.None))
            {
                string n = r.name + " " + (r.sharedMaterial ? r.sharedMaterial.name : "");
                if (!System.Text.RegularExpressions.Regex.IsMatch(n, "water|ocean|sea", System.Text.RegularExpressions.RegexOptions.IgnoreCase)) continue;
                if (r.TryGetComponent<Collider>(out var col) && col.isTrigger) continue;
                var b = r.bounds;
                if (b.size.y > 3f) continue;   // backdrops and waterfalls, not a flat sea
                float area = b.size.x * b.size.z;
                if (area > biggest) { biggest = area; level = b.max.y; }
            }
            // No water found: allow walking down to a little below the start.
            return biggest > 25f ? level : walker.transform.position.y - 3f;
        }

        void OnWalkerTap(Vector2 screenPos)
        {
            if (!Putting && walker.head && LostBallPickup.TryCollectAt(walker.head, screenPos, grabReach))
                course.ShowBanner("Found a lost ball!", 2f);
        }

        void OnHoleStarted(GolfHole hole)
        {
            EnterPutt();
            if (golfCamera && hole.cup) golfCamera.LookToward(hole.cup.transform.position);
        }

        public void EnterPutt()
        {
            if (!ball.gameObject.activeInHierarchy) return;
            Putting = true;
            walker.gameObject.SetActive(false);
            puttRig.SetActive(true);
            golfCamera.SnapToBall();
        }

        public void EnterWalk()
        {
            if (Putting && ball.gameObject.activeInHierarchy)
            {
                // Step back from the ball, facing the way the putting camera was looking.
                Vector3 back = Vector3.ProjectOnPlane(golfCamera.transform.forward, Vector3.up).normalized;
                Vector3 feet = ball.transform.position - back * 1f;
                walker.TeleportTo(feet, ball.transform.position + back * 3f);
            }
            Putting = false;
            puttRig.SetActive(false);
            walker.gameObject.SetActive(true);
            IgnoreWalkerBallCollisions();
        }

        // The walker shouldn't bump the ball around. Unity forgets this whenever either object
        // is switched off, so it is re-applied every time the walker comes back.
        void IgnoreWalkerBallCollisions()
        {
            if (!ball.gameObject.activeInHierarchy) return;
            var cc = walker.GetComponent<CharacterController>();
            foreach (var c in ball.GetComponentsInChildren<Collider>()) Physics.IgnoreCollision(c, cc);
        }

        void GoToBall()
        {
            Vector3 toBall = ball.transform.position - walker.transform.position;
            toBall.y = 0;
            Vector3 dir = toBall.sqrMagnitude > 0.01f ? toBall.normalized : walker.transform.forward;
            walker.TeleportTo(ball.transform.position - dir * 1f, ball.transform.position + dir * 3f);
        }

        GolfHole NearestTee()
        {
            if (!walker) return null;
            Vector3 p = walker.transform.position;
            return course.holes
                .Where(h => h && FlatDistance(h.TeePosition, p) < teeReach)
                .OrderBy(h => FlatDistance(h.TeePosition, p))
                .FirstOrDefault();
        }

        static float FlatDistance(Vector3 a, Vector3 b) => new Vector2(a.x - b.x, a.z - b.z).magnitude;

        void OnGUI()
        {
            float u = CourseManager.UiScale;
            if (button == null) button = new GUIStyle(GUI.skin.button);
            button.fontSize = Mathf.RoundToInt(22 * u);

            float bw = 190 * u, bh = 60 * u, gap = 10 * u;
            float x = Screen.width - bw - 16 * u;
            float y = Screen.height - bh - 16 * u;

            bool Button(string text)
            {
                bool pressed = GUI.Button(new Rect(x, y, bw, bh), text, button);
                y -= bh + gap;
                return pressed;
            }

            if (Putting)
            {
                if (Button("Walk")) EnterWalk();
            }
            else
            {
                if (course.ActiveHole)
                {
                    bool nearBall = FlatDistance(ball.transform.position, walker.transform.position) < ballReach;
                    if (nearBall && !ball.IsMoving && Button("Putt")) EnterPutt();
                    if (!nearBall && Button("Go to ball")) GoToBall();
                    if (Button("Quit hole")) course.AbandonHole();
                }
                else
                {
                    var tee = NearestTee();
                    if (tee && Button($"Play hole {tee.number}")) course.StartHole(tee);
                }
            }

            // Top-right: scorecard and menu.
            float sx = Screen.width - 130 * u - 12 * u;
            if (GUI.Button(new Rect(sx, 12 * u, 130 * u, 48 * u), course.ShowCard ? "Close" : "Card", button))
                course.ShowCard = !course.ShowCard;
            if (GUI.Button(new Rect(sx, 68 * u, 130 * u, 48 * u), "Menu", button) && Application.CanStreamedLevelBeLoaded(menuScene))
                SceneManager.LoadScene(menuScene);
        }
    }
}
