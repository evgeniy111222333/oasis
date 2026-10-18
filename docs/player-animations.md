# Authored player animations

The client bundles the original `rp_character-v3.animation.json` as
`assets/eclipseclient/animations/rp_character_v3.animation.json`. Its 22 clips and 22 bones
are retargeted onto the existing RP skin model; the supplied mannequin geometry is not used.
There are no added runtime libraries or custom animation packets. Movement pacing uses
Minecraft's existing attribute synchronization.

`AnimationLibrary` validates and loads numeric tracks once. `LocomotionController` samples
Catmull–Rom tracks with per-entity clocks, real movement speed, directional selection, and
140 ms crossfades from the interrupted pose. Stops use a 90 ms continuing outgoing pose,
and local input release starts them before residual inertia has ended. For observed players,
two distinct replicated deceleration ticks distinguish a stop from a single speed change.
Standing crouch holds the final enter pose.
Stationary turns follow actual yaw changes and do not turn the entity themselves.

`AvatarAnimationRendererMixin` captures after the native avatar extractor has populated
crouch, item and movement flags. Each render state owns an immutable frame and one cached
grounded skeleton shared by the skin and all armor pieces. Remote players use replicated
displacement. Detached model-editor previews use their preview clock and selected movement.

The torso follows pelvis → spine → chest → neck. Arms include clavicles, elbows and wrists;
legs include knees, ankles and toes. Continuous torso and distal skinning keep the original
skin/clothing/armor UV areas. Held item placement follows the actual elbow and wrist chain.
An upward root correction prevents animated soles from entering the native floor, including
the separate native crouch render offset. Authored height remains unchanged above that floor.

`GroundedGait` fits moving legs to the native rig with world-space stance anchors, alternating
swing trajectories and two-bone knee/ankle IK. Moving loops follow actual traveled distance;
their calibrated cycle lengths are 2.25 blocks walking, 3.06 sprinting and 1.15 crouching,
scaled with the avatar. Lateral strafe widens foot separation and shortens stance so feet never
cross the body midline, while direction reversals and sharp yaw turns immediately re-step stale
anchors and align knee hinge axes with the body facing. The
authored torso, arms and pelvis still play, while the hip height adapts to reachable contacts.
One-shots retain their real-time clocks. A final airborne foot lands in 180 ms during a stop,
even at zero body speed; a trailing support also takes a final step before residual inertia
can pull it beyond reach. Stationary crouch holds finish their swing before returning to the
authored pose. Unsupported states, teleports and world changes clear old anchors.
Detached editor walking remains an in-place authored preview. Ground queries are short,
cached per physics tick and cannot retain players through the weak playback cache.
The support plane follows the player's native collision elevation. A neighboring block at
another elevation does not pull the whole body toward an unreachable foothold at a ledge.

RP pace is deliberately slower than vanilla movement: 2.50 blocks/s standing, 3.60 blocks/s
sprinting and 1.00 blocks/s crouched at healthy baseline attributes. This gives a balanced
0.90-second walk, 0.85-second sprint and 1.15-second crouch cycle without accelerating all the
bones. The shared
`RoleplayPaceAttributes` adds one transient multiplier after vitals' base-speed adjustment;
it neither overwrites that base nor removes other modifiers. Local prediction applies it
after input/sprint selection and refreshes cached movement speed before native physics.
Flight, water, swimming, climbing, passengers, spectators and dead players use native pace.

Layer priority:

1. Unsupported airborne, swimming, flying, climbing, mounted, sleeping, dead and spectator
   states use the existing native behavior.
2. Grounded idle, walking, running, crouching and turning use the authored clips instead of
   the previous procedural gait and idle sway.
3. Shared physiological breathing remains. Native item use, attacks and charged crossbow
   holds retain their arm poses. Carving and RP interaction poses retain their existing priority.

The first-person camera prepares the current native avatar state before placing the eyes.
It follows the animated neck horizontally, retaining the existing vertical eye height and
minimum forward clearance during a backward braking lean. Authored crouch does not receive
the old additional native-crouch lean offset. Camera placement and subsequent skin/armor
extraction share one immutable locomotion frame per exact input sample; repeated extraction
does not advance foot contacts. Existing camera-volume collision resolution still applies.

`verifyFirstPersonCameraAnchor` runs without starting the game. It checks 159,720 animated
view samples over all 22 clips, yaw/scale variants, native fallback, and duplicate-frame
sampling. Downward torso-ray fixtures reproduce 276 intersections with the old fixed camera
and none with the animated anchor. Startup/braking frame ordering is covered at 15/30/60/144 FPS.

Verification from `D:\23\fabric-server`, with Java 25:

```powershell
.\gradlew.bat :client:check :server:check --offline
.\gradlew.bat :client:runClientGameTest -PtabGameTest --offline
```

Client checks include `AnimationPlaybackTest`, `PlayerGroundingTest`, `LocomotionStopTest`,
`GroundedGaitTest`, `GaitTempoTest` and `DistalLimbRendererTest`. The gait regression reconstructs rendered
soles in world space over 189 direction/speed/yaw/FPS cases, turns, braking and scaled avatars.
Native tests exercise all clips and bones, wide/slim skin and actual
PlayerModel armor bakes, UV preservation, pooled model reset, both held item matrices, native
actions, independent observed avatars, first-person visibility and distant editor previews.
`GroundedLocomotionClientGameTest` also drives real native extraction with replicated tick
positions and intermediate render frames, checks planted sole drift through actual model
matrices, and verifies local/observed release, zero-speed stopping, restart and idle completion.
`RoleplayPacingClientGameTest` executes real `LocalPlayer.aiStep` movement and checks measured
walk, sprint and crouch speeds, first-tick switching, idempotence and symptom compatibility.
Server checks include the independent `RoleplayPacingTest` cadence and speed policy regression.
Screenshots and release verification are saved under `art/player_animations_v3`.
