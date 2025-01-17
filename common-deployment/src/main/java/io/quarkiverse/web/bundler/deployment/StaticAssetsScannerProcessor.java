package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.items.WebAsset.watchAssets;

import java.io.IOException;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.StaticAssetsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;

public class StaticAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(StaticAssetsScannerProcessor.class);

    @BuildStep
    StaticAssetsBuildItem scan(ProjectResourcesScannerBuildItem scanner,
            WebBundlerConfig config,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            LiveReloadBuildItem liveReload)
            throws IOException {

        final StaticAssetsContext context = liveReload.getContextObject(StaticAssetsContext.class);
        if (liveReload.isLiveReload()
                && context != null
                && WebBundlerConfig.isEqual(config, context.config())
                && !scanner.hasWebStuffChanged(liveReload.getChangedResources())) {
            LOGGER.debug("Web Bundler scan - static assets: no change detected");
            return new StaticAssetsBuildItem(context.assets());
        }
        LOGGER.debug("Web Bundler scan - static assets: start");
        final List<WebAsset> assets = scanner
                .scan(new ProjectResourcesScannerBuildItem.Scanner(config.staticDir(),
                        "glob:**", config.charset()));
        liveReload.setContextObject(StaticAssetsContext.class, new StaticAssetsContext(config, assets));
        watchAssets(watchedFiles, assets);
        LOGGER.debugf("\"Web Bundler scan - static assets: %d found.", assets.size());
        return new StaticAssetsBuildItem(assets);
    }

    private record StaticAssetsContext(WebBundlerConfig config, List<WebAsset> assets) {
    }
}
