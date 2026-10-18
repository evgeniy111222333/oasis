import java.nio.file.Files;
import java.nio.file.Path;
import ua.rp.chat.animation.AnimationLibrary;
import ua.rp.chat.client.animation.PlayerTorsoSkeleton;

/** Read-only reconstruction of the screenshot's downward sprint view. */
public class FirstPersonSprintProbe {
    public static void main(String[] args) throws Exception {
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            var library = AnimationLibrary.load(input);
            for (String clip : new String[]{"idle", "walk_forward_loop", "sprint_start", "sprint_loop", "sprint_stop"}) {
                if (!library.clipNames().contains(clip)) continue;
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                for (int i = 0; i <= 200; i++) {
                    var pose = library.sample(clip, library.length(clip) * i / 200.0);
                    var skeleton = new PlayerTorsoSkeleton(pose);
                    skeleton.groundFootContacts(pose, false);
                    double forward = -skeleton.neck.z * .9375 / 16;
                    min = Math.min(min, forward);
                    max = Math.max(max, forward);
                }
                System.out.printf(java.util.Locale.ROOT,
                        "%s: animated neck forward %.4f..%.4f m; fixed downward camera forward 0.2500 m%n", clip, min, max);
                if (clip.equals("sprint_stop")) {
                    var pose = library.sample(clip, library.length(clip) * 55 / 120.0);
                    var s = new PlayerTorsoSkeleton(pose);
                    System.out.printf(java.util.Locale.ROOT,
                            "  stop frame 55/120: neck forward %.4f m; unbounded following camera %.4f m; stable minimum 0.2500 m%n",
                            -s.neck.z * .9375 / 16, .25 - s.neck.z * .9375 / 16);
                }
            }
        }
    }
}
