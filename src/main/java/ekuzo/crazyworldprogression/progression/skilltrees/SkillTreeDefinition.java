package ekuzo.crazyworldprogression.progression.skilltrees;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SkillTreeDefinition(
        Identifier id,
        String name,
        String germanName,
        String icon,
        SkillTreeType type,
        List<SkillNode> skills
) {
    public SkillNode findSkill(String skillId) {
        return skills.stream().filter(skill -> skill.id().equals(skillId)).findFirst().orElse(null);
    }

    public String persistedKey(SkillNode skill) {
        return id + "/" + skill.id();
    }

    public enum SkillTreeType { GLOBAL, PERSONAL }

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
            Map<Identifier, Long> costs
    ) {
        public SkillNode {
            costs = Collections.unmodifiableMap(new LinkedHashMap<>(costs));
            if (costs.values().stream().anyMatch(value -> value < 0L)) {
                throw new IllegalArgumentException("Skill costs must not be negative");
            }
        }
    }
}
