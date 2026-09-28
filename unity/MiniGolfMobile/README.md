# MiniGolfMobile (Unity)

Touch-screen mini golf gameplay for Unity 6 (6000.3), written from scratch. It's meant to be dropped
onto course scenes you've exported yourself. This folder contains **only new code**, no game assets.
Don't commit exported game files to this repository.

## What's included

| Script | What it does |
| --- | --- |
| `GolfBall` | Shots, stop detection, reset when out of bounds |
| `TouchPutter` | Drag back from the ball and let go to putt, with an aim line |
| `GolfCamera` | Follows the ball; one-finger drag to look around, pinch to zoom, tap to collect lost balls |
| `GolfHole`, `GolfCup` | Tee position, par, cup detection (works with or without a real cup mesh) |
| `CourseManager` | Strokes, stroke limit, next hole, HUD and scorecard |
| `LostBallPickup` | Collectible lost balls, saved on the device |
| `SpinObstacle`, `PingPongMover` | Simple moving obstacles |
| `GolfPlaySurface`, `OutOfBoundsZone` | Mark the green / out-of-bounds areas |

Menu **Mini Golf** (editor):
- **Create Test Hole** builds and saves a small test hole so you can try the controls.
- **Add Player To Open Scene** adds the ball, camera and CourseManager to a course scene.
- **Copy Scene Report** lists the golf-related objects in the open scene and copies the list to the clipboard.

## Try it in a clean project first

1. Unity Hub → **New project** → **Universal 3D** template, Unity **6000.3.9f1**.
2. Copy this `MiniGolfMobile` folder into the project's `Assets` folder.
3. Menu **Mini Golf → Create Test Hole**, then press **Play**.
   In the editor, click and drag back from the ball to putt, drag elsewhere to look around, and use the scroll wheel to zoom.
4. On a phone: **File → Build Profiles → Android → Switch Platform**, plug in the phone, then **Build And Run**.

## Tuning

Everything that affects feel is exposed in the Inspector:
- Shot strength: `TouchPutter.maxShotSpeed`, `powerCurve`
- Roll and stop: `GolfBall.linearDamping`, `stopSpeed`
- Cup: `GolfCup.radius`, `maxSinkSpeed`
