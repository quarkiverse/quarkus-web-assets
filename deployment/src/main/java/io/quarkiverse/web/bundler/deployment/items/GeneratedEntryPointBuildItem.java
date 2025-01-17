package io.quarkiverse.web.bundler.deployment.items;

import io.quarkus.builder.item.MultiBuildItem;

public final class GeneratedEntryPointBuildItem extends MultiBuildItem {

    private final String key;
    private final String publicPath;

    public GeneratedEntryPointBuildItem(String key, String publicPath) {
        this.key = key;
        this.publicPath = publicPath;
    }

    public String key() {
        return key;
    }

    public String publicPath() {
        return publicPath;
    }
}
