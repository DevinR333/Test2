using System.Collections;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;

namespace MiniGolfMobile
{
    // Runs a round: places the ball on each tee, counts strokes, moves on after the ball
    // is holed or the stroke limit is reached, and draws a simple on-screen HUD and scorecard.
    public class CourseManager : MonoBehaviour
    {
        public GolfBall ball;
        public GolfCamera golfCamera;
        [Tooltip("Leave empty to use every GolfHole in the scene, ordered by number.")]
        public List<GolfHole> holes = new List<GolfHole>();

        [Tooltip("The hole ends at this many strokes if the ball isn't in yet.")]
        public int maxStrokes = 7;
        public float nextHoleDelay = 2.2f;

        public int CurrentIndex { get; private set; }
        public GolfHole CurrentHole => holes.Count > 0 ? holes[CurrentIndex] : null;
        public bool AcceptingShots => !transitioning && !finished;

        int[] strokes;
        bool transitioning, finished, showCard;
        string banner;
        float bannerUntil;
        GUIStyle label, big, button;

        void Start()
        {
            if (!ball) ball = FindFirstObjectByType<GolfBall>();
            if (!golfCamera) golfCamera = FindFirstObjectByType<GolfCamera>();
            if (holes.Count == 0)
                holes = FindObjectsByType<GolfHole>(FindObjectsSortMode.None).OrderBy(h => h.number).ToList();

            strokes = new int[holes.Count];
            if (ball)
            {
                ball.Stopped += OnBallStopped;
                ball.WentOutOfBounds += () => ShowBanner("Out of bounds", 1.5f);
            }
            if (holes.Count > 0) StartHole(0);
            else Debug.LogWarning("CourseManager: no GolfHole found in the scene.");
        }

        public bool IsCurrentHole(GolfHole hole) => hole == null || hole == CurrentHole;

        public void StartHole(int index)
        {
            StopAllCoroutines();
            CurrentIndex = Mathf.Clamp(index, 0, holes.Count - 1);
            strokes[CurrentIndex] = 0;
            transitioning = false;
            finished = false;
            var hole = CurrentHole;
            ball.PlaceAt(hole.TeePosition);
            if (golfCamera)
            {
                if (hole.cup) golfCamera.LookToward(hole.cup.transform.position);
                golfCamera.SnapToBall();
            }
            ShowBanner($"Hole {hole.number}   Par {hole.par}", 2f);
        }

        public void RegisterStroke()
        {
            if (strokes != null && holes.Count > 0) strokes[CurrentIndex]++;
        }

        void OnBallStopped()
        {
            if (transitioning || finished) return;
            if (strokes[CurrentIndex] >= maxStrokes)
            {
                ShowBanner("Stroke limit reached", nextHoleDelay);
                StartCoroutine(NextHoleAfterDelay());
            }
        }

        public void OnHoled(GolfHole hole)
        {
            if (transitioning) return;
            int s = strokes[CurrentIndex];
            ShowBanner(ScoreName(s, CurrentHole.par), nextHoleDelay);
            StartCoroutine(NextHoleAfterDelay());
        }

        IEnumerator NextHoleAfterDelay()
        {
            transitioning = true;
            yield return new WaitForSeconds(nextHoleDelay);
            if (CurrentIndex + 1 < holes.Count)
            {
                StartHole(CurrentIndex + 1);
            }
            else
            {
                transitioning = false;
                finished = true;
                showCard = true;
                ShowBanner($"Round complete: {strokes.Sum()} strokes (par {holes.Sum(h => h.par)})", 5f);
            }
        }

        static string ScoreName(int strokes, int par)
        {
            if (strokes == 1) return "Hole in one!";
            switch (strokes - par)
            {
                case <= -3: return "Albatross!";
                case -2: return "Eagle!";
                case -1: return "Birdie!";
                case 0: return "Par";
                case 1: return "Bogey";
                case 2: return "Double bogey";
                default: return $"{strokes} strokes";
            }
        }

        void ShowBanner(string text, float seconds)
        {
            banner = text;
            bannerUntil = Time.time + seconds;
        }

        void OnGUI()
        {
            if (holes.Count == 0) return;
            float u = Mathf.Max(1f, Screen.height / 720f);
            if (label == null)
            {
                label = new GUIStyle(GUI.skin.label);
                big = new GUIStyle(GUI.skin.label) { alignment = TextAnchor.MiddleCenter, fontStyle = FontStyle.Bold };
                button = new GUIStyle(GUI.skin.button);
            }
            label.fontSize = Mathf.RoundToInt(22 * u);
            big.fontSize = Mathf.RoundToInt(40 * u);
            button.fontSize = Mathf.RoundToInt(20 * u);

            var hole = CurrentHole;
            GUI.Label(new Rect(16 * u, 12 * u, 600 * u, 34 * u),
                $"Hole {hole.number}   Par {hole.par}   Strokes {strokes[CurrentIndex]}", label);

            float bw = 120 * u, bh = 48 * u, x = Screen.width - bw - 12 * u;
            if (GUI.Button(new Rect(x, 12 * u, bw, bh), showCard ? "Close" : "Card", button)) showCard = !showCard;
            if (GUI.Button(new Rect(x, 68 * u, bw, bh), "Reset ball", button) && !ball.InCup) ball.ResetToLastRest();
            if (GUI.Button(new Rect(x, 124 * u, bw / 2 - 4 * u, bh), "<", button)) StartHole(CurrentIndex - 1);
            if (GUI.Button(new Rect(x + bw / 2 + 4 * u, 124 * u, bw / 2 - 4 * u, bh), ">", button)) StartHole(CurrentIndex + 1);

            if (Time.time < bannerUntil && !string.IsNullOrEmpty(banner))
                GUI.Label(new Rect(0, Screen.height * 0.18f, Screen.width, 60 * u), banner, big);

            if (showCard) DrawScorecard(u);
        }

        void DrawScorecard(float u)
        {
            float w = Mathf.Min(Screen.width - 32 * u, 700 * u);
            float rowH = 30 * u;
            float h = rowH * (holes.Count + 2) + 20 * u;
            var r = new Rect((Screen.width - w) / 2, (Screen.height - h) / 2, w, h);
            GUI.Box(r, GUIContent.none);
            float y = r.y + 10 * u;
            GUI.Label(new Rect(r.x + 16 * u, y, w, rowH), "Hole     Par     Strokes", label);
            for (int i = 0; i < holes.Count; i++)
            {
                y += rowH;
                string s = strokes[i] > 0 ? strokes[i].ToString() : "-";
                GUI.Label(new Rect(r.x + 16 * u, y, w, rowH), $"{holes[i].number,-8} {holes[i].par,-7} {s}", label);
            }
            y += rowH;
            GUI.Label(new Rect(r.x + 16 * u, y, w, rowH), $"Total    {holes.Sum(h => h.par),-7} {strokes.Sum()}", label);
        }
    }
}
