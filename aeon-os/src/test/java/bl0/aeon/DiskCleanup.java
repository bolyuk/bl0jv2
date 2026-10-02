package bl0.aeon;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Closes the disk images a test opened, before JUnit deletes its @TempDir: a machine started by a test keeps running
 * on its own thread and holds its image open, and Windows does not delete a file that is open. (Registered for every
 * test by META-INF/services and junit-platform.properties.)
 */
public class DiskCleanup implements AfterEachCallback {
    @Override
    public void afterEach(ExtensionContext context) {
        AeonImage.closeAll();
    }
}
