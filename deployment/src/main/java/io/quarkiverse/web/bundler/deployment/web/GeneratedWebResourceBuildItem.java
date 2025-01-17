package io.quarkiverse.web.bundler.deployment.web;

import java.nio.file.Path;

import io.quarkus.builder.item.MultiBuildItem;

public final class GeneratedWebResourceBuildItem extends MultiBuildItem {

    public static GeneratedWebResourceBuildItem fromFile(String publicPath, Path path, SourceType type) {
        return new GeneratedWebResourceBuildItem(publicPath, path, null, type);
    }

    public static GeneratedWebResourceBuildItem fromContent(String publicPath, byte[] content, SourceType type) {
        return new GeneratedWebResourceBuildItem(publicPath, null, content, type);
    }

    public enum SourceType {
        BUILD_TIME_TEMPLATE("build-time template", 1),
        STATIC_ASSET("static asset", 2),
        BUNDLED_ASSET("bundled asset", 3),
        ;

        private String label;
        private final int order;

        SourceType(String label, int order) {
            this.label = label;
            this.order = order;
        }

        public String label() {
            return label;
        }

        public int order() {
            return order;
        }
    }

    private final String publicPath;
    private final Path path;
    private final byte[] content;
    private final SourceType type;

    private GeneratedWebResourceBuildItem(String publicPath, Path path, byte[] content, SourceType type) {
        this.publicPath = publicPath;
        this.path = path;
        this.content = content;
        this.type = type;
    }

    public String publicPath() {
        return publicPath;
    }

    public Path path() {
        return path;
    }

    public byte[] content() {
        return content;
    }

    public SourceType type() {
        return type;
    }
}
