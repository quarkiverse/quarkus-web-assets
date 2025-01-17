package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.items.WebAsset.watchAssets;

import java.io.IOException;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.QuteTemplatesBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;

public class QuteTemplateAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(QuteTemplateAssetsScannerProcessor.class);

    @BuildStep
    QuteTemplatesBuildItem scan(ProjectResourcesScannerBuildItem scanner,
            WebBundlerConfig config,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            LiveReloadBuildItem liveReload)
            throws IOException {

        final HtmlTemplatesContext context = liveReload.getContextObject(HtmlTemplatesContext.class);
        if (liveReload.isLiveReload()
                && context != null
                && WebBundlerConfig.isEqual(config, context.config())
                && !scanner.hasWebStuffChanged(liveReload.getChangedResources())) {
            LOGGER.debug("Web Bundler scan - html templates: no change detected");
            return new QuteTemplatesBuildItem(context.assets());
        }
        LOGGER.debug("Web Bundler scan - html templates: start");
        final List<WebAsset> assets = scanner
                .scan(new ProjectResourcesScannerBuildItem.Scanner("glob:*.html", config.charset()));
        watchAssets(watchedFiles, assets);
        liveReload.setContextObject(HtmlTemplatesContext.class, new HtmlTemplatesContext(config, assets));
        LOGGER.debugf("Web Bundler scan - html templates: %d found.", assets.size());
        return new QuteTemplatesBuildItem(assets);
    }

    private record HtmlTemplatesContext(WebBundlerConfig config, List<WebAsset> assets) {
    }
}
