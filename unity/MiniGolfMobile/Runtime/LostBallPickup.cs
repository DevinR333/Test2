using System;
using System.Collections;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile
{
    // A hidden collectible ball. Collect it by rolling your ball into it or by tapping it.
    // Found balls are remembered on the device between sessions.
    public class LostBallPickup : MonoBehaviour
    {
        [Tooltip("Unique name for saving. Filled in automatically from the object's name and position if empty.")]
        public string id;
        [Tooltip("Your ball collects it when this close (metres).")]
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

        void Start() => ball = FindFirstObjectByType<GolfBall>();

        void Update()
        {
            if (!collected && ball && Vector3.Distance(ball.transform.position, transform.position) < touchRadius)
                Collect();
        }

        public void Collect()
        {
            if (collected) return;
            collected = true;
            PlayerPrefs.SetInt(Key, 1);
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

        public static void TryCollectAt(Camera cam, Vector2 screenPos, float reach)
        {
            Ray ray = cam.ScreenPointToRay(screenPos);
            foreach (var hit in Physics.RaycastAll(ray, reach, Physics.DefaultRaycastLayers, QueryTriggerInteraction.Collide))
            {
                var pickup = hit.collider.GetComponentInParent<LostBallPickup>();
                if (pickup) { pickup.Collect(); return; }
            }
        }

        public static int CountFound(string sceneName, string[] ids)
        {
            int n = 0;
            foreach (var i in ids)
                if (PlayerPrefs.GetInt($"lostball/{sceneName}/{i}", 0) == 1) n++;
            return n;
        }
    }
}
