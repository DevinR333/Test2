using System;
using UnityEngine;

namespace MiniGolfMobile
{
    // First-person walking. Left half of the screen is a movement stick (it appears where your
    // thumb lands); drag on the right half to look around. A quick tap is reported for grabbing things.
    // In the editor: WASD to move, hold the right mouse button to look, left click to tap.
    [RequireComponent(typeof(CharacterController))]
    public class FirstPersonWalker : MonoBehaviour
    {
        public Camera head;
        public float walkSpeed = 2.2f;
        [Tooltip("Degrees turned per screen point dragged.")]
        public float lookSpeed = 0.2f;
        [Tooltip("Thumb distance (screen points) for full walking speed.")]
        public float stickRadius = 60f;
        public float gravity = 15f;
        public float tapMaxMove = 12f;
        public float tapMaxTime = 0.35f;
        [Tooltip("The player's feet never go below this height (lets you wade in the sea). Set automatically from the water level.")]
        public float minFeetHeight = float.NegativeInfinity;

        public event Action<Vector2> Tapped;

        public bool StickActive => moveId >= 0;
        public Vector2 StickOrigin => moveOrigin;
        public Vector2 StickPosition => moveNow;

        CharacterController controller;
        float yaw, pitch, fallSpeed;
        int moveId = -1, lookId = -1;
        Vector2 moveOrigin, moveNow, lookLast, lookStart;
        float lookStartTime;
        Vector2 lastMouse;

        void Awake()
        {
            controller = GetComponent<CharacterController>();
            if (!head) head = GetComponentInChildren<Camera>();
            yaw = transform.eulerAngles.y;
        }

        void OnEnable()
        {
            moveId = lookId = -1;
            lastMouse = TouchSticks.MousePosition;
        }

        void Update()
        {
            Vector2 move = TouchSticks.KeyboardMove();
            Vector2 look = Vector2.zero;
            float dpi = PointerInput.DpiScale;

            foreach (var f in TouchSticks.ReadFingers())
            {
                if (f.began)
                {
                    if (f.position.x < Screen.width * 0.5f && moveId < 0) { moveId = f.id; moveOrigin = moveNow = f.position; }
                    else if (lookId < 0) { lookId = f.id; lookLast = lookStart = f.position; lookStartTime = Time.time; }
                }
                if (f.id == moveId)
                {
                    moveNow = f.position;
                    if (f.ended) moveId = -1;
                }
                else if (f.id == lookId)
                {
                    look += (f.position - lookLast) / dpi;
                    lookLast = f.position;
                    if (f.ended)
                    {
                        if ((f.position - lookStart).magnitude / dpi < tapMaxMove && Time.time - lookStartTime < tapMaxTime)
                            Tapped?.Invoke(f.position);
                        lookId = -1;
                    }
                }
            }
            if (moveId >= 0)
                move += Vector2.ClampMagnitude((moveNow - moveOrigin) / dpi / stickRadius, 1f);

            Vector2 mouse = TouchSticks.MousePosition;
            if (TouchSticks.MouseLookHeld) look += (mouse - lastMouse) / dpi;
            lastMouse = mouse;
            if (TouchSticks.MouseClickedThisFrame && Input_NoTouches()) Tapped?.Invoke(mouse);

            yaw += look.x * lookSpeed;
            pitch = Mathf.Clamp(pitch - look.y * lookSpeed, -80f, 80f);
            transform.rotation = Quaternion.Euler(0f, yaw, 0f);
            if (head) head.transform.localRotation = Quaternion.Euler(pitch, 0f, 0f);

            move = Vector2.ClampMagnitude(move, 1f);
            Vector3 velocity = (transform.right * move.x + transform.forward * move.y) * walkSpeed;
            fallSpeed = controller.isGrounded ? -1f : fallSpeed - gravity * Time.deltaTime;
            velocity.y = fallSpeed;
            controller.Move(velocity * Time.deltaTime);

            // Standing on the sea bed: wade instead of sinking.
            if (transform.position.y < minFeetHeight)
            {
                controller.enabled = false;
                transform.position = new Vector3(transform.position.x, minFeetHeight, transform.position.z);
                controller.enabled = true;
                fallSpeed = 0f;
            }
        }

        static bool Input_NoTouches() => TouchSticks.ReadFingers().Count == 0;

        // Moves the player to a spot, facing a target.
        public void TeleportTo(Vector3 feetPosition, Vector3 lookAt)
        {
            controller.enabled = false;
            transform.position = feetPosition;
            controller.enabled = true;
            Vector3 d = lookAt - feetPosition;
            if (new Vector2(d.x, d.z).sqrMagnitude > 1e-4f) yaw = Mathf.Atan2(d.x, d.z) * Mathf.Rad2Deg;
            pitch = 25f;
            fallSpeed = 0f;
        }

        void OnGUI()
        {
            if (!StickActive) return;
            float r = stickRadius * PointerInput.DpiScale;
            DrawCircle(moveOrigin, r, new Color(1, 1, 1, 0.15f));
            DrawCircle(moveOrigin + Vector2.ClampMagnitude(moveNow - moveOrigin, r), r * 0.4f, new Color(1, 1, 1, 0.35f));
        }

        static void DrawCircle(Vector2 screenCenter, float radius, Color c)
        {
            var old = GUI.color;
            GUI.color = c;
            var rect = new Rect(screenCenter.x - radius, Screen.height - screenCenter.y - radius, radius * 2, radius * 2);
            GUI.DrawTexture(rect, Texture2D.whiteTexture, ScaleMode.StretchToFill, true, 0, c, 0, radius);
            GUI.color = old;
        }
    }
}
