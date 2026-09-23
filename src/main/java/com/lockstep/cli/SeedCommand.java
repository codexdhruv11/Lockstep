package com.lockstep.cli;

import com.lockstep.demo.DbSeeder;
import com.lockstep.util.Ansi;
import com.lockstep.util.Numbers;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "seed-db", description = "Fill an orders table with rows so DB queries do real work.")
public final class SeedCommand implements Callable<Integer> {
    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 2;

    @Spec
    CommandSpec spec;

    @Option(names = "--conn", required = true,
            description = "Connection string, Go-style or JDBC (e.g. postgres://user:pw@host:5432/db).")
    String conn;

    @Option(names = "--driver", description = "postgres, mysql or sqlite (default: ${DEFAULT-VALUE}).")
    String driver = "postgres";

    @Option(names = "-n", description = "How many rows to insert (default: ${DEFAULT-VALUE}).")
    long rows = 1_000_000;

    @Override
    public Integer call() {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        long start = System.nanoTime();
        try {
            long inserted = DbSeeder.seed(conn, driver, rows,
                    total -> {
                        out.print("\rseeded " + Numbers.withSeparators(total) + " rows");
                        out.flush();
                    });
            out.println();
            out.println("inserted %s rows in %s".formatted(
                    Numbers.withSeparators(inserted), Numbers.latency(System.nanoTime() - start)));
            return EXIT_OK;
        } catch (Exception e) {
            err.println(Ansi.error("seed failed: " + e.getMessage()));
            return EXIT_FAILED;
        }
    }
}
