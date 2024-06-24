package io.quarkiverse.web.bundler.deployment;

import static io.quarkiverse.web.bundler.deployment.BundleWebAssetsScannerProcessor.hasChanged;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.QuteTemplatesBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;

public class QuteTemplateAssetsScannerProcessor {

    private static final Logger LOGGER = Logger.getLogger(QuteTemplateAssetsScannerProcessor.class);

    @BuildStep
    QuteTemplatesBuildItem scan(ProjectResourcesScannerBuildItem scanner,
            WebBundlerConfig config,
            LiveReloadBuildItem liveReload)
            throws IOException {
        LOGGER.debug("Web bundler html templates scan started");
        final HtmlTemplatesContext context = liveReload.getContextObject(HtmlTemplatesContext.class);
        if (liveReload.isLiveReload()
                && context != null
                && Objects.equals(config, context.config())
                && !hasChanged(config, liveReload, s -> s.substring(config.webRoot().length()).matches("^/.+\\.html$"))) {
            LOGGER.debug("Web bundler html templates scan not needed for live reload");
            return new QuteTemplatesBuildItem(context.assets());
        }
        final List<WebAsset> assets = scanner.scan(new ProjectResourcesScannerBuildItem.Scanner(config.webRoot(),
                "glob:*.html", config.charset()));
        liveReload.setContextObject(HtmlTemplatesContext.class, new HtmlTemplatesContext(config, assets));
        LOGGER.debugf("Web bundler %d html templates found.", assets.size());
        return new QuteTemplatesBuildItem(assets);
    }

    private record HtmlTemplatesContext(WebBundlerConfig config, List<WebAsset> assets) {
    }
}
