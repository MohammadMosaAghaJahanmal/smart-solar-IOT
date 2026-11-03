# Camera replacement modes and floating controls

These controls belong to the integrated Virtual Camera Pro application. Install
the app and the camera-consuming apps inside the same supported VMOS guest; no
separate broker or overlay APK is required. Follow
[installation and guest preparation](UNIFIED_INSTALLATION.md) first.

## Choose which cameras to replace

1. Select an image or video in Virtual Camera Pro.
2. If a source is already **Active**, choose **Deactivate Virtual Camera** before
   changing its replacement mode, camera or output resolution.
3. Under **Camera to replace**, choose a mode:

   | Mode | Camera output |
   | --- | --- |
   | **Front Camera Only** | The detected front camera receives the selected media; the back camera keeps its ordinary feed. |
   | **Back Camera Only** | The detected back camera receives the selected media; the front camera keeps its ordinary feed. |
   | **Replace Both Cameras** | The detected front and back cameras receive the same selected media and source transform. |

4. Choose an available output resolution and press **Activate Virtual Camera**.
   Wait for **Active**, then open or reopen the consuming camera app inside VMOS.

The buttons use Android's reported camera IDs and lens facing. Any additional
camera choices show their actual IDs. **Replace Both Cameras** is available only
when the bridge reports compatible front and back routes and a shared output
resolution. In that mode, the resolution selector lists the common sizes; the
app selects a compatible size if the previous single-camera size is unavailable.

Both-camera mode uses one media producer and playback timeline for both routes.
A consuming app can switch between front and back cameras while the replacement
remains active; switching does not start a separate copy of the video. A camera
app may still close and reopen its own preview while switching lenses. This mode
does not promise that the guest supports opening two cameras concurrently.

Mode, camera and resolution choices are disabled while **Active**. Rotation,
fit/fill scaling, source zoom and source position remain adjustable while the
feed is active. Deactivation retains the selected media and settings for the
next activation.

## Use another camera app's own controls

When a camera app sends standard camera zoom/crop requests, the guest camera
bridge applies those requests to the actual delivered frames. A centered crop
zooms in; an off-center crop also changes the viewed position. The app must
submit those requests: a drag used for focus or a gesture the app ignores cannot
automatically become a camera pan.

The existing source preview in Virtual Camera Pro also supports pinch, drag,
zoom buttons and **Reset**. Those controls change the shared source renderer,
including its actual camera frames. In both-camera mode, the source transform
applies to both routes. Each consuming camera session can additionally request
its own crop.

## Show the optional floating controller

The floating controller supplies source controls while another app is open.
It does not modify that app or add gestures to its preview.

1. In Virtual Camera Pro, find **Reframe in other apps**.
2. Choose **Allow Floating Controller** if shown. Android opens this app's
   **Display over other apps** settings. Enable that permission inside the guest
   and return to Virtual Camera Pro. This permission is separate from Camera
   permission and guest root approval.
3. Choose **Show Floating Controller**. Granting permission alone does not show
   controls or activate the camera. Select media and activate it separately.
4. Open the consuming camera app inside the same VMOS guest.

Use the floating controls as follows:

| Control | Effect |
| --- | --- |
| Pinch on the pad labeled **Pinch to zoom / Drag to move media** | Zoom the selected source between 1× and 8×. |
| Drag on that pad | Move the selected source horizontally or vertically. Zoom in first to expose room for reframing. |
| **− Zoom**, **+ Zoom** | Adjust source zoom in steps. |
| Arrow buttons | Adjust source position in steps. |
| **Reset** | Restore source zoom to 1× and center its position. |
| Drag the **Camera controls** header | Move the floating panel without moving the media. |
| Header **− / +** | Collapse or expand the controls. |
| Header **×**, notification **Hide controls**, or **Hide Floating Controller** in the app | Close the panel while retaining the source transform and current camera activation. |

The panel shows the feed state and source zoom/position. Opening it does not
start an inactive camera. Its gestures update the same native renderer used by
the camera broker, rather than applying a visual effect only to this panel or
the controller preview. The latest requested source transform is retained while
the camera remains active and when the panel is closed. Resetting the source
transform does not reset an additional zoom/crop still requested by the
consuming app; reset that app's own camera zoom as well when necessary.

Overlay permission is optional. The existing camera and source controls remain
available without it. If Android removes the permission, the floating panel
closes; check the permission again before showing it.

## Compatibility and verification scope

The existing verified physical-phone integration is VMOS `2.0.0-alpha13`, guest
ROM `10075`, Android 9/API 28, ARM64, on the inspected Infinix X687B Android 10
host. Camera replacement applies to apps in that guest. Other phone models,
guest ROMs and Android 11–16 hosts remain untested. The delivered guest build's
development signing requirements are explained in the
[installation guide](UNIFIED_INSTALLATION.md#development-root-authorization-build).

Release 16's accepted image/video, color, crop, setup, restore and recovery
results remain recorded in [UNIFIED_ACCEPTANCE.md](UNIFIED_ACCEPTANCE.md).
Version 2.1's camera modes and floating controls passed ten actual device feature
tests, including front/back image/video output, held-open transforms, independent
camera crop requests and both-camera process recovery. The
[camera modes acceptance report](CAMERA_MODES_ACCEPTANCE.md) records the exact
delivered APK and scope, alongside the native, Kotlin, UI, type and lint checks.
