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

        GUIStyle button;

        void Start()
        {
            if (!walker) walker = FindFirstObjectByType<FirstPersonWalker>(FindObjectsInactive.Include);
            if (!golfCamera) golfCamera = FindFirstObjectByType<GolfCamera>(FindObjectsInactive.Include);
            if (!puttRig && golfCamera) puttRig = golfCamera.gameObject;
            if (!course) course = FindFirstObjectByType<CourseManager>(FindObjectsInactive.Include);
            if (!ball) ball = FindFirstObjectByType<GolfBall>(FindObjectsInactive.Include);

            walker.Tapped += OnWalkerTap;
            course.HoleStarted += OnHoleStarted;
            course.HoleFinished += _ => EnterWalk();
            LostBallPickup.RecordTotalForActiveScene();
            EnterWalk();
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
