package bl0.bl0jv2.cli;

import bl0.bl0jv2.generation.Bl0jv2_Linker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared libraries of a system, from a manifest: a text file naming one source file per
 * line (paths relative to the working directory, '#' starts a comment), in load order - a
 * library comes after every library it imports. A program built against the manifest does not
 * contain those files' code, it links to them when it loads; the libraries themselves go on
 * the disk as lib/&lt;name&gt;.bl0c together with lib/MANIFEST, the names in load order.
 */
public final class SharedLibs {
    private final List<Path> files;
    private final Set<String> keys = new LinkedHashSet<>();

    private SharedLibs(List<Path> files) {
        this.files = files;
        for (Path f : files) keys.add(Bl0jv2_Linker.libraryKey(f));
    }

    public static SharedLibs read(Path manifest) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            int hash = line.indexOf('#');
            String entry = (hash >= 0 ? line.substring(0, hash) : line).trim();
            if (!entry.isEmpty()) files.add(Path.of(entry));
        }
        Set<String> names = new LinkedHashSet<>();
        for (Path f : files)
            if (!names.add(imageName(f)))
                throw new IOException(manifest + ": two libraries would both be called " + imageName(f));
        return new SharedLibs(files);
    }

    public List<Path> files() {
        return files;
    }

    /** how a file is recognised as one of the libraries (see Bl0jv2_Linker.libraryKey) */
    public Set<String> keys() {
        return keys;
    }

    /** the name of a library's image on the disk, below lib/ */
    static String imageName(Path source) {
        String name = source.getFileName().toString();
        return (name.endsWith(".bl0") ? name.substring(0, name.length() - 4) : name) + ".bl0c";
    }
}
