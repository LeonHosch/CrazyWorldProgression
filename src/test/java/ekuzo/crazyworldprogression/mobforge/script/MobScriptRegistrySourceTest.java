package ekuzo.crazyworldprogression.mobforge.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MobScriptRegistrySourceTest {
    @TempDir Path directory;

    @Test void sourceSaveKeepsCommentsAndFormatting() throws Exception {
        String source = "script example\n\n# Keep this comment\nwait(duration=\"5s\")\n";
        MobScriptRegistry.writeSource(directory, source);
        assertEquals(source, Files.readString(directory.resolve("example.mfs")));
    }

    @Test void sourceSaveRejectsInvalidScriptWithoutCreatingFile() {
        assertThrows(IllegalArgumentException.class,
                () -> MobScriptRegistry.writeSource(directory, "script ../escape\nwait(duration=\"5s\")\n"));
        assertFalse(Files.exists(directory.resolve("escape.mfs")));
    }
}
