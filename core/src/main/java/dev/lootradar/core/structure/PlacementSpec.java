package dev.lootradar.core.structure;

/**
 * 结构集放置参数，字段与 {@code data/<version>/structures.json} 一一对应， 语义对齐原版 structure_set
 * codec（salt/spacing/separation/frequency/exclusion_zone）。
 *
 * <p>{@code locateOffset} 只用于 /locate 类坐标换算，不参与起始区块计算； {@code count/distance/spread} 仅对
 * concentric_rings（要塞）有意义。
 */
public record PlacementSpec(
        Type type,
        String spreadType,
        int salt,
        int spacing,
        int separation,
        float frequency,
        String frequencyReductionMethod,
        int[] locateOffset,
        ExclusionZone exclusionZone,
        Integer count,
        Integer distance,
        Integer spread) {
    public enum Type {
        RANDOM_SPREAD,
        CONCENTRIC_RINGS,
        FEATURE;

        public static Type parse(String raw) {
            return switch (raw) {
                case "random_spread" -> RANDOM_SPREAD;
                case "concentric_rings" -> CONCENTRIC_RINGS;
                case "feature" -> FEATURE;
                default -> throw new IllegalArgumentException("unknown placement type: " + raw);
            };
        }
    }

    public record ExclusionZone(int chunkCount, String otherSet) {}

    public boolean isRandomSpread() {
        return type == Type.RANDOM_SPREAD;
    }
}
