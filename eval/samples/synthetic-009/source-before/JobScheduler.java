public class JobScheduler {
    public void schedule(List<Job> jobs) {
        for (Job job : jobs) {
            executor.submit(() -> run(job));
        }
    }
}
