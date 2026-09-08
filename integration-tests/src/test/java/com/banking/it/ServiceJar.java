package com.banking.it;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Locates a sibling module's packaged Spring Boot jar.
 *
 * <p>These tests run the services from the jars the reactor just built, copied into a stock JRE
 * image, rather than from the images the Dockerfiles produce. The Dockerfiles run a full Maven build
 * inside the container — three services would be several minutes per run, for a jar Maven has
 * already produced two modules earlier. What is under test is the code and its configuration, and
 * both are inside the jar; the image around it contributes a base layer and a curl binary.
 *
 * <p>The trade is honest and worth stating: this does <em>not</em> test the Dockerfiles. If one
 * stops producing a runnable image, these tests still pass.
 */
final class ServiceJar {

    private ServiceJar() {}

    static Path of(String module) {
        Path target = Path.of("..", module, "target").toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            throw new IllegalStateException(notBuilt(module, target));
        }
        try (Stream<Path> files = Files.list(target)) {
            List<Path> jars = files
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    // `package` leaves the pre-repackage jar behind as *.jar.original, which the
                    // suffix filter above already excludes; sources/javadoc jars would not be.
                    .filter(p -> !p.getFileName().toString().matches(".*-(sources|javadoc)\\.jar"))
                    .toList();

            if (jars.size() != 1) {
                throw new IllegalStateException(
                        "expected exactly one jar in " + target + ", found " + jars);
            }
            return jars.get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The failure mode worth a sentence: running this module alone (`-pl integration-tests`) skips
     * the modules whose jars it runs, and the resulting error would otherwise be a bare
     * NoSuchFileException naming a target directory.
     */
    private static String notBuilt(String module, Path target) {
        return module + " has not been packaged — no " + target + ".\n"
                + "These tests run the service jars, so the modules must be built first:\n"
                + "  ./mvnw verify                     (the whole reactor; what CI runs)\n"
                + "  ./mvnw package -DskipTests -pl " + module + " -am   (just this one)";
    }
}
