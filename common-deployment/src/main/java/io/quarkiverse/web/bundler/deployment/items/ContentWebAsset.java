package io.quarkiverse.web.bundler.deployment.items;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

public record ContentWebAsset(String relativePath, byte[] content, Charset charset) implements WebAsset {

    @Override
    public Optional<String> watchedPath() {
        return Optional.empty();
    }

    @Override
    public Optional<Path> path() {
        return Optional.empty();
    }

    @Override
    public boolean isSource() {
        return false;
    }
}
