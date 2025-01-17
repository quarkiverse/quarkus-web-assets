package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.PrepareForBundlingProcessor.createSymbolicLinkOrFallback;
import static io.quarkiverse.web.bundler.deployment.util.PathUtils.prefixWithSlash;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.quarkiverse.web.bundler.deployment.items.StaticAssetsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkiverse.web.bundler.deployment.items.WebBundlerTargetDirBuildItem;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem.SourceType;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.runtime.LaunchMode;

public class StaticWebAssetsProcessor {

    @BuildStep
    void processStaticWebAssets(WebBundlerConfig config,
            WebBundlerTargetDirBuildItem targetDir,
            StaticAssetsBuildItem staticAssets,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFileBuildItemProducer,
            LaunchModeBuildItem launchMode,
            BuildProducer<GeneratedWebResourceBuildItem> staticResourceProducer) {
        final boolean browserLiveReload = launchMode.getLaunchMode().equals(LaunchMode.DEVELOPMENT)
                && config.browserLiveReload();
        for (WebAsset webAsset : staticAssets.getWebAssets()) {
            final String publicPath = webAsset.relativePath();
            final Path targetPath = targetDir.dist().resolve(publicPath);
            try {
                if (webAsset.path().isEmpty()) {
                    staticResourceProducer.produce(GeneratedWebResourceBuildItem.fromContent(
                            prefixWithSlash(publicPath),
                            webAsset.content(), SourceType.STATIC_ASSET));
                } else {
                    if (browserLiveReload) {
                        Files.createDirectories(targetPath.getParent());
                        createSymbolicLinkOrFallback(watchedFileBuildItemProducer, webAsset, targetPath);
                        staticResourceProducer.produce(GeneratedWebResourceBuildItem.fromFile(
                                prefixWithSlash(publicPath),
                                targetPath, SourceType.STATIC_ASSET));
                    } else {
                        // We can read the file
                        staticResourceProducer.produce(GeneratedWebResourceBuildItem.fromContent(
                                prefixWithSlash(publicPath),
                                webAsset.content(), SourceType.STATIC_ASSET));
                    }

                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

}
