using UnityEngine;

namespace MiniGolfMobile
{
    // Spins steadily (windmill blades, turntables). Uses a kinematic rigidbody so it pushes the ball properly.
    public class SpinObstacle : MonoBehaviour
    {
        public Vector3 localAxis = Vector3.up;
        public float degreesPerSecond = 60f;

        Rigidbody rb;

        void Awake()
        {
            rb = GetComponent<Rigidbody>();
            if (!rb) rb = gameObject.AddComponent<Rigidbody>();
            rb.isKinematic = true;
            rb.interpolation = RigidbodyInterpolation.Interpolate;
        }

        void FixedUpdate()
        {
            Quaternion step = Quaternion.AngleAxis(degreesPerSecond * Time.fixedDeltaTime, localAxis);
            rb.MoveRotation(rb.rotation * step);
        }
    }
}
