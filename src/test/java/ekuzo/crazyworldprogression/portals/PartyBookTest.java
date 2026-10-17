package ekuzo.crazyworldprogression.portals;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyBookTest {
    private final UUID leader = UUID.randomUUID(), alex = UUID.randomUUID(), sam = UUID.randomUUID();

    @Test void publicPartiesCanBeJoinedDirectlyUntilFull() {
        PartyBook book = new PartyBook();
        PartyBook.Party party = book.create(leader, "Raiders", PartyBook.Visibility.PUBLIC);
        book.setMaxSize(leader, 2);
        assertTrue(book.join(alex, party.id()));
        assertEquals(party, book.partyOf(alex));
        assertThrows(IllegalArgumentException.class, () -> book.join(sam, party.id()), "full");
    }

    @Test void semiPrivatePartiesTurnJoinsIntoRequestsForTheLeader() {
        PartyBook book = new PartyBook();
        PartyBook.Party party = book.create(leader, "Careful", PartyBook.Visibility.SEMI_PRIVATE);
        assertFalse(book.join(alex, party.id()));
        assertNull(book.partyOf(alex));
        assertEquals(List.of(alex), party.requests());
        assertThrows(IllegalArgumentException.class, () -> book.acceptRequest(alex, alex), "only the leader decides");
        book.acceptRequest(leader, alex);
        assertEquals(party, book.partyOf(alex));
        assertTrue(party.requests().isEmpty());
    }

    @Test void privatePartiesAreHiddenAndInviteOnly() {
        PartyBook book = new PartyBook();
        PartyBook.Party party = book.create(leader, "Secret", PartyBook.Visibility.PRIVATE);
        book.create(sam, "Open", PartyBook.Visibility.PUBLIC);
        assertEquals(1, book.browser().size());
        assertFalse(book.browser().contains(party));
        assertThrows(IllegalArgumentException.class, () -> book.join(alex, party.id()));
        book.invite(leader, alex);
        assertEquals(List.of(party), book.invitesFor(alex));
        book.acceptInvite(alex, party.id());
        assertEquals(party, book.partyOf(alex));
        assertTrue(book.invitesFor(alex).isEmpty());
    }

    @Test void leavingHandsOverLeadershipAndTheLastMemberEndsTheParty() {
        PartyBook book = new PartyBook();
        PartyBook.Party party = book.create(leader, "Two", PartyBook.Visibility.PUBLIC);
        book.join(alex, party.id());
        book.leave(leader);
        assertEquals(alex, party.leader());
        book.leave(alex);
        assertNull(book.party(party.id()));
    }

    @Test void instancesBelongToThePartyIdAndLeavingMeansANewInstance() {
        PartyBook book = new PartyBook();
        PartyBook.Party party = book.create(leader, "Delvers", PartyBook.Visibility.PUBLIC);
        book.join(alex, party.id());
        String partyKey = book.leaseKey(leader);
        assertEquals(partyKey, book.leaseKey(alex));
        assertTrue(partyKey.contains(party.id().toString()));
        PortalData.StoredInstance instance = new PortalData.StoredInstance(Identifier.parse("cwp:dungeon"),
                Identifier.parse("cwp:pocket"), Identifier.parse("crazy-world-progression:portal_instance_1"), 1L, 2L,
                0, 64, 0, 4, 4, 4, List.of(leader.toString(), alex.toString()));
        assertTrue(PortalService.belongsTo(partyKey, book.leaseKey(alex), instance, alex));
        book.leave(alex);
        assertNotEquals(partyKey, book.leaseKey(alex));
        assertFalse(PortalService.belongsTo(partyKey, book.leaseKey(alex), instance, alex),
                "after leaving, the old party's dungeon is no longer theirs");
        // Instances from before parties existed still admit their original players.
        assertTrue(PortalService.belongsTo("", book.leaseKey(alex), instance, alex));
    }
}
