# Player animations v3

> **For agentic workers:** Use superpowers:subagent-driven-development for the independent playback and validation work; integrate renderer changes in the current checkout.

**Goal:** Play all 22 supplied animations on the existing RP player, locally and for observed players, including first person, without replacing the mannequin geometry.

**Architecture:** Bundle the animation resource, sample numeric Catmull-Rom tracks in a pure Java library, and use one motion controller per entity. Capture immutable poses into AvatarRenderState. Retarget Bedrock rotations to the articulated native skeleton and deform the continuous torso around its pelvis/spine/chest chain. Preserve native action poses and RP work/interaction layers. Grounded authored locomotion supersedes the old procedural gait. Unsupported airborne, swimming, sleeping and mounted states fall back to native animation.

**Tech Stack:** Java 25, Fabric 26.1.2, native PlayerModel/ModelPart, JOML, Gson, standalone verification mains and Fabric client game tests.

**Shared playback API:** package ua.rp.chat.animation. AnimationPose.Vector(float x,float y,float z); AnimationPose.rotation(String), position(String) in source degrees/pixels; AnimationLibrary.load(InputStream); new LocomotionController(AnimationLibrary); sample(double seconds, Motion); Motion(double speed,double forward,double left,boolean sprinting,boolean crouching,boolean grounded,float bodyYaw); returned Frame.pose(), clip(), active(). Missing channels are zero. Sources contain 22 bones, 22 clips, 12 loops, 10 holds, no expressions.

- [x] Implement/parser tests: all clips, smooth interpolation, holds, sparse channel reset, loop seams.
- [x] Implement/controller tests: directional locomotion, proportional speed, start/stop, crouch idle hold, interruptions, stationary turns, frame independence.
- [x] Bundle original animation JSON; capture entity movement with separate per-player controllers and immutable frame poses.
- [x] Retarget all 22 bones, including spine, clavicles, hands, ankles and toes, retaining skin UVs and outer layers.
- [x] Keep held item transforms on the hand chain; keep first-person body and attachments on the same pose; retain native use/attack and special-state fallbacks.
- [x] Verify native client rendering for two independent avatars, standing/crouching/directions/sprint, first person, held tools, equipment and pose reset. Review the implementation and screenshots.
- [x] Run client/server checks; build and publish the verified client release through deployment README, retaining the running server when no server changes are required.

Design and execution are authorized by the user's explicit request to fully integrate the animations. Checkpoint 3a4d850b is the pre-animation baseline; runtime files stay outside this change.


Verified release: client 1.5.63, release 20261015-105353. Native client tests, client/server checks, bundled resource digest, all three local JAR copies and signed R2/GitHub downloads verified. Google Drive published; optional VPS mirror unavailable (SSH timeout). Evidence: art/player_animations_v3/release-receipt.json.

