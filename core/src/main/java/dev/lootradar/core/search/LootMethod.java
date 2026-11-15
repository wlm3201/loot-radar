package dev.lootradar.core.search;

public enum LootMethod {
    CHEST("chest", "箱子"),
    ARCHAEOLOGY("archaeology", "考古"),
    POT_SHERD("pot_sherd", "陶罐"),
    CORRIDOR_POT("corridor_pot", "柱廊陶罐"),
    VAULT("vault", "宝库"),
    VAULT_OMINOUS("vault_ominous", "不祥宝库"),
    ENTITY("entity", "生物");

    public final String id;
    public final String zhName;

    LootMethod(String id, String zhName) {
        this.id = id;
        this.zhName = zhName;
    }

    public static LootMethod parse(String id) {
        for (LootMethod method : values()) {
            if (method.id.equals(id)) {
                return method;
            }
        }
        throw new IllegalArgumentException("unknown loot method: " + id);
    }
}
