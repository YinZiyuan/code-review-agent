public class JobScheduler {
    public void schedule(List<Job> jobs) {
        Job current = null;
        for (Job job : jobs) {
            current = job;
            executor.submit(() -> run(current));
        }
    }
}
