using System;
using System.Collections;
using System.Collections.Generic;
using System.Linq;
using UnityEngine;

namespace MiniGolfMobile
{
    // Keeps score for a course. Holes can be played in any order: walk up to a tee and start it.
    // Draws the stroke counter, messages and the scorecard.
    public class CourseManager : MonoBehaviour
    {
        public GolfBall ball;
        [Tooltip("Leave empty to use every GolfHole in the scene, ordered by number.")]
        public List<GolfHole> holes = new List<GolfHole>();

        [Tooltip("The hole ends at this many strokes if the ball isn't in yet.")]
        public int maxStrokes = 7;
        public float finishDelay = 2.2f;

        public GolfHole ActiveHole { get; private set; }
        public bool AcceptingShots => ActiveHole && !finishing;
        public int ActiveStrokes => ActiveHole ? strokes[ActiveHole] : 0;

        public event Action<GolfHole> HoleStarted;
        public event Action<GolfHole> HoleFinished;

        readonly Dictionary<GolfHole, int> strokes = new Dictionary<GolfHole, int>();
        readonly Dictionary<GolfHole, int> scores = new Dictionary<GolfHole, int>();
        bool finishing, showCard;
        string banner;
        float bannerUntil;
        GUIStyle label, big, button;

        public bool ShowCard { get => showCard; set => showCard = value; }

        void Start()
        {
            if (!ball) ball = FindFirstObjectByType<GolfBall>(FindObjectsInactive.Include);
            if (holes.Count == 0)
                holes = FindObjectsByType<GolfHole>(FindObjectsSortMode.None).OrderBy(h => h.number).ToList();
            foreach (var h in holes) strokes[h] = 0;

            if (ball)
            {
                ball.Stopped += OnBallStopped;
                ball.WentOutOfBounds += () => ShowBanner("Out of bounds", 1.5f);
                ball.gameObject.SetActive(false);
            }
            if (holes.Count == 0) Debug.LogWarning("CourseManager: no GolfHole found in the scene.");
        }

        public bool IsCurrentHole(GolfHole hole) => hole == null || hole == ActiveHole;

        public void StartHole(GolfHole hole)
        {
            StopAllCoroutines();
            finishing = false;
            ActiveHole = hole;
            strokes[hole] = 0;
            ball.gameObject.SetActive(true);
            ball.PlaceAt(hole.TeePosition);
            ShowBanner($"Hole {hole.number}   Par {hole.par}", 2f);
            HoleStarted?.Invoke(hole);
        }

        public void RegisterStroke()
        {
            if (ActiveHole) strokes[ActiveHole]++;
        }

        void OnBallStopped()
        {
            if (!finishing && ActiveHole && strokes[ActiveHole] >= maxStrokes)
            {
                ShowBanner("Stroke limit reached", finishDelay);
                StartCoroutine(Finish(strokes[ActiveHole] + 1));
            }
        }

        public void OnHoled(GolfHole hole)
        {
            if (finishing || hole != ActiveHole) return;
            int s = strokes[hole];
            ShowBanner(ScoreName(s, hole.par), finishDelay);
            StartCoroutine(Finish(s));
        }

        IEnumerator Finish(int score)
        {
            finishing = true;
            var hole = ActiveHole;
            if (!scores.TryGetValue(hole, out int best) || score < best) scores[hole] = score;
            yield return new WaitForSeconds(finishDelay);
            ball.gameObject.SetActive(false);
            ActiveHole = null;
            finishing = false;
            HoleFinished?.Invoke(hole);
            if (holes.All(scores.ContainsKey))
            {
                showCard = true;
                ShowBanner($"Course complete: {scores.Values.Sum()} (par {holes.Sum(h => h.par)})", 5f);
            }
        }

        public void AbandonHole()
        {
            StopAllCoroutines();
            finishing = false;
            ActiveHole = null;
            if (ball) ball.gameObject.SetActive(false);
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

        public void ShowBanner(string text, float seconds)
        {
            banner = text;
            bannerUntil = Time.time + seconds;
        }

        public static float UiScale => Mathf.Max(1f, Screen.height / 720f);

        void OnGUI()
        {
            float u = UiScale;
            if (label == null)
            {
                label = new GUIStyle(GUI.skin.label);
                big = new GUIStyle(GUI.skin.label) { alignment = TextAnchor.MiddleCenter, fontStyle = FontStyle.Bold };
                button = new GUIStyle(GUI.skin.button);
            }
            label.fontSize = Mathf.RoundToInt(22 * u);
            big.fontSize = Mathf.RoundToInt(40 * u);
            button.fontSize = Mathf.RoundToInt(20 * u);

            string status = ActiveHole
                ? $"Hole {ActiveHole.number}   Par {ActiveHole.par}   Strokes {strokes[ActiveHole]}"
                : $"Walk to a tee to play   ({scores.Count}/{holes.Count} holes played)";
            GUI.Label(new Rect(16 * u, 12 * u, 900 * u, 34 * u), status, label);

            if (Time.time < bannerUntil && !string.IsNullOrEmpty(banner))
                GUI.Label(new Rect(0, Screen.height * 0.18f, Screen.width, 60 * u), banner, big);

            if (showCard) DrawScorecard(u);
        }

        void DrawScorecard(float u)
        {
            float rowH = 28 * u;
            int perColumn = Mathf.CeilToInt(holes.Count / 2f);
            float w = Mathf.Min(Screen.width - 32 * u, 760 * u);
            float h = rowH * (perColumn + 3) + 20 * u;
            var r = new Rect((Screen.width - w) / 2, (Screen.height - h) / 2, w, h);
            GUI.Box(r, GUIContent.none);
            GUI.Box(r, GUIContent.none);
            for (int col = 0; col < 2; col++)
            {
                float x = r.x + 16 * u + col * w / 2;
                float y = r.y + 10 * u;
                GUI.Label(new Rect(x, y, w / 2, rowH), "Hole   Par   Score", label);
                for (int i = col * perColumn; i < Mathf.Min(holes.Count, (col + 1) * perColumn); i++)
                {
                    y += rowH;
                    string s = scores.TryGetValue(holes[i], out int v) ? v.ToString() : "-";
                    GUI.Label(new Rect(x, y, w / 2, rowH), $"{holes[i].number,-6} {holes[i].par,-5} {s}", label);
                }
            }
            GUI.Label(new Rect(r.x + 16 * u, r.yMax - rowH * 1.5f, w, rowH),
                $"Total   par {holes.Sum(h => h.par)}   score {scores.Values.Sum()} ({scores.Count} holes)", label);
        }
    }
}
