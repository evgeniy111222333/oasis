import com.google.gson.GsonBuilder;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.animation.AnimationLibrary;
import ua.rp.chat.animation.AnimationPose;
import ua.rp.chat.animation.LocomotionController;
import ua.rp.chat.client.animation.PlayerTorsoSkeleton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only production-code kinematics audit. It does not edit or publish the game. */
public class WalkSlipAudit {
    static final float SCALE = .9375f / 16;
    static final float[] X = {-2, 0, 2}, Y = {3.5f,4,4.5f,6}, Z = {-2,-1.5f,-1,-.5f,0,2};
    record Foot(Vector3f[] points, int lowest, Vector3f ankle) {}
    record Sample(double seconds, String clip, double phase, Foot left, Foot right, float lift) {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of("D:/23"), output = Path.of(args[0]);
        Files.createDirectories(output);
        AnimationLibrary library;
        try(var stream=Files.newInputStream(root.resolve("fabric-server/client-mod-gradle/src/main/resources/assets/eclipseclient/animations/rp_character_v3.animation.json"))) {
            library = AnimationLibrary.load(stream);
        }
        var summary = new LinkedHashMap<String,Object>();
        summary.put("measurement", "Production AnimationLibrary + LocomotionController + PlayerTorsoSkeleton; straight grounded walk; native AvatarRenderer scale .9375");
        summary.put("productionCodeModified", false);
        summary.put("loopLengthSeconds", library.length("walk_loop"));
        summary.put("referenceSpeedBlocksPerSecond", 4.3);
        summary.put("distancePerFullCycleBlocks", 4.3 * library.length("walk_loop"));
        summary.put("nominalStepsPerSecondAtReference", 2 / library.length("walk_loop"));
        List<Object> speeds = new ArrayList<>();
        for(double speed : new double[]{1,2,4.3,5.6}) speeds.add(audit(library, speed, output));
        summary.put("steadyWalking", speeds);
        List<Object> cadenceTests = new ArrayList<>();
        for(double multiplier : new double[]{2,3,4,4.8}) cadenceTests.add(audit(library,4.3,output,multiplier));
        summary.put("diagnosticOnlyCadenceExperiments",cadenceTests);
        summary.put("sourceSwingRangesAt1x", ranges(library));
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(summary);
        Files.writeString(output.resolve("walk-slip-report.json"), json + "\n");
        System.out.println(json);
    }

    static Map<String,Object> audit(AnimationLibrary library, double speed, Path output) throws Exception {
        return audit(library,speed,output,1);
    }

    static Map<String,Object> audit(AnimationLibrary library, double speed, Path output, double cadence) throws Exception {
        LocomotionController controller = new LocomotionController(library);
        var motion = new LocomotionController.Motion(speed,1,0,false,false,true,0);
        var clock = LocomotionController.class.getDeclaredField("clipTime"); clock.setAccessible(true);
        final double dt=1.0/240, warmup=3, duration=library.length("walk_loop")*4.3/speed*2/cadence;
        List<Double> slip = new ArrayList<>(), backward = new ArrayList<>(), gaps = new ArrayList<>();
        int phaseWraps=0, frames=0, airborne=0, fullyAirborne=0;
        Sample previous=null;
        StringBuilder csv = new StringBuilder("time_s,clip,clip_phase_s,body_world_z,left_contact_world_z,left_contact_gap_m,left_contact_velocity_m_s,right_contact_world_z,right_contact_gap_m,right_contact_velocity_m_s,root_lift_px\n");
        for(int frame=0;frame<=(warmup+duration)/dt;frame++) {
            double seconds=frame*dt;
            var result=controller.sample(seconds*cadence,motion);
            AnimationPose pose=result.pose();
            PlayerTorsoSkeleton skeleton = new PlayerTorsoSkeleton(pose);
            skeleton.groundFootContacts(pose,false);
            Sample current=new Sample(seconds,result.clip(),clock.getDouble(controller),
                    foot(pose,skeleton,"left"),foot(pose,skeleton,"right"),skeleton.groundLiftPixels());
            if(seconds>=warmup && previous!=null) {
                if(current.phase<previous.phase) phaseWraps++;
                frames++;
                double leftGap=gap(current.left), rightGap=gap(current.right);
                if(Math.min(leftGap,rightGap)>.015) airborne++;
                if(Math.min(leftGap,rightGap)>.05) fullyAirborne++;
                gaps.add(Math.min(leftGap,rightGap));
                double lVelocity=velocity(current.left,previous.left,speed,dt);
                double rVelocity=velocity(current.right,previous.right,speed,dt);
                for(Foot[] pair : List.of(new Foot[]{current.left,previous.left},new Foot[]{current.right,previous.right})) {
                    if(gap(pair[0])<=.015) {
                        Vector3f now=pair[0].points[pair[0].lowest], before=pair[1].points[pair[0].lowest];
                        double worldZ=speed-(now.z-before.z)*SCALE/dt;
                        double worldX=-(now.x-before.x)*SCALE/dt;
                        slip.add(Math.hypot(worldZ,worldX));
                        backward.add((now.z-before.z)*SCALE/dt);
                    }
                }
                csv.append(String.format(Locale.ROOT,"%.6f,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                        seconds,current.clip,current.phase,speed*seconds,
                        speed*seconds-current.left.points[current.left.lowest].z*SCALE,leftGap,lVelocity,
                        speed*seconds-current.right.points[current.right.lowest].z*SCALE,rightGap,rVelocity,current.lift));
            }
            previous=current;
        }
        Files.writeString(output.resolve("walk-speed-"+speed+(cadence==1?"":"-cadence-"+cadence)+".csv"),csv);
        var row=new LinkedHashMap<String,Object>();
        row.put("speedBlocksPerSecond",speed);
        row.put("cadenceMultiplier",cadence);
        row.put("cycleSeconds",library.length("walk_loop")*4.3/speed/cadence);
        row.put("distancePerCycleBlocks",4.3*library.length("walk_loop")/cadence);
        row.put("stepsPerSecond",2/(library.length("walk_loop")*4.3/speed/cadence));
        row.put("sampledFrames",frames);row.put("phaseWraps",phaseWraps);
        row.put("contactSamples",slip.size());
        row.put("medianContactWorldSpeed",percentile(slip,.5));
        row.put("p90ContactWorldSpeed",percentile(slip,.9));
        row.put("medianContactWorldSpeedFractionOfBody",percentile(slip,.5)/speed);
        row.put("medianFootBackwardSpeedRelativeBody",percentile(backward,.5));
        row.put("bothFeetAbove1_5cmFraction",(double)airborne/frames);
        row.put("bothFeetAbove5cmFraction",(double)fullyAirborne/frames);
        row.put("p90LowestFootGapMeters",percentile(gaps,.9));
        return row;
    }

    static double velocity(Foot now, Foot before,double speed,double dt) {
        Vector3f point=now.points[now.lowest], old=before.points[now.lowest];
        return speed-(point.z-old.z)*SCALE/dt;
    }

    static double gap(Foot foot) { return (PlayerTorsoSkeleton.floorPixels(false)-foot.points[foot.lowest].y)*SCALE; }

    static Map<String,Object> ranges(AnimationLibrary library) {
        var result=new LinkedHashMap<String,Object>();
        for(String side:List.of("left","right")) {
            float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
            for(int frame=0;frame<1200;frame++) {
                AnimationPose pose=library.sample("walk_loop",frame/1000.0);
                var s=new PlayerTorsoSkeleton(pose);s.groundFootContacts(pose,false);
                var foot=foot(pose,s,side);
                min=Math.min(min,foot.ankle.z);max=Math.max(max,foot.ankle.z);
            }
            result.put(side+"AnkleForwardBackwardRangeBlocks",(max-min)*SCALE);
        }
        return result;
    }

    static Foot foot(AnimationPose pose,PlayerTorsoSkeleton s,String side) {
        Quaternionf upper=new Quaternionf(s.pelvisRotation).mul(PlayerTorsoSkeleton.rotation(pose.rotation(side+"_leg")));
        Quaternionf calf=PlayerTorsoSkeleton.rotation(pose.rotation(side+"_calf"));
        Quaternionf ankle=PlayerTorsoSkeleton.rotation(pose.rotation(side+"_ankle"));
        Quaternionf toe=PlayerTorsoSkeleton.rotation(pose.rotation(side+"_toe"));
        Vector3f origin=PlayerTorsoSkeleton.offset(s.hip,s.pelvisRotation,side.equals("left")?2.2f:-2.2f,0,0);
        List<Vector3f> points=new ArrayList<>();
        Quaternionf weighted=new Quaternionf();
        for(float x:X) for(float y:Y) for(float z:Z) {
            float ankleWeight=smooth(y-3.5f),toeWeight=ankleWeight*smooth(-.5f-z);
            Vector3f p=new Vector3f(x,y-5,z+1);
            weighted.identity().slerp(toe,toeWeight).transform(p);p.add(0,1,-1);
            weighted.identity().slerp(ankle,ankleWeight).transform(p);p.add(0,4,0);
            calf.transform(p).add(0,6,0);upper.transform(p).add(origin);points.add(p);
        }
        int lowest=0;for(int i=1;i<points.size();i++) if(points.get(i).y>points.get(lowest).y)lowest=i;
        Vector3f pivot=new Vector3f(0,4,0);calf.transform(pivot).add(0,6,0);upper.transform(pivot).add(origin);
        return new Foot(points.toArray(Vector3f[]::new),lowest,pivot);
    }

    static float smooth(float f) {float t=Math.max(0,Math.min(1,f));return t*t*(3-2*t);}
    static double percentile(List<Double> values,double fraction) {
        if(values.isEmpty())return Double.NaN;
        List<Double> sorted=new ArrayList<>(values);sorted.sort(Double::compare);return sorted.get((int)((sorted.size()-1)*fraction));
    }
}
