package maple.map;

import maple.wz.WzNode;

/**
 * Movement constants from Map.wz/Physics.img (the client's own values, in pixels/second and
 * force units for a mass of 100), converted to per-tick values for the 8 ms simulation step.
 */
public final class Physics {
    private Physics() {}

    static final double DT = 0.008;
    static final double MASS = 100;

    // Raw values (defaults are the GMS v83 values; the file overrides them).
    public static double rawWalkForce = 140000, rawWalkSpeed = 125, rawWalkDrag = 80000;
    public static double rawFloatDrag1 = 100000, rawFloatDrag2 = 10000, rawFloatCoefficient = 0.01;
    public static double rawGravityAcc = 2000, rawFallSpeed = 670, rawJumpSpeed = 555;
    public static boolean fromFile;

    // Per-tick values.
    public static double walkForce, walkSpeed, walkDrag, floatDrag1, floatDrag2, gravity, fallSpeed, jumpSpeed;

    static { convert(); }

    public static void load(WzNode img) {
        if (img == null || !img.exists()) return;
        rawWalkForce = img.getDouble("walkForce", rawWalkForce);
        rawWalkSpeed = img.getDouble("walkSpeed", rawWalkSpeed);
        rawWalkDrag = img.getDouble("walkDrag", rawWalkDrag);
        rawFloatDrag1 = img.getDouble("floatDrag1", rawFloatDrag1);
        rawFloatDrag2 = img.getDouble("floatDrag2", rawFloatDrag2);
        rawFloatCoefficient = img.getDouble("floatCoefficient", rawFloatCoefficient);
        rawGravityAcc = img.getDouble("gravityAcc", rawGravityAcc);
        rawFallSpeed = img.getDouble("fallSpeed", rawFallSpeed);
        rawJumpSpeed = img.getDouble("jumpSpeed", rawJumpSpeed);
        fromFile = true;
        convert();
    }

    private static void convert() {
        walkForce = rawWalkForce / MASS * DT * DT;
        walkSpeed = rawWalkSpeed * DT;
        walkDrag = rawWalkDrag / MASS * DT * DT;
        floatDrag1 = rawFloatDrag1 / MASS * DT * DT;
        floatDrag2 = rawFloatDrag2 / MASS * DT * DT;
        gravity = rawGravityAcc * DT * DT;
        fallSpeed = rawFallSpeed * DT;
        jumpSpeed = rawJumpSpeed * DT;
    }

    public static String describe() {
        return (fromFile ? "Physics.img" : "defaults") + ": walk " + rawWalkSpeed + " px/s, jump " + rawJumpSpeed
                + " px/s, gravity " + rawGravityAcc + ", fall " + rawFallSpeed;
    }
}
