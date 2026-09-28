using UnityEngine;

namespace MiniGolfMobile
{
    // Drag back from the ball and let go to putt, like pulling a slingshot.
    // A line shows the direction and how hard the shot will be.
    public class TouchPutter : MonoBehaviour
    {
        public GolfBall ball;
        public Camera cam;
        public CourseManager course;

        [Header("Shot")]
        [Tooltip("Ball speed (m/s) at full power.")]
        public float maxShotSpeed = 5f;
        [Tooltip("Above 1 gives finer control over short putts.")]
        public float powerCurve = 1.5f;
        [Tooltip("Shots weaker than this are cancelled (drag back onto the ball to cancel).")]
        public float minPower = 0.04f;

        [Header("Gesture")]
        [Tooltip("How close to the ball (in screen points) a drag has to start to aim.")]
        public float grabRadius = 70f;
        [Tooltip("Drag length for full power, as a fraction of screen height.")]
        public float fullPowerDrag = 0.35f;

        [Header("Aim line")]
        public float aimLineLength = 1.2f;
        public float aimLineWidth = 0.012f;
        public Color weakColor = new Color(0.3f, 1f, 0.3f);
        public Color strongColor = new Color(1f, 0.25f, 0.2f);

        public bool IsAiming { get; private set; }

        LineRenderer line;
        Vector2 startScreen;
        Vector3 aimDir;
        float power;

        void Start()
        {
            if (!ball) ball = FindFirstObjectByType<GolfBall>();
            if (!cam) cam = GetComponent<Camera>() ? GetComponent<Camera>() : Camera.main;
            if (!course) course = FindFirstObjectByType<CourseManager>();
            CreateLine();
        }

        void CreateLine()
        {
            var go = new GameObject("AimLine");
            go.transform.SetParent(transform, false);
            line = go.AddComponent<LineRenderer>();
            line.positionCount = 2;
            line.useWorldSpace = true;
            line.startWidth = aimLineWidth;
            line.endWidth = aimLineWidth * 0.4f;
            var shader = Shader.Find("Sprites/Default");
            if (!shader) shader = Shader.Find("Universal Render Pipeline/Unlit");
            if (shader) line.material = new Material(shader);
            line.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            line.receiveShadows = false;
            line.enabled = false;
        }

        bool CanShoot => ball && !ball.IsMoving && !ball.InCup && (!course || course.AcceptingShots);

        void Update()
        {
            if (!CanShoot || PointerInput.TouchCount >= 2)
            {
                Cancel();
                return;
            }

            if (PointerInput.PressedThisFrame && NearBall(PointerInput.Position))
            {
                IsAiming = true;
                startScreen = PointerInput.Position;
                power = 0f;
            }
            if (!IsAiming) return;

            if (PointerInput.IsPressed || PointerInput.ReleasedThisFrame)
                UpdateAim(PointerInput.Position);

            if (PointerInput.ReleasedThisFrame)
            {
                if (power >= minPower) Shoot();
                Cancel();
            }
        }

        bool NearBall(Vector2 screenPos)
        {
            Vector3 s = cam.WorldToScreenPoint(ball.transform.position);
            if (s.z <= 0) return false;
            return Vector2.Distance(screenPos, s) <= grabRadius * PointerInput.DpiScale;
        }

        void UpdateAim(Vector2 screenPos)
        {
            // Pulling back points the shot the other way, measured from where the finger went down.
            Vector2 drag = startScreen - screenPos;
            power = Mathf.Clamp01(drag.magnitude / (fullPowerDrag * Screen.height));

            Vector3 right = Vector3.ProjectOnPlane(cam.transform.right, Vector3.up).normalized;
            Vector3 forward = Vector3.ProjectOnPlane(cam.transform.forward, Vector3.up).normalized;
            if (forward.sqrMagnitude < 0.01f) forward = Vector3.ProjectOnPlane(cam.transform.up, Vector3.up).normalized;
            Vector3 dir = right * drag.x + forward * drag.y;
            if (dir.sqrMagnitude > 1e-6f) aimDir = dir.normalized;

            Vector3 from = ball.transform.position + Vector3.up * 0.005f;
            line.enabled = power >= minPower;
            line.SetPosition(0, from);
            line.SetPosition(1, from + aimDir * (aimLineLength * power));
            Color c = Color.Lerp(weakColor, strongColor, power);
            line.startColor = c;
            line.endColor = c;
        }

        void Shoot()
        {
            float speed = maxShotSpeed * Mathf.Pow(power, powerCurve);
            ball.Hit(aimDir * speed);
            if (course) course.RegisterStroke();
        }

        void Cancel()
        {
            IsAiming = false;
            power = 0f;
            if (line) line.enabled = false;
        }
    }
}
