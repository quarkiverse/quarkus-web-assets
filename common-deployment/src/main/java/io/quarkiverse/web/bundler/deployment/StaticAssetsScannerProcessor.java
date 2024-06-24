package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.BundleWebAssetsScannerProcessor.hasChanged;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.StaticAssetsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;

public class StaticAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(StaticAssetsScannerProcessor.class);

    @BuildStep
    StaticAssetsBuildItem scan(ProjectResourcesScannerBuildItem scanner,
            WebBundlerConfig config,
            LiveReloadBuildItem liveReload)
            throws IOException {
        LOGGER.debug("Web bundler static assets scan started");
        final StaticAssetsContext context = liveReload.getContextObject(StaticAssetsContext.class);
        if (liveReload.isLiveReload()
                && context != null
                && Objects.equals(config, context.config())
                && !hasChanged(config, liveReload, s -> s.startsWith(config.fromWebRoot(config.staticDir())))) {
            LOGGER.debug("Web bundler static assets scan not needed for live reload");
            return new StaticAssetsBuildItem(context.assets());
        }
        final List<WebAsset> assets = scanner
                .scan(new ProjectResourcesScannerBuildItem.Scanner(config.fromWebRoot(config.staticDir()),
                        "glob:**", config.charset()));
        liveReload.setContextObject(StaticAssetsContext.class, new StaticAssetsContext(config, assets));
        LOGGER.debugf("Web bundler %d static assets found.", assets.size());
        return new StaticAssetsBuildItem(assets);
    }

    private record StaticAssetsContext(WebBundlerConfig config, List<WebAsset> assets) {
    }
}
