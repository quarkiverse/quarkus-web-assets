package io.quarkiverse.web.bundler.qute.components.deployment;

import io.quarkiverse.web.bundler.deployment.WebBundlerConfig;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.qute.deployment.TemplateRootBuildItem;

class WebBundlerQuteTemplateRootProcessor {

    @BuildStep
    void provideWebTemplateRoot(
            BuildProducer<TemplateRootBuildItem> templateRootProducer,
            WebBundlerConfig config) {
        templateRootProducer.produce(new TemplateRootBuildItem(config.prefixWithWebRoot("templates")));
    }

}
