package ua.rp.chat.animation;

import ua.rp.chat.client.animation.PlayerTorsoSkeleton;

public final class PlayerGroundingTest {
    public static void main(String[] args) throws Exception {
        try (var resource = PlayerGroundingTest.class.getResourceAsStream(
                "/assets/eclipseclient/animations/rp_character_v3.animation.json")) {
            AnimationLibrary library = AnimationLibrary.load(resource);
            for (String clip : library.clipNames()) {
                for (boolean crouching : new boolean[]{false, true}) {
                    float previousLift = Float.NaN;
                    for (int frame = 0; frame <= 100; frame++) {
                        AnimationPose pose = library.sample(clip, library.length(clip) * frame / 100);
                        PlayerTorsoSkeleton skeleton = new PlayerTorsoSkeleton(pose);
                        float before = skeleton.lowestFootPixels(pose);
                        skeleton.groundFootContacts(pose, crouching);
                        float floor = PlayerTorsoSkeleton.floorPixels(crouching);
                        require(skeleton.lowestFootPixels(pose) <= floor + .00001f, clip + " penetrates the floor");
                        require(Math.abs(skeleton.groundLiftPixels() - Math.max(0, before - floor)) < .00001f,
                                clip + " changes authored height while the foot is above the floor");
                        require(Float.isFinite(skeleton.neck.y), clip + " has nonfinite grounding");
                        if (frame < 100 && Float.isFinite(previousLift)) {
                            require(Math.abs(skeleton.groundLiftPixels() - previousLift) < 1.5f,
                                    clip + " introduces a discontinuous root correction");
                        }
                        previousLift = skeleton.groundLiftPixels();
                    }
                }
            }
        }
        System.out.println("PlayerGroundingTest: all 22 clips retain authored motion above the floor; crouch and soles never penetrate");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
