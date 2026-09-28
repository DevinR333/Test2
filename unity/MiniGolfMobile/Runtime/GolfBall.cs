using System;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;

namespace MiniGolfMobile
{
    // The player's ball: takes shots, decides when it has stopped, and sends it back
    // to its last resting spot when it leaves the course.
    [RequireComponent(typeof(Rigidbody))]
    public class GolfBall : MonoBehaviour
    {
        [Header("Stopping")]
        [Tooltip("Below this speed (m/s) the ball counts as slowing to a stop.")]
        public float stopSpeed = 0.04f;
        [Tooltip("How long the ball must stay that slow before it is stopped for good.")]
        public float stopTime = 0.35f;

        [Header("Rolling resistance")]
        public float linearDamping = 0.45f;
        public float angularDamping = 1.2f;

        [Header("Out of bounds")]
        [Tooltip("Reset if the ball drops this far below where it was hit from.")]
        public float fallResetDepth = 3f;
        [Tooltip("When the scene marks greens with InPlaySurface, a ball that stops anywhere else is reset.")]
        public bool requirePlaySurface = true;

        // Component names (ours or the original game's) that mark the playable green.
        static readonly HashSet<string> PlaySurfaceTypes = new HashSet<string> { "InPlaySurface", "GolfPlaySurface" };

        public bool IsMoving { get; private set; }
        public bool InCup { get; private set; }
        public Vector3 LastRestPosition { get; private set; }

        public event Action Stopped;
        public event Action WentOutOfBounds;

        Rigidbody rb;
        float slowTimer;
        bool sceneHasPlaySurfaces;

        void Awake()
        {
            rb = GetComponent<Rigidbody>();
            rb.interpolation = RigidbodyInterpolation.Interpolate;
            rb.collisionDetectionMode = CollisionDetectionMode.ContinuousDynamic;
            rb.maxAngularVelocity = 200f;
            rb.linearDamping = linearDamping;
            rb.angularDamping = angularDamping;
            LastRestPosition = transform.position;
        }

        void Start()
        {
            foreach (var mb in FindObjectsByType<MonoBehaviour>(FindObjectsInactive.Exclude, FindObjectsSortMode.None))
            {
                if (mb != null && PlaySurfaceTypes.Contains(mb.GetType().Name)) { sceneHasPlaySurfaces = true; break; }
            }
        }

        public void Hit(Vector3 velocity)
        {
            if (InCup) return;
            LastRestPosition = transform.position;
            rb.isKinematic = false;
            rb.WakeUp();
            rb.linearVelocity = velocity;
            rb.angularVelocity = Vector3.zero;
            IsMoving = true;
            slowTimer = 0f;
        }

        public void PlaceAt(Vector3 position)
        {
            StopAllCoroutines();
            InCup = false;
            rb.isKinematic = false;
            rb.linearVelocity = Vector3.zero;
            rb.angularVelocity = Vector3.zero;
            rb.position = position;
            transform.position = position;
            LastRestPosition = position;
            IsMoving = false;
            slowTimer = 0f;
        }

        public void ResetToLastRest()
        {
            PlaceAt(LastRestPosition);
            WentOutOfBounds?.Invoke();
        }

        public void SinkInto(Vector3 cupCenter)
        {
            if (InCup) return;
            InCup = true;
            IsMoving = false;
            rb.linearVelocity = Vector3.zero;
            rb.angularVelocity = Vector3.zero;
            rb.isKinematic = true;
            StartCoroutine(DropInto(cupCenter));
        }

        IEnumerator DropInto(Vector3 cupCenter)
        {
            Vector3 start = transform.position;
            Vector3 end = cupCenter + Vector3.down * 0.06f;
            for (float t = 0; t < 1f; t += Time.deltaTime / 0.25f)
            {
                transform.position = Vector3.Lerp(start, end, t * t);
                yield return null;
            }
            transform.position = end;
        }

        void FixedUpdate()
        {
            if (!IsMoving || InCup) return;

            if (transform.position.y < LastRestPosition.y - fallResetDepth)
            {
                ResetToLastRest();
                return;
            }

            if (rb.linearVelocity.magnitude < stopSpeed)
            {
                slowTimer += Time.fixedDeltaTime;
                if (slowTimer >= stopTime) Stop();
            }
            else
            {
                slowTimer = 0f;
            }
        }

        void Stop()
        {
            rb.linearVelocity = Vector3.zero;
            rb.angularVelocity = Vector3.zero;
            IsMoving = false;
            slowTimer = 0f;

            if (requirePlaySurface && sceneHasPlaySurfaces && !IsOnPlaySurface())
            {
                ResetToLastRest();
                return;
            }
            Stopped?.Invoke();
        }

        bool IsOnPlaySurface()
        {
            var hits = Physics.RaycastAll(transform.position + Vector3.up * 0.05f, Vector3.down, 0.3f,
                Physics.DefaultRaycastLayers, QueryTriggerInteraction.Ignore);
            foreach (var hit in hits)
            {
                if (hit.collider.attachedRigidbody == rb) continue;
                // The original courses name their play surfaces "...IPS" (in-play surface).
                if (hit.collider.name.Contains("IPS")) return true;
                foreach (var mb in hit.collider.GetComponentsInParent<MonoBehaviour>(true))
                    if (mb != null && PlaySurfaceTypes.Contains(mb.GetType().Name)) return true;
            }
            return false;
        }

        void OnTriggerEnter(Collider other)
        {
            if (!InCup && other.GetComponentInParent<OutOfBoundsZone>() != null)
                ResetToLastRest();
        }
    }
}
