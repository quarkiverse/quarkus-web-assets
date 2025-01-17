package io.quarkiverse.web.bundler.deployment.devui;

import static io.quarkiverse.web.bundler.deployment.items.BundleWebAsset.BundleType.GENERATED_ENTRY_POINT;
import static io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourcesProcessor.resolveFromRootPath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.quarkiverse.web.bundler.deployment.items.*;
import io.quarkiverse.web.bundler.deployment.web.GeneratedWebResourceBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.vertx.http.runtime.HttpBuildTimeConfig;

public class WebBundlerDevUIProcessor {

    @BuildStep(onlyIf = IsDevelopment.class)
    public void createPages(HttpBuildTimeConfig httpConfig,
            BuildProducer<CardPageBuildItem> cardPageProducer,
            List<EntryPointBuildItem> entryPoints,
            List<GeneratedEntryPointBuildItem> generatedEntryPoints,
            WebDependenciesBuildItem webDependencies,
            List<GeneratedWebResourceBuildItem> generatedWebResources,
            DevUIWebDependenciesBuildItem devUIWebDependencies) {

        CardPageBuildItem cardPageBuildItem = new CardPageBuildItem();

        if (!webDependencies.isEmpty()) {
            // Web Dependency Libraries
            cardPageBuildItem.addBuildTimeData("webDependencies", devUIWebDependencies.getWebDependencies());

            cardPageBuildItem.addPage(Page.webComponentPageBuilder()
                    .componentLink("qwc-web-bundler-web-dependencies.js")
                    .title("Web Dependencies")
                    .icon("font-awesome-brands:square-js")
                    .staticLabel(String.valueOf(webDependencies.list().size())));

        }
        final Map<String, EntryPointItem> generatedEntryPointsMap = generatedEntryPoints.stream()
                .collect(
                        Collectors.toMap(GeneratedEntryPointBuildItem::key,
                                e -> new EntryPointItem(e.publicPath(),
                                        GENERATED_ENTRY_POINT.label()),
                                (a, b) -> b));

        if (!entryPoints.isEmpty()) {
            final List<EntryPoint> entryPointsForDevUI = entryPoints.stream()
                    .map(e -> new EntryPoint(e.key(), getEntryPointItems(generatedEntryPointsMap, e)))
                    .toList();

            cardPageBuildItem.addBuildTimeData("entryPoints",
                    entryPointsForDevUI);

            cardPageBuildItem.addPage(Page.webComponentPageBuilder()
                    .componentLink("qwc-web-bundler-entry-points.js")
                    .title("Entry Points")
                    .icon("font-awesome-solid:folder-tree")
                    .staticLabel(String.valueOf(entryPointsForDevUI.size())));

        }

        if (!generatedWebResources.isEmpty()) {
            final List<WebAsset> assets = generatedWebResources.stream()
                    .sorted(Comparator.comparing(w -> w.type().order()))
                    .map(w -> new WebAsset(resolveFromRootPath(httpConfig, w.publicPath()), w.type().label()))
                    .toList();

            cardPageBuildItem.addBuildTimeData("staticAssets", assets);
            cardPageBuildItem.addPage(Page.webComponentPageBuilder()
                    .componentLink("qwc-web-bundler-output.js")
                    .title("Static Output")
                    .icon("font-awesome-solid:arrow-right-from-bracket")
                    .staticLabel(String.valueOf(assets.size())));
        }

        cardPageProducer.produce(cardPageBuildItem);
    }

    private static List<EntryPointItem> getEntryPointItems(Map<String, EntryPointItem> generatedEntryPoints,
            EntryPointBuildItem e) {
        final List<EntryPointItem> list = new ArrayList<>();
        if (generatedEntryPoints.containsKey(e.key())) {
            list.add(generatedEntryPoints.get(e.key()));
        }
        list.addAll(e.assets().stream()
                .map(a -> new EntryPointItem(
                        a.relativePath(),
                        a.type().label()))
                .toList());
        return list;
    }

    record WebAsset(String path, String type) {
    }

    record EntryPoint(String key, List<EntryPointItem> items) {
    }

    record EntryPointItem(String path, String type) {
    }

}
