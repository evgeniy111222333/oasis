# Walking foot-slide diagnosis

No gameplay code, server settings, animation assets or published releases were changed.

The installed client is 1.5.63, SHA-256
`2c181226aaa80929906ea5aad82ed7615f1aa12a0ac6a9c57390ed7be3656df3`.
Its actual client log confirms `player-animations-v3-20261015-1` and 22 loaded clips.

## Reproduction and measurement

`WalkSlipAudit.java` invokes the current compiled production `AnimationLibrary`,
`LocomotionController` and `PlayerTorsoSkeleton`. It reconstructs both soles using the
production ankle/toe skinning, knee/hip transforms, and the native AvatarRenderer scale
of 0.9375. Straight grounded walking is simulated at 240 samples per second, after a
three-second warmup. A contact sample is a lowest sole point within 1.5 cm of the floor.
Its world velocity compares the same material point on successive frames, including
the character's translation. These are kinematic measurements, not a recording of the
user's live player or network movement.

The animation advances by `speed / 4.3`; its full loop lasts 1.2 source seconds.
Consequently every complete walking cycle covers **5.16 world blocks**, at any steady
movement speed. That is 2.58 blocks per nominal step. The retargeted ankle's entire
forward/backward range is only about **0.555 blocks**.

At a body speed of 4.3 blocks/s, the median speed of a sole material point at contact is
**3.408 blocks/s**. The median support foot therefore travels along the floor at about
**79% of body speed** instead of remaining stationary while the body passes over it.
At body speeds 1, 2 and 5.6 blocks/s the same ratio remains approximately 79%.

Changing playback speed alone was tested in the diagnostic process, without changing
production code. At 2x cadence, median contact slide remains 2.61 blocks/s; at 3x it
remains 2.34 blocks/s. Increasing cadence further produces 6.7–8 steps/s and still
does not eliminate slide because support-phase foot velocity varies within the clip.

## Confirmed causes

1. Walking's reference speed was assigned from movement speed without calibrating the
   exported clip's stride against the retargeted player's actual limb lengths.
2. `groundFootContacts` only lifts the whole skeleton in Y to prevent penetration. It
   does not hold a support foot at a fixed X/Z world contact point. Original hip/knee
   animation continues moving that foot while it touches the floor.
3. Existing verification checks interpolation, poses, UVs, floor penetration and resets;
   it does not check world-space support-foot drift. Passing those tests did not prove
   a physically matched gait.

## Recommended correction, not yet implemented

Calibrate stride distance and cadence together for this rig. Add explicit stance and
swing phases; during stance, hold a world contact target and solve the hip/knee chain
within its reach, then release and blend into the authored swing. Allow the stride to
adapt to movement speed so reducing slide does not require implausibly fast short
steps. Retain the supplied clip as the base choreography. Add a dynamic regression
which measures support-foot world drift at multiple speeds and transitions.

Changing the global player speed alone cannot solve the current mismatch because the
controller proportionally slows the animation and retains 5.16 blocks per cycle.

Evidence: `walk-slip-report.json`, baseline and cadence-experiment CSV files in this
directory. Production locations: `LocomotionController.java:131` and `:222`,
`PlayerTorsoSkeleton.java:48`. Server speed modifiers in `StaminaManager.java:1430`
were reviewed; they change the actual speed but not the controller's stride ratio.
