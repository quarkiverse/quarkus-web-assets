package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.BundleWebAssetsScannerProcessor.hasChanged;

import java.io.IOException;
import java.util.List;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.items.HtmlTemplatesBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;

public class HtmlTemplateAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(HtmlTemplateAssetsScannerProcessor.class);

    @BuildStep
    HtmlTemplatesBuildItem scan(ProjectResourcesScannerBuildItem scanner,
            WebBundlerConfig config,
            LiveReloadBuildItem liveReload)
            throws IOException {
        LOGGER.debug("Web bundler html templates scan started");
        final HtmlTemplatesContext context = liveReload.getContextObject(HtmlTemplatesContext.class);
        if (liveReload.isLiveReload()
                && context != null
                && !hasChanged(config, liveReload, s -> s.substring(config.webRoot().length()).matches("^/.+\\.html$"))) {
            LOGGER.debug("Web bundler html templates scan not needed for live reload");
            return new HtmlTemplatesBuildItem(context.assets());
        }
        final List<WebAsset> assets = scanner.scan(new ProjectResourcesScannerBuildItem.Scanner(config.webRoot(),
                "glob:*.html", config.charset()));
        liveReload.setContextObject(HtmlTemplatesContext.class, new HtmlTemplatesContext(assets));
        LOGGER.debugf("Web bundler %d html templates found.", assets.size());
        return new HtmlTemplatesBuildItem(assets);
    }

    private record HtmlTemplatesContext(List<WebAsset> assets) {
    }
}
