using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace MiniGolfMobile.EditorTools
{
    // One button: set up Android settings, make sure the menu and the open course are in the build,
    // build the APK, and install it on a phone plugged in by USB.
    public static class PhoneBuild
    {
        const string ApkPath = "C:/walkabout/MiniGolf.apk";

        [MenuItem("Mini Golf/Build APK for Phone", priority = 60)]
        public static void BuildForPhone()
        {
            if (!BuildPipeline.IsBuildTargetSupported(BuildTargetGroup.Android, BuildTarget.Android))
            {
                EditorUtility.DisplayDialog("Mini Golf",
                    "Android Build Support isn't installed for this Unity version.\n\n" +
                    "Unity Hub > Installs > 6000.3.9f1 > gear icon > Add modules > tick Android Build Support (with OpenJDK and Android SDK & NDK).", "OK");
                return;
            }

            bool install = EditorUtility.DisplayDialogComplex("Mini Golf",
                "Build the game for Android?\n\nThe first build can take 30-60 minutes.\n\n" +
                "If your phone is plugged in with USB debugging on, it can be installed on it automatically.",
                "Build and install on phone", "Cancel", "Build APK file only") is var choice && choice == 0;
            if (choice == 1) return;

            // Keep the course that's open, and make sure there's a menu to pick courses from.
            var open = SceneManager.GetActiveScene();
            if (open.isDirty) EditorSceneManager.SaveScene(open);
            string coursePath = open.path;
            string menuPath = "Assets/MiniGolfMobile/Generated/LevelSelect.unity";
            if (!File.Exists(menuPath))
            {
                MiniGolfTools.CreateLevelSelectSilently(menuPath);
                if (!string.IsNullOrEmpty(coursePath)) EditorSceneManager.OpenScene(coursePath);
            }
            MiniGolfTools.AddCoursesToBuildSilently();
            var scenes = EditorBuildSettings.scenes.Where(s => s.enabled && File.Exists(s.path)).Select(s => s.path).ToList();
            scenes.Remove(menuPath);
            scenes.Insert(0, menuPath);

            ConfigureAndroid();

            var options = new BuildPlayerOptions
            {
                scenes = scenes.ToArray(),
                locationPathName = ApkPath,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = install ? BuildOptions.AutoRunPlayer : BuildOptions.None
            };
            Directory.CreateDirectory(Path.GetDirectoryName(ApkPath));
            BuildReport report = BuildPipeline.BuildPlayer(options);

            if (report.summary.result == BuildResult.Succeeded)
            {
                string where = install ? "It should now be opening on your phone." : $"Copy {ApkPath} to your phone and open it to install.";
                EditorUtility.DisplayDialog("Mini Golf", $"Build finished ({report.summary.totalSize / (1024 * 1024)} MB).\n\n{where}", "OK");
            }
            else
            {
                var errors = report.steps.SelectMany(s => s.messages).Where(m => m.type == LogType.Error || m.type == LogType.Exception)
                    .Select(m => m.content).Take(8);
                string text = string.Join("\n\n", errors);
                EditorGUIUtility.systemCopyBuffer = text;
                EditorUtility.DisplayDialog("Mini Golf", "The build failed. The errors are copied to the clipboard; paste them to Claude.\n\n" + text, "OK");
            }
        }

        static void ConfigureAndroid()
        {
            if (EditorUserBuildSettings.activeBuildTarget != BuildTarget.Android)
                EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);

            var android = NamedBuildTarget.Android;
            PlayerSettings.companyName = "MiniGolfMobile";
            PlayerSettings.productName = "Mini Golf";
            PlayerSettings.SetApplicationIdentifier(android, "com.minigolfmobile.game");
            PlayerSettings.SetScriptingBackend(android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel26;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.AutoRotation;
            PlayerSettings.allowedAutorotateToLandscapeLeft = true;
            PlayerSettings.allowedAutorotateToLandscapeRight = true;
            PlayerSettings.allowedAutorotateToPortrait = false;
            PlayerSettings.allowedAutorotateToPortraitUpsideDown = false;
            EditorUserBuildSettings.androidBuildSystem = AndroidBuildSystem.Gradle;
            EditorUserBuildSettings.buildAppBundle = false;
        }
    }
}
