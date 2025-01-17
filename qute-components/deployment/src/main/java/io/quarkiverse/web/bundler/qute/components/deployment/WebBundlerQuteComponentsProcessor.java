package io.quarkiverse.web.bundler.qute.components.deployment;

import static io.quarkiverse.web.bundler.deployment.items.WebAsset.watchAssets;
import static io.quarkiverse.web.bundler.qute.components.runtime.WebBundlerQuteContextRecorder.WEB_BUNDLER_ID_PREFIX;
import static io.quarkus.deployment.annotations.ExecutionTime.STATIC_INIT;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

import org.jboss.logging.Logger;

import io.quarkiverse.web.bundler.deployment.WebBundlerConfig;
import io.quarkiverse.web.bundler.deployment.items.EntryPointBuildItem;
import io.quarkiverse.web.bundler.deployment.items.ProjectResourcesScannerBuildItem;
import io.quarkiverse.web.bundler.deployment.items.QuteTagsBuildItem;
import io.quarkiverse.web.bundler.deployment.items.WebAsset;
import io.quarkiverse.web.bundler.qute.components.runtime.WebBundlerQuteContextRecorder;
import io.quarkiverse.web.bundler.qute.components.runtime.WebBundlerQuteEngineObserver;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;

class WebBundlerQuteComponentsProcessor {

    private static final Logger LOGGER = Logger.getLogger(WebBundlerQuteComponentsProcessor.class);

    @BuildStep
    QuteTagsBuildItem scan(ProjectResourcesScannerBuildItem scanner,
            WebBundlerConfig config,
            List<EntryPointBuildItem> entryPoints,
            BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
            LiveReloadBuildItem liveReload)
            throws IOException {

        final QuteComponentsContext context = liveReload.getContextObject(QuteComponentsContext.class);
        if (liveReload.isLiveReload()
                && context != null
                && entryPoints.equals(context.entryPoints)
                && WebBundlerConfig.isEqual(config, context.config())
                && !scanner.hasWebStuffChanged(liveReload.getChangedResources())) {
            LOGGER.debug("Web Bundler scan - Qute Web Components: no changes detected");
            return new QuteTagsBuildItem(context.assets());
        }
        LOGGER.debug("Web Bundler scan - Qute Web Components: start");
        List<ProjectResourcesScannerBuildItem.Scanner> quteTagsAssetsScanners = new ArrayList<>();
        for (Map.Entry<String, WebBundlerConfig.EntryPointConfig> entryPoint : config.bundleWithDefault().entrySet().stream()
                .filter(e -> e.getValue().quteTags()).toList()) {
            final String dir = entryPoint.getValue().effectiveDir(entryPoint.getKey());
            quteTagsAssetsScanners.add(new ProjectResourcesScannerBuildItem.Scanner(dir,
                    "glob:**.html", config.charset()));
        }

        final List<WebAsset> assets = scanner
                .scan(quteTagsAssetsScanners);
        watchAssets(watchedFiles, assets);
        liveReload.setContextObject(QuteComponentsContext.class, new QuteComponentsContext(config, entryPoints, assets));
        LOGGER.debugf("Web Bundler scan - Qute Web Components: %d found.", assets.size());
        return new QuteTagsBuildItem(assets);
    }

    static boolean hasChanged(WebBundlerConfig config,
            LiveReloadBuildItem liveReload,
            Predicate<String> predicate) {
        // Let's just check if the file changed is in the webRoot
        return liveReload.getChangedResources().stream().anyMatch(c -> c.startsWith(config.webRoot()) && predicate.test(c));
    }

    private record QuteComponentsContext(WebBundlerConfig config, List<EntryPointBuildItem> entryPoints,
            List<WebAsset> assets) {
    }

    @BuildStep
    @Record(STATIC_INIT)
    void initQuteTags(
            BuildProducer<AdditionalBeanBuildItem> additionalBeans,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            WebBundlerQuteContextRecorder recorder,
            Optional<QuteTagsBuildItem> quteTags) {
        if (quteTags.isEmpty()) {
            return;
        }
        final Map<String, String> templates = new HashMap<>();
        final List<String> tags = new ArrayList<>();
        for (WebAsset webAsset : quteTags.get().getWebAssets()) {
            final String tag = Path.of(webAsset.relativePath()).getFileName().toString();
            final String tagName = tag.contains(".") ? tag.substring(0, tag.indexOf('.')) : tag;
            templates.put(WEB_BUNDLER_ID_PREFIX + tagName,
                    new String(webAsset.content(), webAsset.charset()));
            tags.add(tagName);
        }
        additionalBeans.produce(new AdditionalBeanBuildItem(WebBundlerQuteEngineObserver.class));
        syntheticBeans.produce(SyntheticBeanBuildItem.configure(WebBundlerQuteContextRecorder.WebBundlerQuteContext.class)
                .supplier(recorder.createContext(tags, templates))
                .done());
    }

}
