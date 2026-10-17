package ekuzo.crazyworldprogression.portals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncounterBannerTest {
    @Test void everyBannerHasTranslationKeys() {
        assertEquals("banner.crazy-world-progression.encounter.started", EncounterBanner.Kind.STARTED.titleKey());
        assertEquals("banner.crazy-world-progression.encounter.wiped.subtitle", EncounterBanner.Kind.WIPED.subtitleKey());
    }
}
