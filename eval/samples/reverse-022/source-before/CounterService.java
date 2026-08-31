public class CounterService {
    private int count;

    public synchronized int next() {
        count += 1;
        return count;
    }
}
