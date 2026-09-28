using System.Linq;
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

        // Courses keep every cup closed with an invisible "HoleCover" collider
        // and only open the one being played, so balls can't drop into other holes.
        Collider[] covers;

        void Awake()
        {
            var root = cup ? cup.transform : transform;
            covers = root.GetComponentsInChildren<Collider>(true).Where(c => c.name == "HoleCover").ToArray();
            SetCupOpen(false);
        }

        public void SetCupOpen(bool open)
        {
            if (covers == null) return;
            foreach (var c in covers)
                if (c) c.enabled = !open;
        }

        void Reset()
        {
            cup = GetComponentInChildren<GolfCup>();
        }
    }
}
