using UnityEngine;

namespace MiniGolfMobile
{
    // Put on a trigger collider (water, off the edge, etc). A ball that enters it
    // goes back to where it was hit from.
    [RequireComponent(typeof(Collider))]
    public class OutOfBoundsZone : MonoBehaviour
    {
        void Reset() => GetComponent<Collider>().isTrigger = true;
    }
}
