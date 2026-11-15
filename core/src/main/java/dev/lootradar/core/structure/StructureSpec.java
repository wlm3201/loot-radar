package dev.lootradar.core.structure;

/**
 * 单个结构的目录项。{@code decorationIndex/generationStep} 与 {@code featureIndex/featureStep(...Deep)}
 * 是「钉死」的装饰阶段下标：由 {@code DecorationIndexDumpTest} + {@code tools/pin-decoration-indices.py} 从原生注册表
 * 校准得到，避免不同版本注册表遍历顺序变化导致随机流错位。
 */
public record StructureSpec(
        String id,
        String zhName,
        String dimension,
        String set,
        String skipKey,
        String notes,
        Integer decorationIndex,
        Integer generationStep,
        Integer featureIndex,
        Integer featureStep,
        Integer featureIndexDeep,
        Integer featureStepDeep,
        Integer rarity) {
    public boolean isSkippedBy(String key) {
        return skipKey != null && skipKey.equals(key);
    }
}
