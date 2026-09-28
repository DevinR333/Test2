using UnityEngine;

namespace MiniGolfMobile
{
    // One hole of a course: where the ball starts, the cup, and par.
    public class GolfHole : MonoBehaviour
    {
        public int number = 1;
        public int par = 2;
        [Tooltip("Where the ball is placed at the start of the hole. Uses this object if empty.")]
        public Transform tee;
        public GolfCup cup;

        public Vector3 TeePosition => (tee ? tee.position : transform.position) + Vector3.up * 0.025f;

        void Reset()
        {
            cup = GetComponentInChildren<GolfCup>();
        }
    }
}
