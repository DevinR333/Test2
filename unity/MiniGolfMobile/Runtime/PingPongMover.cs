using UnityEngine;

namespace MiniGolfMobile
{
    // Slides back and forth between its start position and an offset (gates, sliding blocks).
    public class PingPongMover : MonoBehaviour
    {
        public Vector3 offset = new Vector3(0.5f, 0f, 0f);
        [Tooltip("Seconds for one trip in one direction.")]
        public float travelTime = 2f;
        [Tooltip("Pause at each end, in seconds.")]
        public float pause = 0.5f;

        Rigidbody rb;
        Vector3 start;

        void Awake()
        {
            rb = GetComponent<Rigidbody>();
            if (!rb) rb = gameObject.AddComponent<Rigidbody>();
            rb.isKinematic = true;
            rb.interpolation = RigidbodyInterpolation.Interpolate;
            start = transform.position;
        }

        void FixedUpdate()
        {
            float cycle = 2f * (travelTime + pause);
            float t = Time.time % cycle;
            float a;
            if (t < travelTime) a = t / travelTime;
            else if (t < travelTime + pause) a = 1f;
            else if (t < 2f * travelTime + pause) a = 1f - (t - travelTime - pause) / travelTime;
            else a = 0f;
            rb.MovePosition(start + offset * Mathf.SmoothStep(0f, 1f, a));
        }
    }
}
