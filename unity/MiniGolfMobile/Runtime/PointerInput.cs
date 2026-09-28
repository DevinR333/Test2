using UnityEngine;
#if ENABLE_INPUT_SYSTEM
using UnityEngine.InputSystem;
#endif

namespace MiniGolfMobile
{
    // Reads the primary finger (or mouse in the editor) through whichever input backend
    // the project has enabled, so the rest of the code doesn't care which one it is.
    public static class PointerInput
    {
        public static bool IsPressed
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                var p = Pointer.current;
                return p != null && p.press.isPressed;
#else
                return Input.GetMouseButton(0);
#endif
            }
        }

        public static bool PressedThisFrame
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                var p = Pointer.current;
                return p != null && p.press.wasPressedThisFrame;
#else
                return Input.GetMouseButtonDown(0);
#endif
            }
        }

        public static bool ReleasedThisFrame
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                var p = Pointer.current;
                return p != null && p.press.wasReleasedThisFrame;
#else
                return Input.GetMouseButtonUp(0);
#endif
            }
        }

        public static Vector2 Position
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                var p = Pointer.current;
                return p != null ? p.position.ReadValue() : Vector2.zero;
#else
                return Input.mousePosition;
#endif
            }
        }

        public static int TouchCount
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                var ts = Touchscreen.current;
                if (ts == null) return 0;
                int n = 0;
                foreach (var t in ts.touches)
                    if (t.press.isPressed) n++;
                return n;
#else
                return Input.touchCount;
#endif
            }
        }

        // How much the two fingers spread apart this frame, in pixels (positive = zoom in).
        // Falls back to the mouse wheel in the editor.
        public static float ZoomDelta
        {
            get
            {
#if ENABLE_INPUT_SYSTEM
                var ts = Touchscreen.current;
                if (ts != null)
                {
                    UnityEngine.InputSystem.Controls.TouchControl a = null, b = null;
                    foreach (var t in ts.touches)
                    {
                        if (!t.press.isPressed) continue;
                        if (a == null) a = t;
                        else { b = t; break; }
                    }
                    if (a != null && b != null)
                    {
                        Vector2 pa = a.position.ReadValue(), pb = b.position.ReadValue();
                        Vector2 prevA = pa - a.delta.ReadValue(), prevB = pb - b.delta.ReadValue();
                        return (pa - pb).magnitude - (prevA - prevB).magnitude;
                    }
                }
                var m = Mouse.current;
                return m != null ? m.scroll.ReadValue().y * 0.05f : 0f;
#else
                if (Input.touchCount >= 2)
                {
                    Touch a = Input.GetTouch(0), b = Input.GetTouch(1);
                    Vector2 prevA = a.position - a.deltaPosition, prevB = b.position - b.deltaPosition;
                    return (a.position - b.position).magnitude - (prevA - prevB).magnitude;
                }
                return Input.mouseScrollDelta.y * 6f;
#endif
            }
        }

        // Scales pixel distances so gestures feel the same on phones with different screen densities.
        public static float DpiScale => Screen.dpi > 0 ? Screen.dpi / 160f : 1f;
    }
}
