package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.application.jobs.ReviewJobWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewJobSchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerTestConfiguration.class, ReviewJobSchedulingConfiguration.class)
            .withPropertyValues("code-review.server.worker.poll-interval=1d");

    @Test
    void cliModeDoesNotCreateTheScheduledPoller() {
        contextRunner.withPropertyValues("code-review.runtime=cli")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ScheduledReviewJobPoller.class));
    }

    @Test
    void serverModeCreatesOneScheduledPollerWhenAWorkerExists() {
        contextRunner.withPropertyValues("code-review.runtime=server")
                .run(context -> assertThat(context)
                        .hasSingleBean(ScheduledReviewJobPoller.class));
    }

    @Test
    void pollerContainsCycleFailureAndCanRunTheNextCycle() {
        ReviewJobWorker worker = mock(ReviewJobWorker.class);
        when(worker.runOnce())
                .thenThrow(new IllegalStateException("unsafe database detail"))
                .thenReturn(new ReviewJobWorker.WorkerCycleResult(0, 0, 0, 0, 0, 0, 0, 0, 0));
        ScheduledReviewJobPoller poller = new ScheduledReviewJobPoller(worker);

        poller.poll();
        poller.poll();

        verify(worker, org.mockito.Mockito.times(2)).runOnce();
    }

    @Test
    void pollerShutdownStopsNewCyclesAndLeavesLeaseRecoveryToTheWorkerContract() {
        ReviewJobWorker worker = mock(ReviewJobWorker.class);
        ScheduledReviewJobPoller poller = new ScheduledReviewJobPoller(worker);

        poller.shutdown();
        poller.poll();

        verify(worker).shutdown();
        verify(worker, never()).runOnce();
    }

    @Configuration(proxyBeanMethods = false)
    static class WorkerTestConfiguration {
        @Bean
        ReviewJobWorker reviewJobWorker() {
            return mock(ReviewJobWorker.class);
        }
    }
}
