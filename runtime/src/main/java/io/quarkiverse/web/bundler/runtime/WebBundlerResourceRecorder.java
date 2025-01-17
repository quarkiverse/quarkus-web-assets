package io.quarkiverse.web.bundler.runtime;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import io.quarkiverse.web.bundler.runtime.devmode.ChangeEventHandler;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

@Recorder
public class WebBundlerResourceRecorder {

    private static volatile Function<Consumer<Set<String>>, Runnable> hotDeploymentEventHandlerRegister;
    private static volatile Runnable startWatchScheduler;

    public Handler<RoutingContext> createChangeEventHandler(final String webResourcesDirectory,
            String webRoot,
            final Set<String> webResources,
            ShutdownContext shutdownContext) {
        startWatchScheduler.run();
        return new ChangeEventHandler(hotDeploymentEventHandlerRegister, webResourcesDirectory, webRoot,
                webResources,
                shutdownContext);
    }

    public static void setHotDeploymentEventHandlerRegister(
            Function<Consumer<Set<String>>, Runnable> hotDeploymentEventHandlerRegister) {
        WebBundlerResourceRecorder.hotDeploymentEventHandlerRegister = hotDeploymentEventHandlerRegister;
    }

    public static void setStartWatchScheduler(Runnable startWatchScheduler) {
        WebBundlerResourceRecorder.startWatchScheduler = startWatchScheduler;
    }

}
