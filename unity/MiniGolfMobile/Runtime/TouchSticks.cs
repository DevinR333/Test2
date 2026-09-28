using System.Collections.Generic;
using UnityEngine;
#if ENABLE_INPUT_SYSTEM
using UnityEngine.InputSystem;
#endif

namespace MiniGolfMobile
{
    // Every finger currently on the screen, read through whichever input backend is enabled.
    public struct FingerState
    {
        public int id;
        public Vector2 position;
        public bool began;
        public bool ended;
    }

    public static class TouchSticks
    {
        static readonly List<FingerState> fingers = new List<FingerState>();

        public static List<FingerState> ReadFingers()
        {
            fingers.Clear();
#if ENABLE_INPUT_SYSTEM
            var ts = Touchscreen.current;
            if (ts == null) return fingers;
            foreach (var t in ts.touches)
            {
                bool pressed = t.press.isPressed, released = t.press.wasReleasedThisFrame;
                if (!pressed && !released) continue;
                fingers.Add(new FingerState
                {
                    id = t.touchId.ReadValue(),
                    position = t.position.ReadValue(),
                    began = t.press.wasPressedThisFrame,
                    ended = released
                });
            }
#else
            foreach (var t in Input.touches)
            {
                fingers.Add(new FingerState
                {
                    id = t.fingerId,
                    position = t.position,
                    began = t.phase == TouchPhase.Began,
                    ended = t.phase == TouchPhase.Ended || t.phase == TouchPhase.Canceled
                });
            }
#endif
            return fingers;
        }

        // WASD / arrow keys, for testing in the editor.
        public static Vector2 KeyboardMove()
        {
            Vector2 v = Vector2.zero;
#if ENABLE_INPUT_SYSTEM
            var k = Keyboard.current;
            if (k == null) return v;
            if (k.wKey.isPressed || k.upArrowKey.isPressed) v.y += 1;
            if (k.sKey.isPressed || k.downArrowKey.isPressed) v.y -= 1;
            if (k.dKey.isPressed || k.rightArrowKey.isPressed) v.x += 1;
            if (k.aKey.isPressed || k.leftArrowKey.isPressed) v.x -= 1;
#else
            v.x = (Input.GetKey(KeyCode.D) || Input.GetKey(KeyCode.RightArrow) ? 1 : 0) - (Input.GetKey(KeyCode.A) || Input.GetKey(KeyCode.LeftArrow) ? 1 : 0);
            v.y = (Input.GetKey(KeyCode.W) || Input.GetKey(KeyCode.UpArrow) ? 1 : 0) - (Input.GetKey(KeyCode.S) || Input.GetKey(KeyCode.DownArrow) ? 1 : 0);
#endif
            return Vector2.ClampMagnitude(v, 1f);
        }

        // Right mouse button held = look around, for testing in the editor.
        public static bool MouseLookHeld
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                return Mouse.current != null && Mouse.current.rightButton.isPressed;
#else
                return Input.GetMouseButton(1);
#endif
            }
        }

        public static bool MouseClickedThisFrame
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                return Mouse.current != null && Mouse.current.leftButton.wasReleasedThisFrame;
#else
                return Input.GetMouseButtonUp(0);
#endif
            }
        }

        public static Vector2 MousePosition
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                return Mouse.current != null ? Mouse.current.position.ReadValue() : Vector2.zero;
#else
                return Input.mousePosition;
#endif
            }
        }
    }
}
