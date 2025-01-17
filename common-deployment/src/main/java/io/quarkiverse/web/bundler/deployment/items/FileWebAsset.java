package io.quarkiverse.web.bundler.deployment.items;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public final class FileWebAsset implements WebAsset {
    private final String relativePath;
    private final Path path;
    private final String watchedPath;
    private final Charset charset;
    private final boolean isSource;

    public FileWebAsset(String relativePath, Path path, String watchedPath, boolean isSource,
            Charset charset) {
        this.relativePath = relativePath;
        this.path = path;
        this.isSource = isSource;
        this.watchedPath = watchedPath;
        this.charset = charset;
    }

    @Override
    public byte[] content() {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<Path> path() {
        return Optional.of(path);
    }

    @Override
    public boolean isSource() {
        return isSource;
    }

    @Override
    public String relativePath() {
        return relativePath;
    }

    @Override
    public Optional<String> watchedPath() {
        return Optional.of(watchedPath);
    }

    @Override
    public Charset charset() {
        return charset;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        var that = (FileWebAsset) obj;
        return Objects.equals(this.relativePath, that.relativePath) &&
                Objects.equals(this.path, that.path) &&
                Objects.equals(this.watchedPath, that.watchedPath) &&
                Objects.equals(this.charset, that.charset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(relativePath, path, watchedPath, charset);
    }

    @Override
    public String toString() {
        return "SourceWebAsset[" +
                "relativePath=" + relativePath + ", " +
                "path=" + path + ", " +
                "watchedPath=" + watchedPath + ", " +
                "charset=" + charset + ']';
    }

}
