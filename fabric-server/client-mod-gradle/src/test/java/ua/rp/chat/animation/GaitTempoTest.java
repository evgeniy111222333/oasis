package ua.rp.chat.animation;

import ua.rp.chat.client.animation.GroundedGait;
import ua.rp.chat.client.animation.PlayerTorsoSkeleton;

/** Cadence is a user-facing constraint as well as world-contact accuracy. */
public final class GaitTempoTest {
    public static void main(String[] args) throws Exception {
        AnimationLibrary library;
        try(var input=GaitTempoTest.class.getResourceAsStream("/assets/eclipseclient/animations/rp_character_v3.animation.json")) {
            library=AnimationLibrary.load(input);
        }
        double walkSpeed = ua.rp.chat.locomotion.RoleplayPacing.WALK_STRIDE_METERS
                / ua.rp.chat.locomotion.RoleplayPacing.WALK_CYCLE_SECONDS;
        double crouchSpeed = ua.rp.chat.locomotion.RoleplayPacing.CROUCH_STRIDE_METERS
                / ua.rp.chat.locomotion.RoleplayPacing.CROUCH_CYCLE_SECONDS;
        double sprintSpeed = ua.rp.chat.locomotion.RoleplayPacing.SPRINT_STRIDE_METERS
                / ua.rp.chat.locomotion.RoleplayPacing.SPRINT_CYCLE_SECONDS;
        for(int fps:new int[]{30,60,144}) {
            cadence(library,fps,walkSpeed,false,false,"walk_loop",ua.rp.chat.locomotion.RoleplayPacing.WALK_CYCLE_SECONDS);
            cadence(library,fps,crouchSpeed,false,true,"sneak_loop",ua.rp.chat.locomotion.RoleplayPacing.CROUCH_CYCLE_SECONDS);
            cadence(library,fps,sprintSpeed,true,false,"sprint_loop",ua.rp.chat.locomotion.RoleplayPacing.SPRINT_CYCLE_SECONDS);
            supportTransitionsStayContinuous(library,fps,walkSpeed,false,false);
            supportTransitionsStayContinuous(library,fps,crouchSpeed,false,true);
            supportTransitionsStayContinuous(library,fps,sprintSpeed,true,false);
        }
        System.out.println("GaitTempoTest: authored walking/crouching/sprint periods and actual footstep counts passed at 30/60/144 FPS");
    }

    private static void supportTransitionsStayContinuous(AnimationLibrary library,int fps,double speed,boolean sprint,boolean crouch) {
        var controller=new LocomotionController(library); var gait=new GroundedGait();
        var motion=new LocomotionController.Motion(speed,1,0,sprint,crouch,true,0,LocomotionController.MoveIntent.MOVING);
        double previous=Double.NaN,maxHeight=0,minHeight=10;
        for(int i=0;i<fps*6;i++) {
            double t=i/(double)fps;
            var frame=gait.sample(controller,t,motion,0,0,speed*t,1,null);
            var fitted=new PlayerTorsoSkeleton(frame.pose()); fitted.groundFootContacts(frame.pose(),crouch);
            double height=(PlayerTorsoSkeleton.floorPixels(crouch)-fitted.hip.y)*.9375/16;
            if(i>fps*2) {
                double velocity=Math.abs(height-previous)*fps;
                if(velocity>(sprint?6:2.5))throw new AssertionError("Support transition pops the body: sprint="+sprint
                        +" crouch="+crouch+" velocity="+velocity+" m/s t="+t+" FPS="+fps);
                maxHeight=Math.max(maxHeight,height); minHeight=Math.min(minHeight,height);
            }
            previous=height;
        }
        // Fit may ease away from the exact authored height around landing, but never crouch down
        // to reach an airborne foot or snap back up on the first flight frame.
        if(sprint && (minHeight<.48 || maxHeight<.70))throw new AssertionError("Sprint must keep a natural body-height range: "+minHeight+".."+maxHeight);
    }
    private static void cadence(AnimationLibrary library,int fps,double speed,boolean sprint,boolean crouch,String clip,double period) {
        var controller=new LocomotionController(library); var gait=new GroundedGait();
        var motion=new LocomotionController.Motion(speed,1,0,sprint,crouch,true,0,LocomotionController.MoveIntent.MOVING);
        LocomotionController.Frame previous=null;
        boolean[] oldPlanted=new boolean[2];
        int actualSteps=0; double cycles=0;
        for(int i=0;i<=fps*12;i++) {
            double t=i/(double)fps;
            var frame=gait.sample(controller,t,motion,0,0,speed*t,1,null);
            if(i>fps*2 && previous!=null && previous.clip().equals(clip) && frame.clip().equals(clip)) {
                double delta=frame.phase()-previous.phase(); if(delta<0)delta++;
                double rate=delta*fps;
                if(Math.abs(rate-1/period)>.01)
                    throw new AssertionError(clip+" must keep authored period "+period+"s; actual "+(1/rate)+"s ("+fps+" FPS)");
                cycles+=delta;
                for(int side=0;side<2;side++) if(gait.planted(side)&&!oldPlanted[side])actualSteps++;
            }
            for(int side=0;side<2;side++)oldPlanted[side]=gait.planted(side);
            previous=frame;
        }
        if(Math.abs(actualSteps-2*cycles)>2)throw new AssertionError("Body and footstep cadence must agree: "+clip);
    }
}
