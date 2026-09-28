using UnityEngine;

namespace MiniGolfMobile
{
    // Place at the centre of the cup, level with the green. A ball that rolls over it
    // slowly enough (or falls into a real cup mesh) is holed.
    public class GolfCup : MonoBehaviour
    {
        public GolfHole hole;
        [Tooltip("Cup radius in metres.")]
        public float radius = 0.055f;
        [Tooltip("Faster than this (m/s) and the ball lips out and keeps rolling.")]
        public float maxSinkSpeed = 1.6f;
        [Tooltip("How far below the rim a ball still counts as in the cup.")]
        public float depth = 0.2f;

        GolfBall ball;
        Rigidbody ballBody;
        CourseManager course;

        void Start()
        {
            if (!hole) hole = GetComponentInParent<GolfHole>();
            ball = FindFirstObjectByType<GolfBall>();
            if (ball) ballBody = ball.GetComponent<Rigidbody>();
            course = FindFirstObjectByType<CourseManager>();
        }

        void FixedUpdate()
        {
            if (!ball || ball.InCup) return;
            Vector3 d = ball.transform.position - transform.position;
            if (d.y > 0.06f || d.y < -depth) return;
            if (new Vector2(d.x, d.z).magnitude > radius) return;
            if (ballBody.linearVelocity.magnitude > maxSinkSpeed) return;

            if (course && !course.IsCurrentHole(hole))
            {
                // Wrong hole's cup: send the ball back rather than ending the hole.
                ball.ResetToLastRest();
                return;
            }
            ball.SinkInto(transform.position);
            if (course) course.OnHoled(hole);
        }

        void OnDrawGizmos()
        {
            Gizmos.color = Color.yellow;
            Gizmos.DrawWireSphere(transform.position, radius);
        }
    }
}
