package ekuzo.crazyworldprogression.progression.skilltrees;

import java.util.List;

public record SkillTreeDefinition(
        String id,
        String name,
        String germanName,
        String icon,
        SkillTreeType type,
        List<SkillNode> skills
) {
    // Find a node by the stable id used by network requests and save data.
    public SkillNode findSkill(String skillId) {
        return skills.stream().filter(skill -> skill.id().equals(skillId)).findFirst().orElse(null);
    }

    // Build the save-data key shared by definitions, purchases, and effects.
    public String persistedKey(SkillNode skill) {
        return id + "/" + skill.id();
    }

    public enum SkillTreeType {
        GLOBAL,
        PERSONAL
    }

    public record SkillNode(
            String id,
            String name,
            String germanName,
            String description,
            String germanDescription,
            String icon,
            List<String> previous,
            int following,
            List<String> stats,
            SkillCosts costs
    ) {
    }

    public record SkillCosts(
            long kingdomPoints,
            long echelonPoints,
            long fakhrulCurrency,
            long powerfulSouls
    ) {
        public static final SkillCosts FREE = new SkillCosts(0L, 0L, 0L, 0L);

        // Reject negative costs because they would turn a purchase into a reward exploit.
        public SkillCosts {
            if (kingdomPoints < 0L || echelonPoints < 0L || fakhrulCurrency < 0L || powerfulSouls < 0L) {
                throw new IllegalArgumentException("Skill costs must not be negative");
            }
        }
    }
}
