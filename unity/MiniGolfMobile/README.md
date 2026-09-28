# MiniGolfMobile (Unity)

First-person mini golf for phones, for Unity 6 (6000.3), written from scratch. You walk around a
course, walk up to any hole's tee to play it, putt with touch controls, and grab hidden lost balls.
It's meant to be added to course scenes you've exported yourself. This folder contains **only new code**,
no game assets. Don't commit exported game files to this repository.

## Controls

| Where | Phone | Editor |
| --- | --- | --- |
| Walking | Left thumb: move stick. Right side: drag to look, tap to grab a lost ball | WASD, hold right mouse to look, left click to grab |
| At a tee | **Play hole N** button | same |
| Putting | Drag back from the ball and let go. Drag elsewhere to look, pinch to zoom | click-drag from the ball, scroll to zoom |
| Buttons | **Walk**, **Putt**, **Go to ball**, **Quit hole**, **Card**, **Menu** | same |

## Scripts

| Script | What it does |
| --- | --- |
| `FirstPersonWalker` | Walking and looking in first person |
| `PlayerModeController` | Switches between walking and putting; tee / ball / lost ball interactions |
| `GolfBall`, `TouchPutter`, `GolfCamera` | The ball, drag-to-putt, putting camera |
| `GolfHole`, `GolfCup` | Tee, par, cup detection |
| `CourseManager` | Strokes, stroke limit, scores in any hole order, HUD and scorecard |
| `LostBallPickup` | Lost balls: stay where the course put them, saved when found |
| `LevelSelectMenu` | Course list, with lost balls found per course |
| `SpinObstacle`, `PingPongMover`, `GolfPlaySurface`, `OutOfBoundsZone` | Obstacles and course markers |

## Editor menu: Mini Golf

- **Create Test Course**: a small two-hole course to try the controls.
- **Create Level Select Menu**: the course menu, set as the first scene.
- **Set Up Open Course Scene**: turns an exported course scene into a playable one (lost balls, holes, cups, tees, player).
- **Add Set-Up Courses To Build**: adds every set-up course to the build so it shows in the menu.
- **Copy Scene Report**: lists golf-related objects in the open scene and copies the list to the clipboard.

## Tuning

- Shot strength: `TouchPutter.maxShotSpeed`, `powerCurve`
- Roll and stop: `GolfBall.linearDamping`, `stopSpeed`
- Cup: `GolfCup.radius`, `maxSinkSpeed`
- Walking: `FirstPersonWalker.walkSpeed`, `lookSpeed`
- Par for each hole: `GolfHole.par`
