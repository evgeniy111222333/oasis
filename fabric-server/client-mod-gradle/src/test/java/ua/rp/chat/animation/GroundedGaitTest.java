package ua.rp.chat.animation;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.rp.chat.client.animation.PlayerTorsoSkeleton;
import ua.rp.chat.client.animation.GroundedGait;

/** Measures world-space skin soles, rather than trusting animation clocks or requested IK targets. */
public final class GroundedGaitTest {
    private static final double SCALE = .9375 / 16;
    public static void main(String[] args) throws Exception {
        AnimationLibrary library;
        try (var input = GroundedGaitTest.class.getResourceAsStream(
                "/assets/eclipseclient/animations/rp_character_v3.animation.json")) {
            library = AnimationLibrary.load(input);
        }
        int cases=0;
        double worst=0;
        for (int fps:new int[]{30,60,144}) for (float yaw:new float[]{0,37,-140}) {
            for (double[] direction:new double[][]{{1,0},{-1,0},{0,1},{0,-1},{Math.sqrt(.5),Math.sqrt(.5)}}) {
                for (double speed:new double[]{.3,2,4.3}) {
                    worst=Math.max(worst,steady(library,fps,yaw,speed,direction[0],direction[1],false,false,1,0));
                    cases++;
                }
                worst=Math.max(worst,steady(library,fps,yaw,1.3,direction[0],direction[1],false,true,1,0)); cases++;
            }
            worst=Math.max(worst,steady(library,fps,yaw,5.6,1,0,true,false,1,0)); cases++;
            turning(library,fps);
            braking(library,fps);
        }
        // Large world coordinates retain double precision; avatar scaling changes stride and reach together.
        steady(library,144,120,2.15,1,0,false,false,.5,3_000_000);
        steady(library,60,-33,6,1,0,false,false,1.5,-3_000_000);
        lifecycle(library);
        crouchRelease(library);
        stopContacts(library);
        unevenEdge(library);
        strafeAndReversalsNeverCrossOrStretch(library);
        System.out.printf(java.util.Locale.ROOT,
                "GroundedGaitTest: %d directional/FPS cases, planted horizontal drift <= %.7f blocks/s; turns, braking, scaling and resets passed%n",cases,worst);
    }

    private record Point(double x,double y,double z) {
        double horizontal(Point p) { return Math.hypot(x-p.x,z-p.z); }
    }
    private static double steady(AnimationLibrary library,int fps,float yaw,double speed,double forward,double left,
                                  boolean sprint,boolean crouch,double size,double origin) {
        var controller=new LocomotionController(library);
        var gait=new GroundedGait();
        var motion=new LocomotionController.Motion(speed,forward,left,sprint,crouch,true,yaw);
        double angle=Math.toRadians(yaw),vx=(-Math.sin(angle)*forward+Math.cos(angle)*left)*speed,
                vz=(Math.cos(angle)*forward+Math.sin(angle)*left)*speed;
        Point[] previous=new Point[2];
        GroundedGait.Contact[] oldTargets=new GroundedGait.Contact[2];
        int contacts=0; double worst=0;
        for(int i=0;i<fps*4;i++) {
            double t=i/(double)fps,x=origin+vx*t,z=origin+vz*t;
            var frame=gait.sample(controller,t,motion,x,64,z,size,null);
            var skeleton=new PlayerTorsoSkeleton(frame.pose()); skeleton.groundFootContacts(frame.pose(),crouch);
            for(int side=0;side<2;side++) {
                Point p=world(sole(frame.pose(),skeleton,side==0?"left":"right"),x,64,z,yaw,size,crouch);
                var target=gait.contact(side);
                if(i>fps && target!=null && target.planted()) {
                    require(Math.hypot(p.x-target.x(),p.z-target.z())<.003*size,
                            "IK misses planted target: yaw="+yaw+" forward="+forward+" left="+left);
                    require(p.y>=63.999 && p.y<64.05*size+64*(1-size),"Planted sole must stay on the ground");
                    if(oldTargets[side]!=null && oldTargets[side].planted()
                            && Math.hypot(target.x()-oldTargets[side].x(),target.z()-oldTargets[side].z())<1e-8) {
                        double drift=p.horizontal(previous[side])*fps;
                        worst=Math.max(worst,drift); contacts++;
                        require(drift<.025*size,"Planted sole slides at "+drift+" yaw="+yaw+" speed="+speed);
                    }
                }
                previous[side]=p; oldTargets[side]=target;
            }
        }
        require(contacts>(sprint ? .5 : 1)*fps,"A gait must produce real measured ground contacts");
        return worst;
    }

    private static void turning(AnimationLibrary library,int fps) {
        var controller=new LocomotionController(library); var gait=new GroundedGait();
        Point[] previous=new Point[2]; GroundedGait.Contact[] before=new GroundedGait.Contact[2];
        double x=0,z=0;
        for(int i=0;i<fps*5;i++) {
            double t=i/(double)fps; float yaw=(float)(t*45); double angle=Math.toRadians(yaw);
            x-=Math.sin(angle)*2/fps; z+=Math.cos(angle)*2/fps;
            var motion=new LocomotionController.Motion(2,1,0,false,false,true,yaw);
            var frame=gait.sample(controller,t,motion,x,0,z,1,null);
            var skeleton=new PlayerTorsoSkeleton(frame.pose()); skeleton.groundFootContacts(frame.pose(),false);
            for(int side=0;side<2;side++) {
                Point p=world(sole(frame.pose(),skeleton,side==0?"left":"right"),x,0,z,yaw,1,false);
                var contact=gait.contact(side);
                if(i>fps && contact.planted() && before[side]!=null && before[side].planted()
                        && Math.hypot(contact.x()-before[side].x(),contact.z()-before[side].z())<1e-8)
                    require(p.horizontal(previous[side])*fps<.03,"Turning must keep the supporting foot planted");
                previous[side]=p; before[side]=contact;
            }
        }
    }

    private static void braking(AnimationLibrary library,int fps) {
        for(boolean sprint:new boolean[]{false,true}) {
            var controller=new LocomotionController(library); var gait=new GroundedGait();
            double z=0; String stop=sprint?"sprint_stop":"walk_stop";
            AnimationPose first=null,late=null; double speed=sprint?5.6:4.3;
            for(int i=0;i<fps*4;i++) {
                double t=i/(double)fps;
                double v=t<2?speed:speed*Math.pow(.4,Math.floor((t-2)*20)+1);
                z+=v/fps;
                var intent=t<2?LocomotionController.MoveIntent.MOVING:LocomotionController.MoveIntent.STOPPED;
                var frame=gait.sample(controller,t,new LocomotionController.Motion(v,1,0,sprint,false,true,0,intent),0,0,z,1,null);
                if(i==fps*2) { require(frame.clip().equals(stop),"Release must immediately start "+stop); first=frame.pose(); }
                if(i==fps*2+fps/5) late=frame.pose();
                if(t>2.3 && t<2.45) {
                    require(gait.planted(0)&&gait.planted(1),"The final swing must land even when body speed is zero");
                }
                require(Float.isFinite(frame.pose().rotation("left_leg").x()),"Braking IK must remain finite");
            }
            require(!first.rotation("chest").equals(late.rotation("chest")),"The actual stopping body must animate");
        }
    }

    private static void lifecycle(AnimationLibrary library) {
        var controller=new LocomotionController(library); var gait=new GroundedGait();
        var walk=new LocomotionController.Motion(4.3,1,0,false,false,true,0);
        for(int i=0;i<30;i++) gait.sample(controller,i*.05,walk,0,0,i*.215,1,null);
        var airborne=new LocomotionController.Motion(4.3,1,0,false,false,false,0);
        require(!gait.sample(controller,1.5,airborne,0,.5,6.5,1,null).active(),"Airborne must relinquish the rig");
        require(!gait.planted(0)&&!gait.planted(1),"Airborne must clear both world anchors");
        var landed=gait.sample(controller,1.55,walk,0,0,6.6,1,null);
        var before=landed.pose().rotation("left_leg");
        gait.sample(controller,.1,walk,4096,64,-4096,1,null);
        require(before.equals(landed.pose().rotation("left_leg")),"Captured frames must stay immutable after reset/teleport");
    }

    private static void crouchRelease(AnimationLibrary library) {
        for(int fps:new int[]{30,60,144}) for(double release:new double[]{.83,1,1.17,1.31}) {
            var controller=new LocomotionController(library); var gait=new GroundedGait();
            boolean landed=false;
            for(int i=0;i<fps*3;i++) {
                double t=i/(double)fps;
                boolean moving=t<release;
                var intent=moving?LocomotionController.MoveIntent.MOVING:LocomotionController.MoveIntent.STOPPED;
                var motion=new LocomotionController.Motion(moving?1.3:0,1,0,false,true,true,0,intent);
                var frame=gait.sample(controller,t,motion,0,0,Math.min(t,release)*1.3,1,null);
                if(t>=release+.18 && t<release+.22+2.0/fps) landed|=gait.planted(0)&&gait.planted(1);
                if(t>release+.5) require(frame.clip().equals("sneak_enter")&&frame.phase()==1,
                        "Crouch release must retain the authored stationary hold");
            }
            require(landed,"Crouch release must finish the last swing instead of freezing a raised foot: fps="+fps+" release="+release);
        }
    }

    private static void stopContacts(AnimationLibrary library) {
        for(int fps:new int[]{30,60,144}) for(boolean sprint:new boolean[]{false,true})
                for(double release:new double[]{1,1.4,1.4667,1.65}) {
            var controller=new LocomotionController(library); var gait=new GroundedGait();
            double z=0,base=sprint?5.6:4.3;
            for(int i=0;i<fps*3;i++) {
                double t=i/(double)fps;
                double speed=t<release?base:base*Math.pow(.546,1+Math.floor((t-release)*20));
                z+=speed/fps;
                var intent=t<release?LocomotionController.MoveIntent.MOVING:LocomotionController.MoveIntent.STOPPED;
                var frame=gait.sample(controller,t,new LocomotionController.Motion(speed,1,0,sprint,false,true,0,intent),0,0,z,1,null);
                if(t>=release && frame.clip().contains("stop")) {
                    var skeleton=new PlayerTorsoSkeleton(frame.pose()); skeleton.groundFootContacts(frame.pose(),false);
                    for(int side=0;side<2;side++) {
                        var contact=gait.contact(side);
                        if(contact!=null&&contact.planted()) {
                            Point actual=world(sole(frame.pose(),skeleton,side==0?"left":"right"),0,0,z,0,1,false);
                            require(Math.hypot(actual.x-contact.x(),actual.z-contact.z())<.003,
                                    "Stopping must release an unreachable foot, not drag it: sprint="+sprint+" release="+release+" t="+t);
                        }
                    }
                }
            }
        }
    }

    private static void unevenEdge(AnimationLibrary library) {
        var controller=new LocomotionController(library); var gait=new GroundedGait();
        var walk=new LocomotionController.Motion(2,1,0,false,false,true,0);
        for(int i=0;i<180;i++) {
            double t=i/60.0;
            var frame=gait.sample(controller,t,walk,0,0,2*t,1,(x,z,y)->x<0?-.5:0);
            if(i>60) {
                var s=new PlayerTorsoSkeleton(frame.pose()); s.groundFootContacts(frame.pose(),false);
                for(int side=0;side<2;side++) if(gait.planted(side)) {
                    var contact=gait.contact(side);
                    Point p=world(sole(frame.pose(),s,side==0?"left":"right"),0,0,2*t,0,1,false);
                    require(Math.abs(p.y-contact.y())<.03,"Neighboring lower blocks must not lift the entire avatar");
                }
            }
        }
    }

    private static void strafeAndReversalsNeverCrossOrStretch(AnimationLibrary library) {
        var controller=new LocomotionController(library); var gait=new GroundedGait();
        double x=0,z=0; float yaw=0;
        double[][] sequence={{0,1},{0,-1},{-1,0},{0,1},{0,-1},{1,0},{-1,0}};
        int fps=60;
        for(int i=0;i<fps*7;i++) {
            double t=i/(double)fps;
            int segment=(i/(fps/2))%sequence.length;
            double forward=sequence[segment][0], left=sequence[segment][1];
            if(i==fps*3 || i==fps*5) yaw+=115;
            double angle=Math.toRadians(yaw), speed=2.5;
            x+=(-Math.sin(angle)*forward+Math.cos(angle)*left)*speed/fps;
            z+=(Math.cos(angle)*forward+Math.sin(angle)*left)*speed/fps;
            var motion=new LocomotionController.Motion(speed,forward,left,false,false,true,yaw,LocomotionController.MoveIntent.MOVING);
            var frame=gait.sample(controller,t,motion,x,0,z,1,null);
            var s=new PlayerTorsoSkeleton(frame.pose()); s.groundFootContacts(frame.pose(),false);
            Vector3f leftSole=sole(frame.pose(),s,"left");
            Vector3f rightSole=sole(frame.pose(),s,"right");
            require(leftSole.x>rightSole.x+.4f,"Legs must not scissor-cross during A/D/S reversals or sharp turns: leftX="+leftSole.x+" rightX="+rightSole.x);
            require(frame.pose().rotation("left_calf").x()>1f && frame.pose().rotation("right_calf").x()>1f,
                    "Legs must not lock into a hyper-extended stick during A/D/S reversals");
        }
    }

    private static Point world(Vector3f p,double x,double y,double z,float yaw,double size,boolean crouch) {
        double a=Math.toRadians(yaw),c=Math.cos(a),s=Math.sin(a),scale=SCALE*size;
        return new Point(x+scale*(p.x*c+p.z*s),y+scale*(PlayerTorsoSkeleton.floorPixels(crouch)-p.y),
                z+scale*(p.x*s-p.z*c));
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }

    public static Vector3f sole(AnimationPose pose, PlayerTorsoSkeleton skeleton, String side) {
        Quaternionf thigh = new Quaternionf(skeleton.pelvisRotation)
                .mul(PlayerTorsoSkeleton.rotation(pose.rotation(side + "_leg")));
        Quaternionf calf = PlayerTorsoSkeleton.rotation(pose.rotation(side + "_calf"));
        Quaternionf ankle = PlayerTorsoSkeleton.rotation(pose.rotation(side + "_ankle"));
        Vector3f p = new Vector3f(0, 2, 0);
        ankle.transform(p).add(0, 4, 0);
        calf.transform(p).add(0, 6, 0);
        thigh.transform(p);
        return p.add(PlayerTorsoSkeleton.offset(skeleton.hip, skeleton.pelvisRotation,
                side.equals("left") ? 2.2f : -2.2f, 0, 0));
    }
}

