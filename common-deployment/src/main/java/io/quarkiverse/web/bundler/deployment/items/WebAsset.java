package io.quarkiverse.web.bundler.deployment.items;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;

public interface WebAsset {

    String relativePath();

    Optional<String> watchedPath();

    byte[] content();

    Optional<Path> path();

    boolean isSource();

    Charset charset();

    static void watchAssets(BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            List<WebAsset> assets) {
        for (WebAsset asset : assets) {
            asset.watchedPath().ifPresent(s -> watchedFiles.produce(HotDeploymentWatchedFileBuildItem.builder()
                    .setRestartNeeded(true)
                    .setLocation(s)
                    .build()));
        }
    }

    static boolean isLocalFileSystem(Path path) {
        try {
            return "file".equalsIgnoreCase(path.getFileSystem().provider().getScheme());
        } catch (Exception e) {
            return false;
        }
    }

}
