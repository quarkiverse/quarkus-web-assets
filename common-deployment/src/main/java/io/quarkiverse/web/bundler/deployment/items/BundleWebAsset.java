package io.quarkiverse.web.bundler.deployment.items;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

public record BundleWebAsset(WebAsset webAsset, BundleType type) implements WebAsset {

    public enum BundleType {
        GENERATED_ENTRY_POINT("generated entry-point"), // a named generated entry-point main.js, page1.js
        INDEX("custom index"), // index.js, index.ts, index.jsx, index.tsx
        MANUAL("available for import"), // Add this to the working directory but do not bundle it (the entrypoint may import it)
        AUTO("auto-imported") // Add this to the working directory and index it automatically as part of the bundle
        ;

        private String label;

        BundleType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Override
    public Optional<String> watchedPath() {
        return webAsset.watchedPath();
    }

    @Override
    public byte[] content() {
        return webAsset.content();
    }

    @Override
    public Optional<Path> path() {
        return webAsset.path();
    }

    @Override
    public boolean isSource() {
        return webAsset.isSource();
    }

    @Override
    public String relativePath() {
        return webAsset.relativePath();
    }

    @Override
    public Charset charset() {
        return webAsset.charset();
    }
}
