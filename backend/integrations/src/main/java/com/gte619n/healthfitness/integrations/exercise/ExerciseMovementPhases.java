package com.gte619n.healthfitness.integrations.exercise;

import com.gte619n.healthfitness.core.exercise.DemoPhase;
import com.gte619n.healthfitness.core.exercise.MovementPattern;

/**
 * Movement-pattern-driven position descriptions so the still phases and the
 * video ROM are concrete and <em>maximal</em> for ANY exercise — not just bench
 * press (IMPL-15, ADR-0009). Every catalog exercise carries a
 * {@link MovementPattern}, so this generalizes across the whole catalog by
 * movement class rather than per exercise name.
 *
 * <p>{@link #position} gives the body/equipment position for one of the three
 * demo stills; {@code MID} is always the deepest / fully-contracted (hardest)
 * point with explicit "full range, not partial" language — that's the fix for
 * shallow mid-rep stills. {@link #fullRom} is the one-sentence full-rep
 * description used by the video prompt.
 */
public final class ExerciseMovementPhases {

    private ExerciseMovementPhases() {}

    public static String position(MovementPattern pattern, DemoPhase phase) {
        MovementPattern p = pattern == null ? MovementPattern.OTHER : pattern;
        return switch (phase) {
            case START -> start(p);
            case MID -> mid(p);
            case END -> end(p);
        };
    }

    /** The deepest / peak-effort position — always at MAXIMUM range, never partial. */
    private static String mid(MovementPattern p) {
        return switch (p) {
            case SQUAT -> "at the absolute bottom of the squat — hips dropped to or below knee "
                + "level (at or below parallel), full depth; never a shallow quarter or half squat";
            case HINGE -> "at the bottom of the hinge — hips pushed all the way back, torso hinged "
                + "well forward, a deep full hamstring stretch, the weight lowered to mid-shin; "
                + "full range, not a partial bend";
            case LUNGE -> "at the deepest point of the lunge — the rear knee lowered to just above "
                + "the floor and the front thigh parallel to the ground; full depth, not a shallow dip";
            case PUSH_HORIZONTAL -> "at the deepest point — the weight lowered all the way until it "
                + "lightly touches the chest with the elbows fully bent and tucked below the torso; "
                + "full range, never stopped short above the chest";
            case PUSH_VERTICAL -> "at the bottom — the weight at shoulder height with the elbows "
                + "fully bent and down, the deepest position before pressing; full range";
            case PULL_HORIZONTAL -> "at peak contraction — the weight pulled all the way in until it "
                + "reaches the torso/hip with the shoulder blade fully retracted; full range, not a "
                + "short partial pull";
            case PULL_VERTICAL -> "at peak contraction — pulled all the way until the chin clears the "
                + "bar or the handle reaches the upper chest; full range";
            case CARRY -> "mid-carry, holding the loaded weight in a tall, braced position while walking";
            case CORE -> "at the point of peak contraction and tension of the movement, full range";
            case CARDIO -> "at the most dynamic mid-point of the movement";
            case MOBILITY, STRETCH -> "at the position of deepest stretch and greatest range of the movement";
            case OTHER -> "at the deepest, fullest-range point of the movement (the hardest position), "
                + "at maximum range — never a shallow or partial position";
        };
    }

    /** The finished / locked-out position. */
    private static String end(MovementPattern p) {
        return switch (p) {
            case SQUAT, HINGE, LUNGE -> "standing fully upright at complete hip and knee lockout";
            case PUSH_HORIZONTAL -> "pressed to full arm lockout, elbows completely straight";
            case PUSH_VERTICAL -> "pressed fully overhead to complete elbow lockout, arms straight overhead";
            case PULL_HORIZONTAL, PULL_VERTICAL -> "returned to the fully extended start, arms straight at a full stretch";
            case CARRY -> "at the end of the carry, still tall and braced";
            case CORE -> "returned to the fully lengthened, neutral end of the movement";
            case CARDIO, MOBILITY, STRETCH, OTHER -> "at the finished end position of the movement";
        };
    }

    /** The beginning position. */
    private static String start(MovementPattern p) {
        return switch (p) {
            case SQUAT, HINGE, LUNGE, CARRY -> "standing tall and braced with the loaded weight, at the start of the movement";
            case PUSH_HORIZONTAL -> "holding the weight at full arm extension over the chest, at the start before lowering";
            case PUSH_VERTICAL -> "the weight racked at shoulder height, at the start before pressing";
            case PULL_HORIZONTAL, PULL_VERTICAL -> "the weight at a full stretch with the arms fully extended, at the start before pulling";
            case CORE, CARDIO, MOBILITY, STRETCH, OTHER -> "at the neutral starting position of the movement";
        };
    }

    /** One-sentence full-rep ROM for the video prompt. */
    public static String fullRom(MovementPattern p) {
        if (p == null) {
            return "reach the full bottom/stretch position, then drive all the way to full extension at the top.";
        }
        return switch (p) {
            case SQUAT -> "descend until the hips drop clearly below the knees (at or below parallel), "
                + "then stand up to full hip and knee extension.";
            case HINGE -> "lower under a full hip hinge until a deep hamstring stretch, then stand to full lockout.";
            case LUNGE -> "drop the rear knee toward the floor until the front thigh is parallel, then drive to full standing.";
            case PUSH_HORIZONTAL -> "lower the weight until it lightly touches the chest, then press to full elbow lockout.";
            case PUSH_VERTICAL -> "start at shoulder level and press fully overhead to locked-out elbows.";
            case PULL_HORIZONTAL -> "let the weight hang to a full stretch at the bottom, then pull until it reaches the torso/hip.";
            case PULL_VERTICAL -> "start from a dead hang at full stretch, then pull until the chin clears the bar / handle reaches the chest.";
            case CORE -> "move through the complete contraction and full lengthening of each rep.";
            case CARRY, CARDIO, MOBILITY, STRETCH, OTHER -> "move through the complete, full range the movement allows.";
        };
    }
}
