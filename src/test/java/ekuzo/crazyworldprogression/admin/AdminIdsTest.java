package ekuzo.crazyworldprogression.admin;

import ekuzo.crazyworldprogression.api.CwpIdentifiers;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminIdsTest {
    @Test
    void plainCreationUsesCwpNamespaceAndLegacyReferencesStayStable() {
        Identifier old = Identifier.parse("cwp:fish");
        assertEquals(Identifier.parse("crazy-world-progression:fish"), AdminIds.create("fish"));
        assertEquals(old, AdminIds.reference("fish", List.of(old)));
        assertEquals("fish", AdminIds.display(old));
        assertEquals("fish", AdminIds.display(AdminIds.create("fish")));
        assertEquals("othermod:fish", AdminIds.display(Identifier.parse("othermod:fish")));
        assertThrows(IllegalArgumentException.class, () -> AdminIds.reference("fish",
                List.of(old, AdminIds.create("fish"))));
        assertThrows(IllegalArgumentException.class, () -> AdminIds.createAvailable("fish", List.of(old)));
        assertThrows(IllegalArgumentException.class,
                () -> CwpIdentifiers.requireCustomRegistration(Identifier.parse("fish")));
        assertEquals(Identifier.parse("othermod:fish"),
                CwpIdentifiers.requireCustomRegistration(Identifier.parse("othermod:fish")));
    }
}
