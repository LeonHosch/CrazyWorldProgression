package ekuzo.crazyworldprogression.api.mobforge;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.server.Bootstrap;
import ekuzo.crazyworldprogression.portals.PortalService;
import net.minecraft.core.BlockPos;
import java.time.Instant;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MobForgeApiTest {
    @BeforeAll static void bootstrapMinecraft() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("example", path); }

    @Test void registriesValidateReferencesAndFreeze() {
        AbilityRegistry abilities = new AbilityRegistry();
        BehaviorRegistry behaviors = new BehaviorRegistry();
        MobRegistry mobs = new MobRegistry();
        abilities.register(id("pulse"), () -> context -> { });
        behaviors.register(id("guard"), (world, handle, mob) -> { });
        MobDefinition definition = MobDefinition.builder(id("sentinel"), EntityTypes.ZOMBIE)
                .tag("boss").ability(id("pulse")).behavior(id("guard")).build();
        mobs.register(definition);
        assertEquals(definition, mobs.get(id("sentinel")).orElseThrow());
        assertEquals(java.util.Set.of(definition), mobs.findByTag("boss"));
        assertTrue(mobs.findByTag("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> mobs.register(definition));
        assertThrows(IllegalArgumentException.class, () -> abilities.register(id("pulse"), () -> context -> { }));
        mobs.freeze(abilities, behaviors);
        abilities.freeze();
        behaviors.freeze();
        assertThrows(IllegalStateException.class, () -> mobs.register(MobDefinition.builder(id("late"), EntityTypes.ZOMBIE).build()));
        assertThrows(UnsupportedOperationException.class, () -> mobs.all().clear());
    }

    @Test void missingAbilityFailsAtFreezeWithMobId() {
        MobRegistry mobs = new MobRegistry();
        mobs.register(MobDefinition.builder(id("sentinel"), EntityTypes.ZOMBIE).ability(id("missing")).build());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> mobs.freeze(new AbilityRegistry(), new BehaviorRegistry()));
        assertTrue(error.getMessage().contains("example:sentinel"));
        assertTrue(error.getMessage().contains("example:missing"));
    }

    @Test void rejectsBadDefinitionsAndSpawnCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> MobDefinition.builder(Identifier.parse("minecraft:zombie"), EntityTypes.ZOMBIE).build());
        assertThrows(IllegalArgumentException.class, () -> MobDefinition.builder(id("bad"), EntityTypes.ZOMBIE).tag("Bad Tag").build());
        assertThrows(NullPointerException.class, () -> MobDefinition.builder(id("bad"), null));
        assertThrows(NullPointerException.class, () -> SpawnContext.at(null, net.minecraft.world.phys.Vec3.ZERO));
    }

    @Test void unifiedResolverPrefersDefinitionsThenTemplatesThenEntityTypes() {
        MobRegistry mobs = new MobRegistry();
        Identifier custom = id("sentinel");
        Identifier vanilla = Identifier.parse("minecraft:zombie");
        mobs.register(MobDefinition.builder(custom, EntityTypes.ZOMBIE).build());
        assertEquals(EntitySpawnService.Resolution.MOB_DEFINITION,
                EntitySpawnService.resolve(custom, mobs, ignored -> true, ignored -> true));
        assertEquals(EntitySpawnService.Resolution.YAML_TEMPLATE,
                EntitySpawnService.resolve(id("template"), mobs, ignored -> true, ignored -> true));
        assertEquals(EntitySpawnService.Resolution.ENTITY_TYPE,
                EntitySpawnService.resolve(vanilla, mobs, ignored -> false, ignored -> true));
        assertThrows(IllegalArgumentException.class,
                () -> EntitySpawnService.resolve(id("missing"), mobs, ignored -> false, ignored -> false));
    }

    @Test void dispatchesEventsWithoutExposingMutableListenerState() {
        AtomicInteger calls = new AtomicInteger();
        MobForgeEvents.register(new MobForgeEvents.Listener() {
            @Override public void onPhaseChanged(MobForgeHandle mob, String previous, String current) {
                if (previous == null && current.equals("enraged")) calls.incrementAndGet();
            }
        });
        MobForgeEvents.phaseChanged(null, null, "enraged");
        assertEquals(1, calls.get());
    }

    @Test void pocketLeaseIdIsStableAndChangesOnRecreation() {
        var first = new PortalService.ActiveInstance(id("portal"), id("template"), id("pocket_1"),
                Instant.ofEpochMilli(1000), Instant.ofEpochMilli(2000), BlockPos.ZERO,
                new PortalService.Vec3iView(10, 10, 10));
        var recreated = new PortalService.ActiveInstance(id("portal"), id("template"), id("pocket_1"),
                Instant.ofEpochMilli(3000), Instant.ofEpochMilli(4000), BlockPos.ZERO,
                new PortalService.Vec3iView(10, 10, 10));
        var restored = new PortalService.ActiveInstance(first.portalId(), first.templateId(), first.slotDimension(),
                first.created(), first.expires(), first.origin(), first.size());
        assertEquals(first.instanceId(), restored.instanceId());
        assertNotEquals(first.instanceId(), recreated.instanceId());
    }
}
