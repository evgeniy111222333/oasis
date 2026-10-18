package ua.rp.chat.client.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.animation.AnimationPose;
import ua.rp.chat.animation.LocomotionController;
import ua.rp.chat.locomotion.RoleplayPacing;

/** Per-avatar world contacts. No physics, velocities or entity positions are changed. */
public final class GroundedGait {
    @FunctionalInterface public interface Ground {
        /** Return a collision surface near referenceY, or NaN when there is no ground. */
        double height(double x, double z, double referenceY);
    }
    private record Point(double x, double y, double z) {
        Point lerp(Point to, double t) { return new Point(x + (to.x-x)*t, y + (to.y-y)*t, z + (to.z-z)*t); }
    }
    private static final class Foot {
        Point anchor, from, landing, target;
        double plantedYaw, plantedDirX, plantedDirZ, finishStart;
        long cycle;
        boolean planted, finishing;
    }
    private final Foot[] feet = {new Foot(), new Foot()};
    private boolean initialized, stopping, wasMoving;
    private double previousSeconds, oldX, oldY, oldZ, cycles, footCycles, weight;
    private double x, y, z, yaw, scale, stride, footStride, stance, strafe, vx, vz;
    private Ground ground;

    public boolean planted(int side) { return initialized && feet[side].target != null && feet[side].planted; }
    public record Contact(double x, double y, double z, boolean planted) {}
    public Contact contact(int side) {
        Point p=feet[side].target;
        return p==null ? null : new Contact(p.x,p.y,p.z,feet[side].planted);
    }

    public LocomotionController.Frame sample(LocomotionController controller, double seconds,
            LocomotionController.Motion motion, double x, double y, double z, double entityScale, Ground ground) {
        this.ground=ground;
        try {
            return sampleFrame(controller,seconds,motion,x,y,z,entityScale);
        } finally {
            // Runtime surface callbacks capture their player; retaining one would defeat the weak-key cache.
            this.ground=null;
        }
    }

    private LocomotionController.Frame sampleFrame(LocomotionController controller, double seconds,
            LocomotionController.Motion motion, double x, double y, double z, double entityScale) {
        this.x=x; this.y=y; this.z=z; this.yaw=motion.bodyYaw();
        this.scale=.9375 / 16 * entityScale;
        stride=(motion.crouching() ? RoleplayPacing.CROUCH_STRIDE_METERS
                : motion.sprinting() ? RoleplayPacing.SPRINT_STRIDE_METERS : RoleplayPacing.WALK_STRIDE_METERS)*entityScale;
        double dirNorm=Math.hypot(motion.forward(),motion.left());
        strafe=dirNorm>1e-4 ? Math.abs(motion.left())/dirNorm : 0;
        footStride=stride*(1-.38*strafe);
        stance=motion.crouching() ? .46 : motion.sprinting() ? .26 : .32;
        double dt=initialized ? Math.max(0, seconds-previousSeconds) : 0;
        double distance=initialized ? Math.hypot(x-oldX,z-oldZ) : 0;
        if (!motion.grounded() || seconds < previousSeconds || dt > .25 || distance > 1.5*entityScale
                || initialized && Math.abs(y-oldY) > .45*entityScale) {
            reset();
            dt=distance=0;
        }
        if (initialized) {
            cycles+=distance/stride;
            footCycles+=distance/footStride;
        } else {
            cycles=footCycles=0;
        }
        previousSeconds=seconds; oldX=x; oldY=y; oldZ=z;
        var frame=controller.sample(seconds,motion,cycles);
        if (!frame.active()) return frame;
        double radians=Math.toRadians(yaw);
        vx=(-Math.sin(radians)*motion.forward()+Math.cos(radians)*motion.left())*motion.speed();
        vz=(Math.cos(radians)*motion.forward()+Math.sin(radians)*motion.left())*motion.speed();
        boolean moving=frame.clip().contains("loop") || frame.clip().contains("start")
                || frame.clip().equals("sneak_enter") && frame.phase()<1 && motion.speed()>.05
                    && motion.moveIntent()!=LocomotionController.MoveIntent.STOPPED;
        boolean stop=frame.clip().contains("stop");
        boolean leavingGait=wasMoving && !moving;
        boolean finishing=feet[0].finishing || feet[1].finishing;
        wasMoving=moving;
        // Idle and authored standing turns retain their original poses once the final step has landed.
        double desiredWeight=(moving || stop || leavingGait || finishing) ? 1 : 0;
        weight=Math.max(0,Math.min(1,weight+Math.signum(desiredWeight-weight)*dt/.12));
        if (!initialized) {
            initialize(frame.pose(),motion.crouching());
            initialized=true;
        }
        if (weight <= 0 && !moving && !stop) { resetFeet(); stopping=false; return frame; }
        if (moving && stopping) {
            resetFeet();
            initialize(frame.pose(),motion.crouching());
            stopping=false;
        }
        if ((stop || leavingGait) && !stopping) {
            stopping=true;
            for (int side=0;side<2;side++) {
                Foot foot=feet[side];
                if (!foot.planted) finishStep(foot,side,seconds,motion.crouching());
            }
        }
        for (int side=0;side<2;side++) {
            Foot foot=feet[side];
            if (foot.target==null) initialize(frame.pose(),motion.crouching());
            if (stopping || !moving) {
                if (foot.planted) {
                    double dx=foot.anchor.x-x,dz=foot.anchor.z-z,velocity=Math.hypot(vx,vz);
                    double lat=dx*Math.cos(radians)+dz*Math.sin(radians);
                    // Residual inertia or a sharp body turn can carry a support past reach or across the midline.
                    if (dx*vx+dz*vz < -.30*entityScale*velocity || Math.hypot(dx,dz)>.44*entityScale
                            || (side==0 ? lat : -lat)<0 || Math.abs(wrappedYaw(yaw-foot.plantedYaw))>45)
                        finishStep(foot,side,seconds,motion.crouching());
                }
                if (foot.finishing) {
                    foot.landing=placement(side,.065,motion.crouching());
                    double t=Math.min(1,(seconds-foot.finishStart)/.18);
                    Point p=foot.from.lerp(foot.landing,smooth(t));
                    foot.target=new Point(p.x,p.y+Math.sin(Math.PI*t)*Math.sin(Math.PI*t)*.05*entityScale,p.z);
                    if (t>=1) { foot.anchor=foot.target; foot.planted=true; foot.finishing=false; foot.plantedYaw=yaw; }
                }
            } else step(foot,side,motion,entityScale);
        }
        AnimationPose fitted=fit(frame.pose(),motion.crouching());
        return new LocomotionController.Frame(AnimationPose.blend(frame.pose(),fitted,smooth(weight)),
                frame.clip(),true,frame.phase());
    }

    private void reset() { initialized=false; weight=0; stopping=false; wasMoving=false; cycles=footCycles=0; resetFeet(); }
    private void resetFeet() {
        for (Foot foot:feet) { foot.anchor=foot.from=foot.landing=foot.target=null; foot.finishing=false; }
    }
    private void finishStep(Foot foot,int side,double seconds,boolean crouching) {
        foot.from=foot.target;
        foot.landing=placement(side,.065,crouching);
        foot.finishStart=seconds;
        foot.finishing=true;
        foot.planted=false;
    }
    private void initialize(AnimationPose pose, boolean crouching) {
        PlayerTorsoSkeleton skeleton=new PlayerTorsoSkeleton(pose);
        skeleton.groundFootContacts(pose,crouching);
        double speed=Math.max(.05,Math.hypot(vx,vz));
        double dirX=vx/speed, dirZ=vz/speed;
        for (int side=0;side<2;side++) {
            Foot foot=feet[side];
            if (foot.target!=null) continue;
            double phase=footCycles+(side==0 ? 0 : .5);
            foot.cycle=(long)Math.floor(phase);
            double local=phase-Math.floor(phase);
            foot.planted=local<stance;
            foot.plantedYaw=yaw;
            foot.plantedDirX=dirX;
            foot.plantedDirZ=dirZ;
            if (foot.planted) {
                foot.anchor=placement(side,(stance*.5-local)*footStride/speed,crouching);
                foot.target=foot.anchor;
            } else {
                foot.from=placement(side,-(local-stance*.5)*footStride/speed,crouching);
                foot.landing=placement(side,(1-local+stance*.5)*footStride/speed,crouching);
                foot.target=world(sole(pose,skeleton,side),crouching);
            }
        }
    }

    private void step(Foot foot,int side,LocomotionController.Motion motion,double entityScale) {
        double phase=footCycles+(side==0 ? 0 : .5);
        long cycle=(long)Math.floor(phase);
        double local=phase-Math.floor(phase);
        double speed=Math.max(.05,Math.hypot(vx,vz));
        double dirX=vx/speed, dirZ=vz/speed;
        if (local<stance) {
            if (!foot.planted || cycle!=foot.cycle || staleAnchor(foot,side,dirX,dirZ,entityScale)) {
                foot.anchor=placement(side,(stance*.5-local)*footStride/speed,motion.crouching());
                foot.plantedYaw=yaw;
                foot.plantedDirX=dirX;
                foot.plantedDirZ=dirZ;
            }
            foot.target=foot.anchor;
            foot.planted=true;
        } else {
            Point idealFrom=placement(side,-(local-stance*.5)*footStride/speed,motion.crouching());
            if (foot.planted || cycle!=foot.cycle || foot.from==null) {
                foot.from=foot.target!=null ? foot.target : idealFrom;
                foot.plantedDirX=dirX;
                foot.plantedDirZ=dirZ;
            }
            if (staleSwingOrigin(foot,side,idealFrom,dirX,dirZ,entityScale)) {
                foot.from=idealFrom;
                foot.plantedDirX=dirX;
                foot.plantedDirZ=dirZ;
            }
            foot.planted=false;
            // Constant velocity predicts a fixed landing point; steering changes it smoothly during swing.
            foot.landing=placement(side,(1-local+stance*.5)*footStride/speed,motion.crouching());
            double t=(local-stance)/(1-stance);
            Point p=foot.from.lerp(foot.landing,smooth(t));
            double height=(motion.crouching() ? .045 : motion.sprinting() ? .28 : .085)*entityScale;
            foot.target=new Point(p.x,p.y+height*Math.pow(Math.sin(Math.PI*t),2),p.z);
            foot.plantedYaw=yaw;
        }
        foot.cycle=cycle;
    }

    private boolean staleAnchor(Foot foot,int side,double dirX,double dirZ,double entityScale) {
        if (foot.anchor==null) return true;
        double radians=Math.toRadians(yaw);
        double dx=foot.anchor.x-x, dz=foot.anchor.z-z;
        double lat=dx*Math.cos(radians)+dz*Math.sin(radians);
        double fwd=-dx*Math.sin(radians)+dz*Math.cos(radians);
        double hipLat=(side==0 ? 2.2 : -2.2)*scale;
        double hipDist=Math.hypot(lat-hipLat,fwd);
        double dirDot=dirX*foot.plantedDirX+dirZ*foot.plantedDirZ;
        return hipDist>.43*entityScale
                || (side==0 ? lat : -lat)<0
                || Math.abs(wrappedYaw(yaw-foot.plantedYaw))>45
                || dirDot<.25;
    }

    private boolean staleSwingOrigin(Foot foot,int side,Point idealFrom,double dirX,double dirZ,double entityScale) {
        double radians=Math.toRadians(yaw);
        double dx=foot.from.x-x, dz=foot.from.z-z;
        double lat=dx*Math.cos(radians)+dz*Math.sin(radians);
        double dirDot=dirX*foot.plantedDirX+dirZ*foot.plantedDirZ;
        double driftFromIdeal=Math.hypot(foot.from.x-idealFrom.x,foot.from.z-idealFrom.z);
        return (side==0 ? lat : -lat)<0
                || dirDot<.25
                || driftFromIdeal>.45*entityScale;
    }

    private Point placement(int side,double aheadSeconds,boolean crouching) {
        double radians=Math.toRadians(yaw);
        double inward=.5*stance*footStride*strafe;
        double lateralMag=Math.max(2.2*scale,inward+.45*scale);
        double lateral=side==0 ? lateralMag : -lateralMag;
        double px=x+Math.cos(radians)*lateral+vx*aheadSeconds;
        double pz=z+Math.sin(radians)*lateral+vz*aheadSeconds;
        double floor=ground==null ? y : ground.height(px,pz,y);
        // The native rig grounds against the player's collision elevation. A neighbor's lower
        // level can be beyond a 12px leg's reach while the entity still stands on the upper edge.
        // Keep that native support plane instead of pulling the entire avatar toward a false anchor.
        if (!Double.isFinite(floor) || Math.abs(floor-y)>.025*scale/(.9375/16)) floor=y;
        return new Point(px,floor,pz);
    }

    private AnimationPose fit(AnimationPose source,boolean crouching) {
        PlayerTorsoSkeleton skeleton=new PlayerTorsoSkeleton(source);
        skeleton.groundFootContacts(source,crouching);
        Vector3f[] ankles=new Vector3f[2];
        float hipY=skeleton.hip.y;
        for (int side=0;side<2;side++) {
            ankles[side]=nativePoint(feet[side].target,crouching).add(0,-2,0);
            Vector3f offset=skeleton.pelvisRotation.transform(new Vector3f(side==0 ? 2.2f : -2.2f,0,0));
            double dx=ankles[side].x-skeleton.hip.x-offset.x, dz=ankles[side].z-skeleton.hip.z-offset.z;
            if (!feet[side].planted) {
                // A long swing must follow the moving hips between takeoff and landing. Bound its
                // horizontal excursion, increasingly so while raised, and never cross the midline.
                float minSideX=.6f;
                if (side==0 && ankles[side].x<skeleton.hip.x+minSideX) {
                    ankles[side].x=skeleton.hip.x+minSideX;
                    dx=ankles[side].x-skeleton.hip.x-offset.x;
                } else if (side==1 && ankles[side].x>skeleton.hip.x-minSideX) {
                    ankles[side].x=skeleton.hip.x-minSideX;
                    dx=ankles[side].x-skeleton.hip.x-offset.x;
                }
                double size=scale/(.9375/16),lift=Math.max(0,feet[side].target.y-y);
                double radius=(.44-.32*smooth(lift/(.10*size)))*size/scale;
                double horizontal=Math.hypot(dx,dz);
                if (horizontal>radius) {
                    dx*=radius/horizontal; dz*=radius/horizontal;
                    ankles[side].x=(float)(skeleton.hip.x+offset.x+dx);
                    ankles[side].z=(float)(skeleton.hip.z+offset.z+dz);
                }
                feet[side].target=world(new Vector3f(ankles[side]).add(0,2,0),crouching);
            }
            double vertical=Math.sqrt(Math.max(1,9.95*9.95-dx*dx-dz*dz));
            hipY=Math.max(hipY,(float)(ankles[side].y-offset.y-vertical));
        }
        hipY=Math.min(PlayerTorsoSkeleton.floorPixels(crouching)-5.5f,hipY);
        var editor=source.edit();
        var com=source.position("center_of_mass");
        editor.position("center_of_mass",com.x(),12-hipY,com.z());
        for (int side=0;side<2;side++) {
            String name=side==0 ? "left" : "right";
            Vector3f origin=PlayerTorsoSkeleton.offset(new Vector3f(skeleton.hip.x,hipY,skeleton.hip.z),
                    skeleton.pelvisRotation,side==0 ? 2.2f : -2.2f,0,0);
            Vector3f d=new Vector3f(ankles[side]).sub(origin);
            new Quaternionf(skeleton.pelvisRotation).conjugate().transform(d);
            if (!feet[side].planted) {
                float distance=d.length();
                if (distance<.0001f) d.set(0,2.001f,0);
                else d.mul(Math.max(2.001f,Math.min(9.95f,distance))/distance);
                Vector3f reachable=skeleton.pelvisRotation.transform(new Vector3f(d)).add(origin).add(0,2,0);
                feet[side].target=world(reachable,crouching);
            }
            double length=Math.max(2.001,Math.min(9.999,d.length()));
            double knee=Math.acos(Math.max(-1,Math.min(1,(length*length-36-16)/48)));
            double beta=Math.atan2(4*Math.sin(knee),6+4*Math.cos(knee));
            Vector3f u=new Vector3f(d).normalize();
            Quaternionf align=new Quaternionf().rotationTo(new Vector3f(0,1,0),u);
            double footYawDegrees=Math.max(-25,Math.min(25,wrappedYaw(feet[side].plantedYaw-yaw)))+(side==0 ? -6 : 6);
            double footYawRadians=Math.toRadians(footYawDegrees);
            Vector3f hinge=new Vector3f((float)Math.cos(footYawRadians),0,(float)-Math.sin(footYawRadians));
            new Quaternionf(skeleton.pelvisRotation).conjugate().transform(hinge);
            Vector3f xAxis=align.transform(new Vector3f(1,0,0));
            Vector3f zAxis=align.transform(new Vector3f(0,0,1));
            float kneeYaw=(float)Math.atan2(-hinge.dot(zAxis),hinge.dot(xAxis));
            Quaternionf upper=align.rotateY(kneeYaw).rotateX((float)-beta);
            Quaternionf calf=new Quaternionf().rotationX((float)knee);
            double ankleYawDegrees=Math.max(-35,Math.min(35,wrappedYaw(feet[side].plantedYaw-yaw)));
            Quaternionf desiredFoot=new Quaternionf().rotationY((float)Math.toRadians(ankleYawDegrees));
            Quaternionf ankle=new Quaternionf(skeleton.pelvisRotation).mul(upper).mul(calf).conjugate().mul(desiredFoot);
            rotation(editor,name+"_leg",upper);
            editor.rotation(name+"_calf",(float)Math.toDegrees(knee),0,0);
            rotation(editor,name+"_ankle",ankle);
            editor.rotation(name+"_toe",0,0,0);
        }
        return editor.build();
    }
    private static double wrappedYaw(double angle) {
        angle%=360;
        if (angle>180) angle-=360;
        if (angle<-180) angle+=360;
        return angle;
    }
    private static void rotation(AnimationPose.Editor editor,String bone,Quaternionf q) {
        Vector3f r=q.getEulerAnglesZYX(new Vector3f());
        editor.rotation(bone,(float)Math.toDegrees(r.x),(float)Math.toDegrees(r.y),(float)Math.toDegrees(r.z));
    }
    private Point world(Vector3f p,boolean crouching) {
        double angle=Math.toRadians(yaw),c=Math.cos(angle),s=Math.sin(angle);
        return new Point(x+scale*(p.x*c+p.z*s),y+scale*(PlayerTorsoSkeleton.floorPixels(crouching)-p.y),
                z+scale*(p.x*s-p.z*c));
    }
    private Vector3f nativePoint(Point p,boolean crouching) {
        double angle=Math.toRadians(yaw),c=Math.cos(angle),s=Math.sin(angle),dx=(p.x-x)/scale,dz=(p.z-z)/scale;
        return new Vector3f((float)(dx*c+dz*s),(float)(PlayerTorsoSkeleton.floorPixels(crouching)-(p.y-y)/scale),
                (float)(dx*s-dz*c));
    }
    private static Vector3f sole(AnimationPose pose,PlayerTorsoSkeleton skeleton,int side) {
        String name=side==0 ? "left" : "right";
        Vector3f p=new Vector3f(0,2,0);
        PlayerTorsoSkeleton.rotation(pose.rotation(name+"_ankle")).transform(p).add(0,4,0);
        PlayerTorsoSkeleton.rotation(pose.rotation(name+"_calf")).transform(p).add(0,6,0);
        new Quaternionf(skeleton.pelvisRotation).mul(PlayerTorsoSkeleton.rotation(pose.rotation(name+"_leg"))).transform(p);
        return p.add(PlayerTorsoSkeleton.offset(skeleton.hip,skeleton.pelvisRotation,side==0 ? 2.2f : -2.2f,0,0));
    }
    private static double smooth(double t) { t=Math.max(0,Math.min(1,t)); return t*t*(3-2*t); }
}
