package io.quarkiverse.web.bundler.deployment.items;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import io.quarkus.builder.item.MultiBuildItem;

public final class EntryPointBuildItem extends MultiBuildItem {

    private final EntryPoint entryPoint;

    public EntryPointBuildItem(EntryPoint entryPoint) {
        this.entryPoint = requireNonNull(entryPoint, "entryPoint is required");
    }

    public String key() {
        return entryPoint.key();
    }

    public String dir() {
        return entryPoint.dir();
    }

    public List<BundleWebAsset> assets() {
        return entryPoint.assets();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass())
            return false;
        EntryPointBuildItem that = (EntryPointBuildItem) o;
        return Objects.equals(entryPoint, that.entryPoint);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(entryPoint);
    }

    public record EntryPoint(String key, String dir, List<BundleWebAsset> assets) {

    }
}
