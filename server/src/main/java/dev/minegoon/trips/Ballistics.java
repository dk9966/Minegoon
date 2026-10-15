package dev.minegoon.trips;

/**
 * Where to point a fully drawn bow. An arrow leaves at 3 blocks a tick, then
 * every tick moves, keeps 99% of its speed and falls 0.05 blocks a tick faster
 * (https://minecraft.wiki/w/Arrow). The flat arc is found by halving the
 * angle until the arrow crosses the target's distance at the target's height.
 */
final class Ballistics {

    private static final double SPEED = 3.0;
    private static final double DRAG = 0.99;
    private static final double GRAVITY = 0.05;
    private static final int MAX_TICKS = 200;

    /** What the arrow would fly through, by distance along the ground and height above the bow. */
    @FunctionalInterface
    interface Space {
        boolean open(double along, double height);
    }

    private Ballistics() {
    }

    /** The game's pitch, negative upward, for a target this far away and this much higher. */
    static double pitch(double distance, double rise) {
        double low = -Math.PI / 4;
        double high = Math.PI / 4;
        for (int round = 0; round < 32; round++) {
            double angle = (low + high) / 2;
            if (heightAt(angle, distance) < rise) {
                low = angle;
            } else {
                high = angle;
            }
        }
        return -Math.toDegrees((low + high) / 2);
    }

    /** Ticks a level shot takes to cover the distance. */
    static int ticks(double distance) {
        double x = 0;
        double speed = SPEED;
        int tick = 0;
        while (x < distance && tick < MAX_TICKS) {
            x += speed;
            speed *= DRAG;
            tick++;
        }
        return tick;
    }

    /** The arrow flies through open space all the way to the distance, checked every half block. */
    static boolean clear(double angle, double distance, Space space) {
        double x = 0;
        double y = 0;
        double vx = SPEED * Math.cos(angle);
        double vy = SPEED * Math.sin(angle);
        for (int tick = 0; tick < MAX_TICKS && x < distance; tick++) {
            int parts = (int) Math.ceil(Math.hypot(vx, vy) / 0.5);
            for (int part = 1; part <= parts; part++) {
                double along = x + vx * part / parts;
                if (along >= distance) {
                    return true;
                }
                if (!space.open(along, y + vy * part / parts)) {
                    return false;
                }
            }
            x += vx;
            y += vy;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }
        return x >= distance;
    }

    /** The arrow's height where it crosses the distance, shot at this angle above level. */
    static double heightAt(double angle, double distance) {
        double x = 0;
        double y = 0;
        double vx = SPEED * Math.cos(angle);
        double vy = SPEED * Math.sin(angle);
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            if (x + vx >= distance) {
                return y + vy * (distance - x) / vx;
            }
            x += vx;
            y += vy;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }
        return Double.NEGATIVE_INFINITY;
    }
}
