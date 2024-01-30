package org.example;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.TriggerBuilder.newTrigger;

public class QuartzBench implements Job {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Args: -props=<prop_file> [-schedule=<after_seconds> [-recovery]]");
            System.exit(1);
        }

        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");

        String fileName = pluck(args, "-props=").orElseThrow();

        System.out.println("Properties file: " + fileName);
        StdSchedulerFactory fact = new StdSchedulerFactory();
        fact.initialize(fileName);

        Scheduler scheduler = fact.getScheduler();

        boolean recovery = Arrays.asList(args).contains("-recovery");

        Optional<Integer> schedule = pluck(args, "-schedule=").map(Integer::parseInt);

        if (schedule.isPresent()) {
            int after = schedule.get();
            System.out.println(
                    "Scheduling on-shot after " + after + "s" +
                    (recovery? " with recovery" : ""));
            scheduler.clear();
            schedule(scheduler, after, recovery);
        }

        scheduler.start();
    }

    private static Optional<String> pluck(String[] args, String prefix) {
        return Arrays.stream(args)
                .filter(a -> a.startsWith(prefix))
                .map(a -> a.substring(prefix.length()))
                .findFirst();
    }

    private static void schedule(
            Scheduler scheduler,
            int after,
            boolean recovery
    )
    throws SchedulerException {
        JobDetail job = newJob(QuartzBench.class)
                .withIdentity("my-job")
                .requestRecovery(recovery)
                .build();
        Trigger trigger = newTrigger()
                .withIdentity("my-trigger")
                .startAt(new Date(Instant.now().plus(after, ChronoUnit.SECONDS).toEpochMilli()))
                .build();
        scheduler.scheduleJob(job, trigger);
    }

    @Override
    public void execute(JobExecutionContext ctx) {
        JobDetail detail = ctx.getJobDetail();
        System.out.println("Job " + detail.getKey().getName() +
                           ", fire: " + ctx.getFireTime() +
                           ", previous: " + ctx.getPreviousFireTime() +
                           ", next: " + ctx.getNextFireTime());
    }
}