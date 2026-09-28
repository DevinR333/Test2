using System;
using System.Collections;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile
{
    // A hidden collectible ball, left where the course placed it. Walk up to it and tap it to grab it.
    // Found balls are remembered on the device between sessions.
    public class LostBallPickup : MonoBehaviour
    {
        [Tooltip("Unique name for saving. Filled in automatically from the object's name and position if empty.")]
        public string id;
        [Tooltip("Also collect it when your golf ball rolls into it.")]
        public bool collectWithGolfBall;
        [Tooltip("Golf ball distance (metres) that counts as touching.")]
        public float touchRadius = 0.1f;
        [Tooltip("Size of the tap target added when the object has no collider.")]
        public float tapRadius = 0.08f;

        public static event Action<LostBallPickup> Collected;

        GolfBall ball;
        bool collected;

        string Key => $"lostball/{SceneManager.GetActiveScene().name}/{id}";

        void Awake()
        {
            if (string.IsNullOrEmpty(id))
            {
                Vector3Int p = Vector3Int.RoundToInt(transform.position * 100f);
                id = $"{name}@{p.x},{p.y},{p.z}";
            }
            if (PlayerPrefs.GetInt(Key, 0) == 1)
            {
                gameObject.SetActive(false);
                return;
            }
            if (!GetComponentInChildren<Collider>())
            {
                var sc = gameObject.AddComponent<SphereCollider>();
                sc.isTrigger = true;
                sc.radius = tapRadius / Mathf.Max(0.0001f, transform.lossyScale.x);
            }
        }

        void Start() => ball = FindFirstObjectByType<GolfBall>(FindObjectsInactive.Include);

        void Update()
        {
            if (collectWithGolfBall && !collected && ball && ball.gameObject.activeInHierarchy && Vector3.Distance(ball.transform.position, transform.position) < touchRadius)
                Collect();
        }

        public void Collect()
        {
            if (collected) return;
            collected = true;
            PlayerPrefs.SetInt(Key, 1);
            string scene = SceneManager.GetActiveScene().name;
            PlayerPrefs.SetInt(FoundKey(scene), PlayerPrefs.GetInt(FoundKey(scene), 0) + 1);
            PlayerPrefs.Save();
            Collected?.Invoke(this);
            StartCoroutine(ShrinkAway());
        }

        IEnumerator ShrinkAway()
        {
            Vector3 s = transform.localScale;
            for (float t = 0; t < 1f; t += Time.deltaTime / 0.4f)
            {
                transform.localScale = s * (1f - t);
                transform.position += Vector3.up * Time.deltaTime * 0.5f;
                yield return null;
            }
            gameObject.SetActive(false);
        }

        // Grabs the nearest lost ball along a tap, if it is within arm's reach.
        public static bool TryCollectAt(Camera cam, Vector2 screenPos, float reach)
        {
            Ray ray = cam.ScreenPointToRay(screenPos);
            var hits = Physics.RaycastAll(ray, reach, Physics.DefaultRaycastLayers, QueryTriggerInteraction.Collide);
            System.Array.Sort(hits, (a, b) => a.distance.CompareTo(b.distance));
            foreach (var hit in hits)
            {
                var pickup = hit.collider.GetComponentInParent<LostBallPickup>();
                if (pickup) { pickup.Collect(); return true; }
            }
            return false;
        }

        public static string FoundKey(string scene) => $"lostballfound/{scene}";
        public static string TotalKey(string scene) => $"lostballtotal/{scene}";

        // Remembers how many lost balls this course has, for the level menu.
        public static void RecordTotalForActiveScene()
        {
            int total = FindObjectsByType<LostBallPickup>(FindObjectsInactive.Include, FindObjectsSortMode.None).Length;
            PlayerPrefs.SetInt(TotalKey(SceneManager.GetActiveScene().name), total);
        }
    }
}
