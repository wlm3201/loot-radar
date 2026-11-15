package dev.lootradar.core.structure;

import java.util.List;

public record StructureSetSpec(String id, PlacementSpec placement, List<Member> structures) {

    public record Member(String id, int weight) {}
}
