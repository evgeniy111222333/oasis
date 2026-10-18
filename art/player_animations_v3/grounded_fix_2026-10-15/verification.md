# Grounded walking and responsive stopping — client 1.5.64

The supplied animations remain the upper-body source. Grounded legs now use alternating
world-space support anchors, swing trajectories and a two-bone solve fitted to the native
12-pixel legs. Moving-loop phase follows traveled distance; one-shots use real time.
Movement physics, attributes, server protocol and the running server are unchanged.

The initial regression measured the rendered sole sliding at a median **2.5013 blocks/s**
with the prior pipeline. The new directional test covers **189** combinations of FPS,
yaw, speed and direction, with maximum planted horizontal drift **0.0000689 blocks/s**.
It additionally covers turns, scaling, coordinates near 3 million, resets, crouch release,
braking at several release phases, and the native support plane beside a lower ledge.

The actual Minecraft client test reconstructs soles from native model matrices across
nine gaits on wide/slim models: **2,814** measured ground-contact pairs, worst per-gait
90th-percentile world sole speed **0.000021378 blocks/s**. Skin and armor share the same
skeleton. Local key release begins stopping before the first friction tick. Observed
players begin stopping after two distinct deceleration ticks. Stop clocks advance even
at zero displacement, restart interrupts a stop, and stationary playback reaches idle.

Additional regressions reproduced and fixed during review:

- A stopped crouching foot could remain suspended when `sneak_enter` held its last pose.
- Residual inertia could carry a planted stop contact beyond leg reach and drag it.
- A neighboring lower collision surface could conflict with the native floor correction.
- A replicated speed reduction could latch an inferred stop indefinitely after speed stabilized.

`native-client.log` contains the final successful `:client:check :client:runClientGameTest`
run. `screenshots/` preserves all 25 native client regression captures. Publication checks,
the exact signed release, downloaded JAR hashes and mirror verification are recorded in
`publish_update.log` and `release-receipt.json` after publication completes.

Ground contact fitting follows the player's native collision elevation; it does not move
the entity or stretch a leg toward an unreachable neighboring ledge.
