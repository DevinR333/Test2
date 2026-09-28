using System.Collections.Generic;
using System.IO;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile
{
    // Lists every course scene in the build and loads the one you tap. Drag to scroll.
    public class LevelSelectMenu : MonoBehaviour
    {
        public string title = "Choose a course";

        readonly List<(int index, string name)> courses = new List<(int, string)>();
        float scroll;
        Vector2 pressPos, lastPos;
        bool pressed, dragged;
        GUIStyle titleStyle, rowStyle, infoStyle;

        void Start()
        {
            int self = SceneManager.GetActiveScene().buildIndex;
            for (int i = 0; i < SceneManager.sceneCountInBuildSettings; i++)
            {
                if (i == self) continue;
                string path = SceneUtility.GetScenePathByBuildIndex(i);
                courses.Add((i, Path.GetFileNameWithoutExtension(path)));
            }
        }

        void Update()
        {
            if (PointerInput.PressedThisFrame)
            {
                pressed = true;
                dragged = false;
                pressPos = lastPos = PointerInput.Position;
            }
            if (pressed && PointerInput.IsPressed)
            {
                Vector2 p = PointerInput.Position;
                scroll += p.y - lastPos.y;
                lastPos = p;
                if ((p - pressPos).magnitude / PointerInput.DpiScale > 12f) dragged = true;
            }
            float zoom = PointerInput.ZoomDelta;
            if (zoom != 0 && PointerInput.TouchCount < 2) scroll -= zoom * 8f;

            if (pressed && PointerInput.ReleasedThisFrame)
            {
                pressed = false;
                if (!dragged) TryOpen(PointerInput.Position);
            }
        }

        float RowHeight => 84 * CourseManager.UiScale;
        float Top => 110 * CourseManager.UiScale;

        void TryOpen(Vector2 screenPos)
        {
            float guiY = Screen.height - screenPos.y;
            int row = Mathf.FloorToInt((guiY - Top - scroll) / RowHeight);
            if (guiY > Top && row >= 0 && row < courses.Count) SceneManager.LoadScene(courses[row].index);
        }

        void OnGUI()
        {
            float u = CourseManager.UiScale;
            if (titleStyle == null)
            {
                titleStyle = new GUIStyle(GUI.skin.label) { alignment = TextAnchor.MiddleCenter, fontStyle = FontStyle.Bold };
                rowStyle = new GUIStyle(GUI.skin.box) { alignment = TextAnchor.MiddleLeft };
                infoStyle = new GUIStyle(GUI.skin.label) { alignment = TextAnchor.MiddleRight };
            }
            titleStyle.fontSize = Mathf.RoundToInt(40 * u);
            rowStyle.fontSize = Mathf.RoundToInt(28 * u);
            infoStyle.fontSize = Mathf.RoundToInt(20 * u);

            float maxScroll = 0f, minScroll = Mathf.Min(0f, Screen.height - Top - courses.Count * RowHeight - 20 * u);
            scroll = Mathf.Clamp(scroll, minScroll, maxScroll);

            GUI.Label(new Rect(0, 20 * u, Screen.width, 70 * u), title, titleStyle);
            if (courses.Count == 0)
            {
                GUI.Label(new Rect(0, Top, Screen.width, 60 * u), "No courses in Build Settings yet", infoStyle);
                return;
            }

            GUI.BeginGroup(new Rect(0, Top, Screen.width, Screen.height - Top));
            float w = Mathf.Min(Screen.width - 40 * u, 900 * u), x = (Screen.width - w) / 2;
            for (int i = 0; i < courses.Count; i++)
            {
                float y = scroll + i * RowHeight;
                if (y + RowHeight < 0 || y > Screen.height) continue;
                var r = new Rect(x, y + 6 * u, w, RowHeight - 12 * u);
                GUI.Box(r, "   " + Pretty(courses[i].name), rowStyle);
                int total = PlayerPrefs.GetInt(LostBallPickup.TotalKey(courses[i].name), -1);
                if (total >= 0)
                {
                    int found = PlayerPrefs.GetInt(LostBallPickup.FoundKey(courses[i].name), 0);
                    GUI.Label(new Rect(r.x, r.y, r.width - 20 * u, r.height), $"Lost balls {found}/{total}", infoStyle);
                }
            }
            GUI.EndGroup();
        }

        // "TouristTrap_Easy" -> "Tourist Trap (Easy)"
        static string Pretty(string sceneName)
        {
            string s = sceneName.Replace('_', ' ');
            var sb = new System.Text.StringBuilder();
            for (int i = 0; i < s.Length; i++)
            {
                if (i > 0 && char.IsUpper(s[i]) && char.IsLower(s[i - 1])) sb.Append(' ');
                sb.Append(s[i]);
            }
            string r = sb.ToString();
            foreach (var mode in new[] { "Easy", "Hard", "Night" })
                if (r.EndsWith(" " + mode)) r = r.Substring(0, r.Length - mode.Length - 1) + $" ({mode})";
            return r;
        }
    }
}
