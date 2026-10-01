package com.lockstep.cli;

import com.lockstep.analysis.Survey;
import com.lockstep.report.CliTables;
import com.lockstep.runner.db.SurveyProbe;
import com.lockstep.util.Ansi;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "survey",
        description = "Rank what already costs the most, from the target's own counters. "
                + "Read-only: generates no load and changes nothing.")
public final class SurveyCommand implements Callable<Integer> {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_CONFIG_ERROR = 2;
    static final int EXIT_FAILED = 3;

    @Spec
    CommandSpec spec;

    @Option(names = "--db", required = true,
            description = "Connection string for the target's PostgreSQL, Go-style or JDBC. "
                    + "A read-only user is sufficient and is what you should use.")
    String db;

    @Option(names = "--driver", description = "Driver (default: ${DEFAULT-VALUE}).")
    String driver = "postgres";

    @Option(names = "--metrics",
            description = "The target's metrics endpoint, to rank endpoints as well as queries. "
                    + "Optional; without it the database rankings still work.")
    String metrics;

    @Option(names = "--top", description = "How many rows per ranking (default: ${DEFAULT-VALUE}).")
    int top = 8;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        Survey survey;
        try (SurveyProbe probe = SurveyProbe.open(db, driver)) {
            survey = probe.read(metrics);
        } catch (IllegalArgumentException e) {
            err.println(Ansi.error(e.getMessage()));
            return EXIT_CONFIG_ERROR;
        } catch (RuntimeException e) {
            err.println(Ansi.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            return EXIT_FAILED;
        }

        if (!survey.available()) {
            err.println(Ansi.error(survey.unavailableReason()));
            return EXIT_FAILED;
        }

        out.print(CliTables.surveyReport(survey, Math.max(1, top)));
        out.flush();
        return EXIT_SUCCESS;
    }
}
