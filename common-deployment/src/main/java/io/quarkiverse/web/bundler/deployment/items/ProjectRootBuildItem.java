package io.quarkiverse.web.bundler.deployment.items;

import java.nio.file.Path;
import java.util.*;

import io.quarkus.builder.item.SimpleBuildItem;

public final class ProjectRootBuildItem extends SimpleBuildItem {

    private final Path path;

    public ProjectRootBuildItem(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return path != null;
    }
}
