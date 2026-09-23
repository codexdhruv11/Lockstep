package com.lockstep.cli;

import com.lockstep.demo.DemoServer;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "demo-server", description = "Run a small demo API to practise load testing against.")
public final class DemoCommand implements Callable<Integer> {
    @Spec
    CommandSpec spec;

    @Option(names = "--port", description = "Port to listen on (default: ${DEFAULT-VALUE}).")
    int port = 8080;

    @Override
    public Integer call() throws Exception {
        var out = spec.commandLine().getOut();
        try (DemoServer server = DemoServer.start(port)) {
            out.println("demo server on http://localhost:" + server.port());
            out.println("  POST /api/login          -> {\"token\":\"tok-123\"}");
            out.println("  GET  /api/me             -> needs Authorization: Bearer tok-123");
            out.println("  GET  /api/products       -> a product list");
            out.println("  POST /api/orders         -> creates an order");
            out.println("  GET  /api/checkout?token=tok-123");
            out.println("  GET  /api/slow           -> 250ms, for producing a spike on purpose");
            out.println();
            out.println("try: lockstep run -c examples/scenario-login.yaml");
            out.println("Ctrl-C to stop.");
            out.flush();

            Thread.currentThread().join();
        }
        return 0;
    }
}
