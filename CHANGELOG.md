# Changelog

All notable changes to EdgePanel. Each version is tagged in git.

## [3.0] - 2026-10-23

### Fixed
- **Drag-to-open from the handle.** Pressing the handle and pulling inward now opens the panel and follows the finger, with velocity hand-off. The handle's touch area is wider than it looks, it sets system gesture exclusion rects (API 29+) and tracking starts on `ACTION_DOWN`.
- **Jump on close.** The overlay window used to be resized and moved at the start of the close animation, so the old frame flashed at a new position for a few milliseconds. The handle and the panel now live in two fixed-size windows that are never resized or moved; the panel window is only shown or hidden. Tap-to-close starts from rest with a critically damped spring, so it only shrinks.

### Added
- Liquid-glass slider thumb (lens magnification, top highlight, bright rim, soft shadow, accent dot) that squashes and stretches with drag speed.
- Animated icons: bell shake, bell-off shake + fade, vibrate jiggle, moon swing-in, speaker waves, sun rays, Work/Personal pop, torch beams, Wi-Fi ripple, play/pause morph.
- 14 synthesized sound effects (SoundPool) and `VibrationEffect.Composition` haptics with fallbacks; slider ticks every 10% and firmer ends.
- New pages: **Quick toggles** (flashlight, Wi-Fi, Bluetooth, screenshot), **Music + Battery**, **Apps** (camera, calculator, 2 user-picked apps).
- Page manager in setup: enable/disable and reorder pages.
- Sound and Vibration switches.
- Accessibility service for the screenshot button; optional notification listener for the song title.
- Tests for drag-to-open and monotonic, jump-free collapse.

## [2.0] - 2026-10-23

### Changed
- Gesture handling rewritten: interactive vertical paging that follows the finger and snaps with a spring; fast flicks change page, slow drags on a slider adjust it; page dots as large tap targets.
- All motion moved to AndroidX DynamicAnimation springs tuned like iOS (response ≈ 0.35-0.55 s, damping ≈ 0.75-0.85), interruptible with velocity hand-off.
- Slimmer iOS Control Center-style volume pill with a continuous fill and soft glow.

### Added
- Brightness slider (`WRITE_SETTINGS`) with a perceptual gamma curve; turns off auto-brightness while dragging.
- Four themes, Classic Black, Glass, Neon and Minimal Light, each with its own vector icon set.
- Per-theme accent colour picker and live preview in the setup screen.
- Haptic ticks on page snap and slider ends.
- Robolectric tests.

## [1.0] - 2026-10-23

### Added
- Foreground overlay service with a thin handle on the right or left screen edge.
- Black panel that bulges out of the edge, drawn with a custom `Path` and cubic-bezier concave fillets.
- Media volume slider, Silent / Vibrate / Ring toggle with floating labels, Do Not Disturb and Work / Personal presets.
- Setup screen with permission grants, start/stop, edge position and start on boot.

[3.0]: https://github.com/nitixhhh/EdgePanel/releases/tag/v3.0
[2.0]: https://github.com/nitixhhh/EdgePanel/releases/tag/v2.0
[1.0]: https://github.com/nitixhhh/EdgePanel/releases/tag/v1.0
