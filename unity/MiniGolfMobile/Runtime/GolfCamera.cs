using System;
using UnityEngine;

namespace MiniGolfMobile
{
    // Follows the ball. Drag with one finger (away from the ball) to look around,
    // pinch to zoom. A quick tap is passed on for things like picking up lost balls.
    [RequireComponent(typeof(Camera))]
    public class GolfCamera : MonoBehaviour
    {
        public GolfBall ball;
        public TouchPutter putter;

        public float distance = 1.6f;
        public float minDistance = 0.3f;
        public float maxDistance = 12f;
        public float pitch = 35f;
        public float minPitch = 5f;
        public float maxPitch = 85f;
        public float yaw;

        [Tooltip("Degrees turned per screen point dragged.")]
        public float orbitSpeed = 0.3f;
        public float zoomSpeed = 0.006f;
        public float followSharpness = 8f;
        [Tooltip("A press that moves less than this (screen points) counts as a tap.")]
        public float tapMaxMove = 12f;

        public event Action<Vector2> Tapped;

        Camera cam;
        Vector3 focus;
        Vector2 lastPos, pressPos;
        bool orbiting;

        void Start()
        {
            cam = GetComponent<Camera>();
            if (!ball) ball = FindFirstObjectByType<GolfBall>();
            if (!putter) putter = FindFirstObjectByType<TouchPutter>();
            if (ball) focus = ball.transform.position;
        }

        void LateUpdate()
        {
            if (!ball) return;
            HandleInput();

            focus = Vector3.Lerp(focus, ball.transform.position, 1f - Mathf.Exp(-followSharpness * Time.deltaTime));
            Quaternion rot = Quaternion.Euler(pitch, yaw, 0f);
            transform.SetPositionAndRotation(focus - rot * Vector3.forward * distance, rot);
        }

        void HandleInput()
        {
            float zoom = PointerInput.ZoomDelta;
            if (zoom != 0f)
                distance = Mathf.Clamp(distance * (1f - zoom * zoomSpeed / PointerInput.DpiScale), minDistance, maxDistance);

            if (PointerInput.TouchCount >= 2)
            {
                orbiting = false;
                return;
            }

            bool aiming = putter && putter.IsAiming;
            if (PointerInput.PressedThisFrame)
            {
                pressPos = lastPos = PointerInput.Position;
                orbiting = !aiming;
            }
            if (aiming) orbiting = false;

            if (orbiting && PointerInput.IsPressed)
            {
                Vector2 p = PointerInput.Position;
                Vector2 d = (p - lastPos) / PointerInput.DpiScale;
                lastPos = p;
                yaw += d.x * orbitSpeed;
                pitch = Mathf.Clamp(pitch - d.y * orbitSpeed, minPitch, maxPitch);
            }

            if (PointerInput.ReleasedThisFrame)
            {
                Vector2 p = PointerInput.Position;
                if (orbiting && (p - pressPos).magnitude / PointerInput.DpiScale < tapMaxMove)
                    Tapped?.Invoke(p);
                orbiting = false;
            }
        }

        // Turns the view so the ball looks toward a point (e.g. the cup at the start of a hole).
        public void LookToward(Vector3 target)
        {
            if (!ball) return;
            Vector3 d = target - ball.transform.position;
            if (new Vector2(d.x, d.z).sqrMagnitude > 1e-4f)
                yaw = Mathf.Atan2(d.x, d.z) * Mathf.Rad2Deg;
        }

        public void SnapToBall()
        {
            if (ball) focus = ball.transform.position;
        }
    }
}
